package hu.orajegyzet.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.orajegyzet.ai.SummaryEngine
import hu.orajegyzet.ai.TrStyle
import hu.orajegyzet.ai.Translator
import hu.orajegyzet.data.*
import hu.orajegyzet.rec.RecordingService
import hu.orajegyzet.work.TranslateWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Megosztásból érkező bemenet (más appból: szöveg vagy hangfájl). */
data class SharedInput(val text: String? = null, val audio: Uri? = null)

// =====================================================================================
// Fordító (főképernyő)
// =====================================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslatorScreen(
    shared: SharedInput?,
    onSharedConsumed: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onStartRecording: (style: String) -> Unit,
    onStopRecording: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by Settings.flow(ctx).collectAsState(initial = AppSettings())
    val dao = remember { TrDb.get(ctx).dao() }

    var input by rememberSaveable { mutableStateOf("") }
    var style by remember(settings.trStyle) { mutableStateOf(TrStyle.of(settings.trStyle)) }
    var currentId by rememberSaveable { mutableStateOf<Long?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val recordingId by RecordingService.active.collectAsState()
    LaunchedEffect(recordingId) { recordingId?.let { currentId = it } }

    val current by remember(currentId) {
        currentId?.let { dao.byIdFlow(it) } ?: flowOf(null)
    }.collectAsState(initial = null)

    // motor-kijelzés
    val engine = remember(settings) { Translator.engineFor(ctx, settings) }

    fun startText(text: String, st: TrStyle = style) {
        val clean = text.trim()
        if (clean.isEmpty()) { message = "Írj vagy illessz be szöveget, vagy indíts felvételt."; return }
        message = null
        scope.launch(Dispatchers.IO) {
            val id = dao.insert(Translation(kind = TrKind.TEXT, original = clean, style = st.name,
                status = TrStatus.QUEUED, progress = "Sorban áll…"))
            TranslateWorker.enqueue(ctx, id)
            withContext(Dispatchers.Main) { currentId = id }
        }
    }

    fun startAudioFile(uri: Uri) {
        message = "Hangfájl beolvasása…"
        scope.launch(Dispatchers.IO) {
            val r = runCatching { importAudio(ctx, uri) }
            r.onSuccess { (path, mime) ->
                val id = dao.insert(Translation(kind = TrKind.AUDIO, style = style.name, audioPath = path,
                    audioMime = mime, status = TrStatus.QUEUED, progress = "Sorban áll…"))
                TranslateWorker.enqueue(ctx, id)
                withContext(Dispatchers.Main) { currentId = id; message = null }
            }.onFailure { e ->
                withContext(Dispatchers.Main) { message = "A hangfájl nem olvasható: ${e.message}" }
            }
        }
    }

    // megosztásból érkező bemenet
    LaunchedEffect(shared) {
        shared ?: return@LaunchedEffect
        shared.text?.takeIf { it.isNotBlank() }?.let { input = it }
        shared.audio?.let { startAudioFile(it) }
        onSharedConsumed()
    }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { startAudioFile(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fordító", fontWeight = FontWeight.Bold) },
                actions = {
                    TextButton(onClick = onOpenHistory) { Text("🕘 Előzmények") }
                    TextButton(onClick = onOpenSettings) { Text("⚙") }
                }
            )
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 680.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .imePadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                EngineLine(engine, settings)

                // --- mód ---
                Text("Bármilyen nyelv → magyar", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(TrStyle.entries.toList()) { st ->
                        FilterChip(
                            selected = st == style,
                            onClick = {
                                style = st
                                scope.launch { Settings.setTrStyle(ctx, st.name) }
                            },
                            label = { Text(st.label) }
                        )
                    }
                }

                // --- bemenet ---
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Szöveg (bármilyen nyelven)") },
                    placeholder = { Text("Írd be, illeszd be, vagy oszd meg ide más appból…") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                    maxLines = 14
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.let { input = it }
                    }, modifier = Modifier.weight(1f)) { Text("📋 Beillesztés", fontSize = 13.sp) }
                    OutlinedButton(onClick = { input = "" }, enabled = input.isNotEmpty(),
                        modifier = Modifier.weight(1f)) { Text("✕ Törlés", fontSize = 13.sp) }
                    OutlinedButton(onClick = { audioPicker.launch(arrayOf("audio/*")) },
                        enabled = recordingId == null, modifier = Modifier.weight(1f)) {
                        Text("🎵 Hangfájl", fontSize = 13.sp)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { startText(input) },
                        enabled = recordingId == null && input.isNotBlank(),
                        modifier = Modifier.weight(1f).height(52.dp)
                    ) { Text("Fordítás", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                    RecordButton(
                        recording = recordingId != null,
                        onStart = { message = null; onStartRecording(style.name) },
                        onStop = onStopRecording
                    )
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }

                // --- eredmény ---
                current?.let { tr ->
                    TranslationCard(
                        tr = tr,
                        onRetranslate = { st -> startText(tr.original, st) },
                        onEditOriginal = { input = tr.original }
                    )
                }
            }
        }
    }
}

@Composable
private fun EngineLine(engine: String?, s: AppSettings) {
    val text = when (engine) {
        SummaryEngine.GEMINI -> "☁ Gemini (felhő) – ${s.geminiModel}"
        SummaryEngine.GEMMA -> "📱 Gemma 4 helyben" + if (s.privacyLock) " – adatvédelmi zár" else ""
        else -> "⚠️ Nincs fordítómotor – Beállítások → Modellek vagy Gemini-kulcs"
    }
    val color = if (engine == null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    Card(colors = CardDefaults.cardColors(containerColor = color)) {
        Text(text, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontSize = 13.sp,
            fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun RecordButton(recording: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    var seconds by remember { mutableStateOf(0L) }
    LaunchedEffect(recording) {
        while (recording) {
            seconds = ((System.currentTimeMillis() - RecordingService.startedAt) / 1000).coerceAtLeast(0)
            delay(500)
        }
    }
    if (recording) {
        Button(
            onClick = onStop,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.height(52.dp)
        ) { Text("■ %d:%02d".format(seconds / 60, seconds % 60), fontWeight = FontWeight.Bold) }
    } else {
        FilledTonalButton(onClick = onStart, modifier = Modifier.height(52.dp)) {
            Text("🎙 Beszéd", fontWeight = FontWeight.Bold)
        }
    }
}

// =====================================================================================
// Eredménykártya (főképernyő és részletnézet)
// =====================================================================================

@Composable
fun TranslationCard(
    tr: Translation,
    onRetranslate: ((TrStyle) -> Unit)?,
    onEditOriginal: (() -> Unit)?
) {
    val ctx = LocalContext.current
    val tts = rememberTts()
    var showOriginal by remember(tr.id) { mutableStateOf(tr.kind == TrKind.TEXT) }
    var otherStyleMenu by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // fejléc: nyelv → magyar, mód, motor
            Row(verticalAlignment = Alignment.CenterVertically) {
                val lang = tr.sourceLang.ifBlank { if (tr.status == TrStatus.DONE) "?" else "…" }
                Text("$lang → magyar", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(TrStyle.of(tr.style).label + (SummaryEngine.label(tr.engine)?.let { " · $it" } ?: ""),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            when (tr.status) {
                TrStatus.RECORDING -> Text("🎙 Felvétel folyamatban… A leállítás után fordítok.",
                    color = MaterialTheme.colorScheme.primary)
                TrStatus.QUEUED, TrStatus.PROCESSING -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(tr.progress ?: "Feldolgozás…", fontSize = 13.sp)
                }
                TrStatus.ERROR -> Text("❌ ${tr.error}", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }

            if (tr.translation.isNotBlank()) {
                SelectionContainer {
                    Text(tr.translation, fontSize = 16.sp, lineHeight = 23.sp)
                }
                tr.warning?.let { Text("⚠️ $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { copyText(ctx, tr.translation) }, modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 6.dp)) { Text("📋 Másolás", fontSize = 12.sp) }
                    OutlinedButton(onClick = { shareText(ctx, tr.translation) }, modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 6.dp)) { Text("↗ Megosztás", fontSize = 12.sp) }
                    OutlinedButton(onClick = { tts.speak(tr.translation) }, modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 6.dp)) { Text("🔊 Felolvasás", fontSize = 12.sp) }
                }
            }

            if (onRetranslate != null && tr.original.isNotBlank() &&
                (tr.status == TrStatus.DONE || tr.status == TrStatus.ERROR)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.weight(1f)) {
                        OutlinedButton(onClick = { otherStyleMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (tr.status == TrStatus.ERROR) "↻ Újra" else "↻ Más módban", fontSize = 12.sp)
                        }
                        DropdownMenu(expanded = otherStyleMenu, onDismissRequest = { otherStyleMenu = false }) {
                            TrStyle.entries.forEach { st ->
                                DropdownMenuItem(text = { Text(st.label) }, onClick = {
                                    otherStyleMenu = false; onRetranslate(st)
                                })
                            }
                        }
                    }
                    if (onEditOriginal != null) {
                        OutlinedButton(onClick = onEditOriginal, modifier = Modifier.weight(1f)) {
                            Text("✎ Eredeti szerkesztése", fontSize = 12.sp)
                        }
                    }
                }
            }

            if (tr.original.isNotBlank()) {
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().clickable { showOriginal = !showOriginal },
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (tr.kind == TrKind.AUDIO) "Átirat (eredeti nyelven)" else "Eredeti szöveg",
                        fontWeight = FontWeight.Medium, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(if (showOriginal) "▾" else "▸")
                }
                if (showOriginal) {
                    SelectionContainer {
                        Text(tr.original, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { copyText(ctx, tr.original) }) { Text("📋 Átirat másolása", fontSize = 12.sp) }
                    }
                }
            }
        }
    }
}

// =====================================================================================
// Előzmények és részletnézet
// =====================================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslationHistoryScreen(onOpen: (Long) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val items by remember { TrDb.get(ctx).dao().all() }.collectAsState(initial = emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(items, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) items else items.filter {
            it.original.lowercase().contains(q) || it.translation.lowercase().contains(q) ||
                it.sourceLang.lowercase().contains(q)
        }
    }
    val fmt = remember { SimpleDateFormat("yyyy. MM. dd. HH:mm", Locale("hu", "HU")) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Előzmények") }, navigationIcon = { BackButton(onBack) })
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 12.dp)) {
                item {
                    OutlinedTextField(query, { query = it }, label = { Text("Keresés") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                if (filtered.isEmpty()) item {
                    Text(if (items.isEmpty()) "Még nincs fordítás." else "Nincs találat.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                }
                items(filtered, key = { it.id }) { tr ->
                    Card(Modifier.fillMaxWidth().clickable { onOpen(tr.id) }) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text((if (tr.kind == TrKind.AUDIO) "🎙 " else "📝 ") +
                                    tr.sourceLang.ifBlank { "?" } + " → magyar · " + TrStyle.of(tr.style).label,
                                    fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                Text(fmt.format(Date(tr.createdAt)), fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            val preview = when (tr.status) {
                                TrStatus.DONE -> tr.translation
                                TrStatus.ERROR -> "❌ " + tr.error
                                TrStatus.RECORDING -> "Felvétel folyamatban…"
                                else -> tr.progress ?: "Feldolgozás…"
                            }
                            Text(preview, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslationDetailScreen(id: Long, onBack: () -> Unit, onOpen: (Long) -> Unit, onEditOriginal: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { TrDb.get(ctx).dao() }
    val tr by remember(id) { dao.byIdFlow(id) }.collectAsState(initial = null)
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Fordítás törlése") },
            text = { Text("Biztosan törlöd ezt a fordítást (és a hangfelvételt, ha van)?") },
            confirmButton = {
                Button(onClick = {
                    confirmDelete = false
                    val cur = tr
                    scope.launch(Dispatchers.IO) {
                        cur?.let {
                            TranslateWorker.cancel(ctx, it.id)
                            it.audioPath?.let { p -> runCatching { File(p).delete() } }
                            dao.delete(it)
                        }
                        withContext(Dispatchers.Main) { onBack() }
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                    Text("Törlés")
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Mégse") } }
        )
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Fordítás") }, navigationIcon = { BackButton(onBack) },
            actions = { TextButton(onClick = { confirmDelete = true }) { Text("🗑️") } })
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
                tr?.let { t ->
                    TranslationCard(
                        tr = t,
                        onRetranslate = { st ->
                            scope.launch(Dispatchers.IO) {
                                val newId = dao.insert(Translation(kind = TrKind.TEXT, original = t.original,
                                    style = st.name, status = TrStatus.QUEUED, progress = "Sorban áll…"))
                                TranslateWorker.enqueue(ctx, newId)
                                withContext(Dispatchers.Main) { onOpen(newId) }
                            }
                        },
                        onEditOriginal = { onEditOriginal(t.original) }
                    )
                } ?: Text("A fordítás nem található.")
            }
        }
    }
}

// =====================================================================================
// Segédek
// =====================================================================================

/** A kiválasztott hangfájl bemásolása az app tárhelyére; (útvonal, MIME) párt ad. */
private fun importAudio(ctx: Context, uri: Uri): Pair<String, String> {
    val cr = ctx.contentResolver
    val mime = cr.getType(uri)?.takeIf { it.startsWith("audio/") || it.startsWith("video/") } ?: "audio/mp4"
    val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "m4a"
    val out = File(ctx.filesDir, "imp_${System.currentTimeMillis()}.$ext")
    cr.openInputStream(uri)?.use { ins -> out.outputStream().use { ins.copyTo(it) } }
        ?: error("nem nyitható meg")
    if (out.length() == 0L) { out.delete(); error("üres fájl") }
    return out.absolutePath to mime
}

private fun copyText(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Fordítás", text))
    android.widget.Toast.makeText(ctx, "Másolva", android.widget.Toast.LENGTH_SHORT).show()
}

private fun shareText(ctx: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    ctx.startActivity(Intent.createChooser(send, "Megosztás").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Egyszerű magyar felolvasó (Android TextToSpeech). */
class Tts(ctx: Context) {
    private var ready = false
    private val tts: TextToSpeech = TextToSpeech(ctx.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
    }
    fun speak(text: String) {
        if (!ready) return
        runCatching { tts.setLanguage(Locale("hu", "HU")) }
        // a felolvasó egy hívásban kb. 4000 karaktert fogad
        text.chunked(3800).forEachIndexed { i, part ->
            tts.speak(part, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "tr_$i")
        }
    }
    fun shutdown() { runCatching { tts.stop(); tts.shutdown() } }
}

@Composable
private fun rememberTts(): Tts {
    val ctx = LocalContext.current
    val tts = remember { Tts(ctx) }
    DisposableEffect(Unit) { onDispose { tts.shutdown() } }
    return tts
}
