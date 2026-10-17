package hu.orajegyzet.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.orajegyzet.ai.LiveLocalTranslator
import hu.orajegyzet.ai.LiveTranslateClient
import hu.orajegyzet.ai.ModeSelector
import hu.orajegyzet.ai.SentenceStream
import hu.orajegyzet.ai.SummaryEngine
import hu.orajegyzet.data.*
import hu.orajegyzet.rec.MicStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Az élő tolmács vezérlője (Fordító 0.2.2): mikrofon → (Gemini Live | Whisper+Gemma) → két
 * külön, mondatonként számozott felirat-lista (eredeti és magyar). Minden állapotváltozás a főszálon.
 */
class LiveSession(private val ctx: Context) {
    enum class Mode { ONLINE, LOCAL }

    var running by mutableStateOf(false)
        private set
    var status by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var level by mutableStateOf(0f)
        private set
    var working by mutableStateOf(false)
        private set
    /** A felismert forrásnyelv (kód vagy név), a fejléc nyelvpár-jelvényéhez. */
    var sourceLang by mutableStateOf("")
        private set
    var startedAt by mutableStateOf(0L)
        private set
    var lastMode: Mode = Mode.ONLINE
        private set

    /** Lezárt mondatok / szakaszok, külön a két nyelvre. */
    val sourceLines = mutableStateListOf<String>()
    val targetLines = mutableStateListOf<String>()
    /** A még nyitott (érkező) mondat a két oldalon. */
    var sourcePartial by mutableStateOf("")
        private set
    var targetPartial by mutableStateOf("")
        private set

    private val srcStream = SentenceStream()
    private val dstStream = SentenceStream()

    private val main = Handler(Looper.getMainLooper())
    private fun ui(block: () -> Unit) { main.post(block) }
    /** Szünet után (új szöveg nélkül) a nyitott mondatot lezárjuk. */
    private val pauseFlushSrc = Runnable { flushSource() }
    private val pauseFlushDst = Runnable { flushTarget() }

    private var mic: MicStream? = null
    private var online: LiveTranslateClient? = null
    private var local: LiveLocalTranslator? = null
    private var player: AudioTrack? = null
    private val playExec = Executors.newSingleThreadExecutor()

    val hasContent: Boolean
        get() = sourceLines.isNotEmpty() || targetLines.isNotEmpty() ||
            sourcePartial.isNotBlank() || targetPartial.isNotBlank()

    fun start(mode: Mode, s: AppSettings, speak: Boolean) {
        if (running) return
        error = null; status = "Indítás…"; lastMode = mode
        startedAt = System.currentTimeMillis()
        running = true
        when (mode) {
            Mode.ONLINE -> startOnline(s, speak)
            Mode.LOCAL -> startLocal(s)
        }
    }

    private fun startOnline(s: AppSettings, speak: Boolean) {
        if (speak) player = createPlayer()
        val client = LiveTranslateClient(s.geminiApiKey, s.liveModel, "hu", object : LiveTranslateClient.Listener {
            override fun onReady() = ui { status = "Kész – beszélhetsz" }
            override fun onSourceText(text: String, languageCode: String?) = ui {
                languageCode?.takeIf { it.isNotBlank() }?.let { sourceLang = it }
                sourceLines += srcStream.append(text)
                sourcePartial = srcStream.partial
                main.removeCallbacks(pauseFlushSrc); main.postDelayed(pauseFlushSrc, PAUSE_MS)
            }
            override fun onTranslatedText(text: String) = ui {
                targetLines += dstStream.append(text)
                targetPartial = dstStream.partial
                main.removeCallbacks(pauseFlushDst); main.postDelayed(pauseFlushDst, PAUSE_MS)
            }
            override fun onTranslatedAudio(pcm24k: ByteArray) {
                val p = player ?: return
                playExec.execute { runCatching { p.write(pcm24k, 0, pcm24k.size) } }
            }
            override fun onTurnComplete() = ui { flushSource(); flushTarget() }
            override fun onError(message: String) = ui { error = message }
            override fun onClosed() = ui {
                flushSource(); flushTarget()
                if (running) { stopMic(); running = false; status = "A kapcsolat lezárult." }
            }
        })
        online = client
        client.connect()
        startMic(echoCancel = speak) { client.sendPcm(it) }
    }

    private fun startLocal(s: AppSettings) {
        val tr = LiveLocalTranslator(ctx, s, object : LiveLocalTranslator.Listener {
            override fun onStatus(text: String) = ui { status = text }
            override fun onSegmentStarted() = ui { working = true }
            override fun onSegment(source: String, sourceLang: String, translation: String) = ui {
                working = false
                if (sourceLang.isNotBlank()) this@LiveSession.sourceLang = sourceLang
                // helyben egy szakasz = egy sorpár, így a számozás a két oldalon biztosan egyezik
                sourceLines += source
                targetLines += translation
            }
            override fun onError(message: String) = ui { working = false; error = message }
        })
        local = tr
        tr.start()
        startMic(echoCancel = false) { tr.push(it) }
    }

    private fun startMic(echoCancel: Boolean, sink: (ByteArray) -> Unit) {
        val m = MicStream(echoCancel, onChunk = { chunk ->
            sink(chunk)
            val lv = (MicStream.levelOf(chunk) / 4000.0).coerceIn(0.0, 1.0).toFloat()
            ui { level = lv }
        }, onError = { msg -> ui { error = msg; stop() } })
        mic = m
        m.start()
    }

    private fun flushSource() {
        main.removeCallbacks(pauseFlushSrc)
        srcStream.flush()?.let { sourceLines += it }
        sourcePartial = ""
    }

    private fun flushTarget() {
        main.removeCallbacks(pauseFlushDst)
        dstStream.flush()?.let { targetLines += it }
        targetPartial = ""
    }

    private fun stopMic() {
        mic?.stop(); mic = null
        level = 0f
    }

    fun stop() {
        if (!running) return
        stopMic()
        online?.close(); online = null
        local?.stop()   // a sorban lévő szakaszok még lefordulnak; a Gemma utána zár
        flushSource(); flushTarget()
        player?.let { p -> playExec.execute { runCatching { p.stop(); p.release() } } }
        player = null
        running = false
        status = null
    }

    fun clear() {
        sourceLines.clear(); targetLines.clear()
        srcStream.flush(); dstStream.flush()
        sourcePartial = ""; targetPartial = ""; sourceLang = ""; error = null
    }

    fun fullText(): Pair<String, String> =
        sourceLines.joinToString("\n") to targetLines.joinToString("\n")

    fun release() {
        stop()
        local?.release()
        main.removeCallbacksAndMessages(null)
        playExec.shutdown()
    }

    private fun createPlayer(): AudioTrack? = runCatching {
        val min = AudioTrack.getMinBufferSize(24_000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(24_000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min, 24_000))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().apply { play() }
    }.getOrNull()

    companion object {
        /** Ennyi csend után a nyitott mondat új sorba kerül. */
        const val PAUSE_MS = 1500L
    }
}

/** A jelvény szövege: „de” → „DE”, „német” → „NÉMET” (rövid nevek maradnak olvashatók). */
internal fun langBadge(lang: String): String =
    lang.trim().substringBefore('-').uppercase(Locale("hu", "HU")).ifBlank { "?" }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(onBack: () -> Unit, onOpenTranslation: (Long) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by Settings.flow(ctx).collectAsState(initial = AppSettings())
    val session = remember { LiveSession(ctx.applicationContext) }
    DisposableEffect(Unit) { onDispose { session.release() } }

    // a képernyő ne aludjon el tolmácsolás közben
    val view = LocalView.current
    DisposableEffect(session.running) {
        view.keepScreenOn = session.running
        onDispose { view.keepScreenOn = false }
    }

    val onlineAllowed = !settings.privacyLock && settings.geminiApiKey.isNotBlank()
    var mode by remember { mutableStateOf<LiveSession.Mode?>(null) }
    val effMode = mode ?: if (onlineAllowed && ModeSelector.hasNetwork(ctx, false)) LiveSession.Mode.ONLINE
        else LiveSession.Mode.LOCAL
    val speak = settings.liveSpeak

    fun begin() {
        val m = if (effMode == LiveSession.Mode.ONLINE && !ModeSelector.hasNetwork(ctx, false))
            LiveSession.Mode.LOCAL.also { mode = it } else effMode
        session.clear()
        session.start(m, settings, speak && m == LiveSession.Mode.ONLINE)
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) begin()
    }
    fun requestStart() {
        val ok = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (ok) begin() else permLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    val showPanes = session.running || session.hasContent

    Scaffold(topBar = {
        if (session.running) {
            RunningBar(session, speakOn = speak, onToggleSpeak = null, onStop = { session.stop() })
        } else {
            TopAppBar(title = { Text("Élő tolmács") }, navigationIcon = { BackButton(onBack) })
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 900.dp).fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {

                if (!session.running) {
                    SetupPanel(
                        compact = showPanes,
                        effMode = effMode,
                        onlineAllowed = onlineAllowed,
                        privacyLock = settings.privacyLock,
                        speak = speak,
                        onMode = { mode = it },
                        onSpeak = { v -> scope.launch { Settings.setLiveSpeak(ctx, v) } },
                        onStart = { requestStart() }
                    )
                }
                session.error?.let { Text("❌ $it", fontSize = 13.sp, color = MaterialTheme.colorScheme.error) }
                if (session.running && session.status != null && !session.hasContent) {
                    Text(session.status!!, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                }

                if (showPanes) {
                    LivePanes(session, Modifier.weight(1f))
                    if (!session.running) {
                        ResultActions(
                            onCopy = {
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("Tolmács", session.fullText().second))
                                android.widget.Toast.makeText(ctx, "Másolva", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            onSave = {
                                val (src, hu) = session.fullText()
                                val engine = if (session.lastMode == LiveSession.Mode.ONLINE) SummaryEngine.GEMINI else SummaryEngine.GEMMA
                                val lang = session.sourceLang
                                scope.launch(Dispatchers.IO) {
                                    val id = TrDb.get(ctx).dao().insert(Translation(
                                        kind = TrKind.AUDIO, status = TrStatus.DONE, style = "NATURAL",
                                        original = src, sourceLang = lang, translation = hu, engine = engine,
                                        warning = "Élő tolmácsolás"))
                                    withContext(Dispatchers.Main) { session.clear(); onOpenTranslation(id) }
                                }
                            },
                            onClear = { session.clear() }
                        )
                    }
                } else {
                    EmptyHint(Modifier.weight(1f))
                }
            }
        }
    }
}

/** Futás közbeni egysoros fejléc: idő, nyelvpár, szint, hang, Stop. */
@Composable
private fun RunningBar(session: LiveSession, speakOn: Boolean, onToggleSpeak: (() -> Unit)?, onStop: () -> Unit) {
    var seconds by remember { mutableStateOf(0L) }
    LaunchedEffect(session.startedAt) {
        while (true) {
            seconds = ((System.currentTimeMillis() - session.startedAt) / 1000).coerceAtLeast(0)
            delay(500)
        }
    }
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("●", color = MaterialTheme.colorScheme.error, fontSize = 16.sp)
                Text("%d:%02d".format(seconds / 60, seconds % 60), fontWeight = FontWeight.Medium)
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(8.dp)) {
                    Text("${langBadge(session.sourceLang)} → HU", fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Text(if (session.lastMode == LiveSession.Mode.ONLINE) "☁" else "📱", fontSize = 14.sp)
                if (speakOn && session.lastMode == LiveSession.Mode.ONLINE) Text("🔊", fontSize = 14.sp)
                if (session.working) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.weight(1f))
                Button(onClick = onStop,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                    modifier = Modifier.height(36.dp)) {
                    Text("■ Stop", fontWeight = FontWeight.Bold)
                }
            }
            LinearProgressIndicator(progress = { session.level }, modifier = Modifier.fillMaxWidth().height(2.dp))
        }
    }
}

/** Indítás előtti / leállítás utáni beállítások; ha már van felirat, tömör (egy sor). */
@Composable
private fun SetupPanel(
    compact: Boolean,
    effMode: LiveSession.Mode,
    onlineAllowed: Boolean,
    privacyLock: Boolean,
    speak: Boolean,
    onMode: (LiveSession.Mode) -> Unit,
    onSpeak: (Boolean) -> Unit,
    onStart: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = effMode == LiveSession.Mode.ONLINE, onClick = { onMode(LiveSession.Mode.ONLINE) },
            enabled = onlineAllowed, label = { Text("☁ Online") })
        FilterChip(selected = effMode == LiveSession.Mode.LOCAL, onClick = { onMode(LiveSession.Mode.LOCAL) },
            label = { Text("📱 Helyben") })
        if (effMode == LiveSession.Mode.ONLINE) {
            Spacer(Modifier.weight(1f))
            Text("🔊", fontSize = 14.sp)
            Switch(checked = speak, onCheckedChange = onSpeak)
        }
    }
    if (!compact) {
        Text(
            when {
                privacyLock -> "Adatvédelmi zár: csak helyben (Whisper + Gemma 4), kb. 3–6 mp késéssel."
                !onlineAllowed -> "Online módhoz Gemini API-kulcs kell (Beállítások → Online)."
                effMode == LiveSession.Mode.ONLINE -> "Gemini Live: kb. 1–2 mp késés, a hang a Google szerverére kerül. " +
                    "A 🔊 kapcsolóval a magyar hang is szól (fülhallgatóval a legjobb)."
                else -> "Helyben: Whisper + Gemma 4, kb. 3–6 mp késés. Semmi nem hagyja el a telefont."
            },
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(if (compact) 44.dp else 52.dp)) {
        Text(if (compact) "🎙 Új tolmácsolás" else "🎙 Tolmácsolás indítása", fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

/** A két felirat-ablak: állóban egymás alatt (magyar nagyobb), fekvőben egymás mellett. */
@Composable
private fun LivePanes(session: LiveSession, modifier: Modifier) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val srcTitle = if (session.sourceLang.isNotBlank()) "Eredeti · ${langBadge(session.sourceLang)}" else "Eredeti"
    if (landscape) {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CaptionPane(srcTitle, session.sourceLines, session.sourcePartial, primary = false, Modifier.weight(1f).fillMaxHeight())
            CaptionPane("Magyar", session.targetLines, session.targetPartial, primary = true, Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CaptionPane(srcTitle, session.sourceLines, session.sourcePartial, primary = false, Modifier.weight(2f).fillMaxWidth())
            CaptionPane("Magyar", session.targetLines, session.targetPartial, primary = true, Modifier.weight(3f).fillMaxWidth())
        }
    }
}

/** Egy nyelv számozott, magától görgető felirat-listája; a legutóbbi sor kiemelve. */
@Composable
private fun CaptionPane(title: String, lines: List<String>, partial: String, primary: Boolean, modifier: Modifier) {
    val listState = rememberLazyListState()
    val total = lines.size + if (partial.isNotBlank()) 1 else 0
    LaunchedEffect(total, partial.length / 40) {
        if (total > 0) listState.animateScrollToItem(total - 1)
    }
    val border = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val baseSize = if (primary) 17.sp else 13.sp
    val lineHeight = if (primary) 24.sp else 19.sp
    Column(modifier
        .border(if (primary) 1.dp else 0.5.dp, border, RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
        .padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text(title, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer(Modifier.fillMaxSize()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(lines) { i, line ->
                    val latest = partial.isBlank() && i == lines.lastIndex
                    CaptionLine(i + 1, line, latest, primary, baseSize, lineHeight)
                }
                if (partial.isNotBlank()) item {
                    CaptionLine(lines.size + 1, "$partial …", latest = true, primary, baseSize, lineHeight, italic = true)
                }
            }
        }
    }
}

@Composable
private fun CaptionLine(n: Int, text: String, latest: Boolean, primary: Boolean,
                        size: androidx.compose.ui.unit.TextUnit, lineHeight: androidx.compose.ui.unit.TextUnit,
                        italic: Boolean = false) {
    val color = when {
        latest && primary -> MaterialTheme.colorScheme.onSurface
        latest -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row {
        Text("$n", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.width(22.dp).padding(top = 3.dp))
        Text(text, fontSize = size, lineHeight = lineHeight, color = color,
            fontWeight = if (latest && primary) FontWeight.Medium else FontWeight.Normal,
            fontStyle = if (italic) androidx.compose.ui.text.font.FontStyle.Italic else null)
    }
}

@Composable
private fun ResultActions(onCopy: () -> Unit, onSave: () -> Unit, onClear: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onCopy, modifier = Modifier.weight(1f)) { Text("📋 Másolás", fontSize = 13.sp) }
        Button(onClick = onSave, modifier = Modifier.weight(1f)) { Text("💾 Mentés", fontSize = 13.sp) }
        OutlinedButton(onClick = onClear, modifier = Modifier.weight(0.8f)) { Text("✕ Törlés", fontSize = 13.sp) }
    }
}

@Composable
private fun EmptyHint(modifier: Modifier) {
    Box(modifier.fillMaxWidth()
        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
        .padding(16.dp)) {
        Text("Indítás után két ablak jelenik meg: felül az eredeti nyelv, alul a magyar fordítás, " +
            "mondatonként számozva. Fekvő helyzetben a kettő egymás mellé kerül.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
