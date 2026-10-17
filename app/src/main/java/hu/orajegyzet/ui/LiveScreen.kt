package hu.orajegyzet.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.orajegyzet.ai.LiveLocalTranslator
import hu.orajegyzet.ai.LiveTranslateClient
import hu.orajegyzet.ai.ModeSelector
import hu.orajegyzet.ai.SummaryEngine
import hu.orajegyzet.data.*
import hu.orajegyzet.rec.MicStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/** Egy lefordított szakasz. */
data class LiveLine(val source: String, val lang: String, val translation: String)

/**
 * Az élő tolmács vezérlője (Fordító 0.2): mikrofon → (Gemini Live | Whisper+Gemma) → feliratok.
 * Minden állapotváltozás a főszálon történik.
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
    val lines = mutableStateListOf<LiveLine>()
    /** Online: a folyamatban lévő (még nem lezárt) eredeti és magyar szöveg. */
    var partialSource by mutableStateOf("")
        private set
    var partialTranslation by mutableStateOf("")
        private set
    var partialLang by mutableStateOf("")
        private set
    var working by mutableStateOf(false)
        private set
    var lastMode: Mode = Mode.ONLINE
        private set

    private val main = Handler(Looper.getMainLooper())
    private fun ui(block: () -> Unit) { main.post(block) }

    private var mic: MicStream? = null
    private var online: LiveTranslateClient? = null
    private var local: LiveLocalTranslator? = null
    private var player: AudioTrack? = null
    private val playExec = Executors.newSingleThreadExecutor()

    fun start(mode: Mode, s: AppSettings, speak: Boolean) {
        if (running) return
        error = null; status = "Indítás…"; lastMode = mode
        partialSource = ""; partialTranslation = ""; partialLang = ""
        running = true
        when (mode) {
            Mode.ONLINE -> startOnline(s, speak)
            Mode.LOCAL -> startLocal(s)
        }
    }

    private fun startOnline(s: AppSettings, speak: Boolean) {
        if (speak) player = createPlayer()
        val client = LiveTranslateClient(s.geminiApiKey, s.liveModel, "hu", object : LiveTranslateClient.Listener {
            override fun onReady() = ui { status = "Kész – beszélhetsz (Gemini Live)" }
            override fun onSourceText(text: String, languageCode: String?) = ui {
                partialSource += text
                languageCode?.let { partialLang = it }
            }
            override fun onTranslatedText(text: String) = ui { partialTranslation += text }
            override fun onTranslatedAudio(pcm24k: ByteArray) {
                val p = player ?: return
                playExec.execute { runCatching { p.write(pcm24k, 0, pcm24k.size) } }
            }
            override fun onTurnComplete() = ui { commitPartial() }
            override fun onError(message: String) = ui { error = message }
            override fun onClosed() = ui {
                commitPartial()
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
                lines += LiveLine(source, sourceLang, translation)
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

    private fun commitPartial() {
        if (partialSource.isNotBlank() || partialTranslation.isNotBlank()) {
            lines += LiveLine(partialSource.trim(), partialLang, partialTranslation.trim())
        }
        partialSource = ""; partialTranslation = ""
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
        commitPartial()
        player?.let { p -> playExec.execute { runCatching { p.stop(); p.release() } } }
        player = null
        running = false
        status = "Leállítva."
    }

    fun clear() { lines.clear(); partialSource = ""; partialTranslation = "" }

    fun fullText(): Pair<String, String> =
        lines.joinToString("\n") { it.source } to lines.joinToString("\n") { it.translation }

    fun release() {
        stop()
        local?.release()
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
}

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
        if (effMode == LiveSession.Mode.ONLINE && !ModeSelector.hasNetwork(ctx, false)) {
            mode = LiveSession.Mode.LOCAL
        }
        session.start(mode ?: effMode, settings, speak && (mode ?: effMode) == LiveSession.Mode.ONLINE)
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) begin()
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Élő tolmács") }, navigationIcon = { BackButton(onBack) })
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 680.dp).fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = effMode == LiveSession.Mode.ONLINE,
                        onClick = { mode = LiveSession.Mode.ONLINE },
                        enabled = onlineAllowed && !session.running,
                        label = { Text("☁ Online (Gemini)") }
                    )
                    FilterChip(
                        selected = effMode == LiveSession.Mode.LOCAL,
                        onClick = { mode = LiveSession.Mode.LOCAL },
                        enabled = !session.running,
                        label = { Text("📱 Helyben") }
                    )
                }
                Text(
                    when {
                        settings.privacyLock -> "Adatvédelmi zár: csak helyben (Whisper + Gemma 4), kb. 3–6 mp késéssel."
                        !onlineAllowed -> "Online módhoz Gemini API-kulcs kell (Beállítások → Online)."
                        effMode == LiveSession.Mode.ONLINE -> "Gemini Live: kb. 1–2 mp késés. A hang a Google szerverére kerül."
                        else -> "Helyben: Whisper + Gemma 4, kb. 3–6 mp késés. Semmi nem hagyja el a telefont."
                    },
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (effMode == LiveSession.Mode.ONLINE) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = speak, enabled = !session.running,
                            onCheckedChange = { v -> scope.launch { Settings.setLiveSpeak(ctx, v) } })
                        Spacer(Modifier.width(8.dp))
                        Text("🔊 Magyar hang hangosan (fülhallgatóval a legjobb)", fontSize = 13.sp)
                    }
                }

                // indítás / leállítás
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (session.running) {
                        Button(onClick = { session.stop() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.weight(1f).height(56.dp)) {
                            Text("■ Leállítás", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(onClick = {
                            val ok = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                            if (ok) begin() else permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }, modifier = Modifier.weight(1f).height(56.dp)) {
                            Text("🎙 Tolmácsolás indítása", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                if (session.running) {
                    LinearProgressIndicator(progress = { session.level }, modifier = Modifier.fillMaxWidth())
                }
                session.status?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary) }
                session.error?.let { Text("❌ $it", fontSize = 13.sp, color = MaterialTheme.colorScheme.error) }
                Text("Fordító ${hu.orajegyzet.BuildConfig.VERSION_NAME}", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)

                // feliratok
                val listState = rememberLazyListState()
                val count = session.lines.size + 1
                LaunchedEffect(count, session.partialTranslation, session.working) {
                    listState.animateScrollToItem(count - 1)
                }
                Card(Modifier.fillMaxWidth().weight(1f)) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        itemsIndexed(session.lines) { _, line -> LiveLineView(line) }
                        item {
                            when {
                                session.partialSource.isNotBlank() || session.partialTranslation.isNotBlank() ->
                                    LiveLineView(LiveLine(session.partialSource, session.partialLang, session.partialTranslation), partial = true)
                                session.working -> Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp)); Text("Fordítás…", fontSize = 13.sp)
                                }
                                session.lines.isEmpty() -> Text("Itt jelennek meg a feliratok: felül kicsiben az eredeti, alatta a magyar.",
                                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                else -> Spacer(Modifier.height(1.dp))
                            }
                        }
                    }
                }

                if (!session.running && session.lines.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Tolmács", session.fullText().second))
                            android.widget.Toast.makeText(ctx, "Másolva", android.widget.Toast.LENGTH_SHORT).show()
                        }, modifier = Modifier.weight(1f)) { Text("📋 Másolás", fontSize = 13.sp) }
                        Button(onClick = {
                            val (src, hu) = session.fullText()
                            val lang = session.lines.firstOrNull { it.lang.isNotBlank() }?.lang.orEmpty()
                            val engine = if (session.lastMode == LiveSession.Mode.ONLINE) SummaryEngine.GEMINI else SummaryEngine.GEMMA
                            scope.launch(Dispatchers.IO) {
                                val id = TrDb.get(ctx).dao().insert(Translation(
                                    kind = TrKind.AUDIO, status = TrStatus.DONE, style = "NATURAL",
                                    original = src, sourceLang = lang, translation = hu, engine = engine,
                                    warning = "Élő tolmácsolás"))
                                withContext(Dispatchers.Main) { session.clear(); onOpenTranslation(id) }
                            }
                        }, modifier = Modifier.weight(1f)) { Text("💾 Mentés", fontSize = 13.sp) }
                        OutlinedButton(onClick = { session.clear() }, modifier = Modifier.weight(0.7f)) {
                            Text("✕", fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveLineView(line: LiveLine, partial: Boolean = false) {
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (line.source.isNotBlank()) {
                Text((if (line.lang.isNotBlank()) "[${line.lang}] " else "") + line.source,
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (line.translation.isNotBlank()) {
                Text(line.translation + if (partial) " …" else "", fontSize = 18.sp, lineHeight = 25.sp,
                    fontWeight = if (partial) FontWeight.Normal else FontWeight.Medium)
            }
        }
    }
}
