package hu.orajegyzet.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import hu.orajegyzet.ai.Extractive
import hu.orajegyzet.ai.GeminiPipeline
import hu.orajegyzet.ai.OfflinePipeline
import hu.orajegyzet.data.*
import hu.orajegyzet.rec.RecordingService
import hu.orajegyzet.work.ProcessingWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

fun fmt(min: Int) = "%d:%02d".format(min / 60, min % 60)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    onOpenNote: (Long) -> Unit,
    onOpenAssistant: () -> Unit,
    onOpenSchedule: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onStartRecording: (Long?, String?) -> Unit,
    onStopRecording: () -> Unit
) {
    val ctx = LocalContext.current
    val db = remember { Db.get(ctx) }
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()

    val appSettings by Settings.flow(ctx).collectAsState(initial = AppSettings())
    val schoolMode = appSettings.schoolMode
    val allLessonsToday by db.lessonDao().byDay(today.dayOfWeek.value).collectAsState(initial = emptyList())
    val lessons = if (schoolMode) allLessonsToday else emptyList()
    val projects by db.projectDao().all().collectAsState(initial = emptyList())
    val notes by db.noteDao().all()
        .map { list -> list.filter { it.dateIso == today.toString() } }
        .collectAsState(initial = emptyList())

    val activeRecNote = notes.firstOrNull { it.status == NoteStatus.RECORDING }
    val recording = activeRecNote != null || RecordingService.isRecordingActive

    var showStartDialog by remember { mutableStateOf(false) }
    var selectedContextType by remember { mutableStateOf("project") } // "lesson", "project", "custom"
    var selectedLessonId by remember { mutableStateOf<Long?>(null) }
    var selectedProjectId by remember { mutableStateOf("prj-01") }
    var selectedDocType by remember { mutableStateOf("JEG") } // JEG, EML, JZK, VEZ, STA
    var customTopic by remember { mutableStateOf("") }

    // --- Élő Felvételi Időzítő ---
    var elapsedSec by remember { mutableLongStateOf(0L) }
    LaunchedEffect(recording) {
        if (recording) {
            while (true) {
                val start = RecordingService.recordingStartMillis
                if (start > 0) {
                    elapsedSec = (System.currentTimeMillis() - start) / 1000L
                } else {
                    elapsedSec++
                }
                delay(1000)
            }
        } else {
            elapsedSec = 0L
        }
    }

    // --- Külső Hangfájl Importálása ---
    val audioPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { inputUri ->
            scope.launch(Dispatchers.IO) {
                try {
                    val destFile = File(ctx.filesDir, "ext_audio_${System.currentTimeMillis()}.m4a")
                    ctx.contentResolver.openInputStream(inputUri)?.use { input ->
                        destFile.outputStream().use { output -> input.copyTo(output) }
                    }

                    val todayStr = LocalDate.now().toString()
                    val existingToday = db.noteDao().allOnDate(todayStr)
                    val activeProj = projects.firstOrNull { it.id == selectedProjectId }

                    val docCode = NoteCodeGenerator.generate(
                        projectCode = activeProj?.code ?: "PROJ",
                        subject = "Importált Hangfájl",
                        docType = "JZK",
                        date = LocalDate.now(),
                        sequence = existingToday.size + 1
                    )

                    val nowMin = LocalTime.now().let { it.hour * 60 + it.minute }
                    val newNoteId = db.noteDao().insert(
                        Note(
                            lessonId = null,
                            projectId = selectedProjectId,
                            docCode = docCode,
                            docType = "JZK",
                            subject = "Importált Hangfájl (${docCode})",
                            dateIso = todayStr,
                            startMin = nowMin,
                            endMin = nowMin + 45,
                            status = NoteStatus.QUEUED,
                            audioPath = destFile.absolutePath
                        )
                    )
                    ProcessingWorker.enqueue(ctx, newNoteId)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    // --- Forgó fül animáció rögzítés közben ---
    val transition = rememberInfiniteTransition(label = "ear_pulse")
    val earAngle by transition.animateFloat(
        initialValue = -12f, targetValue = 12f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
        label = "ear_angle"
    )

    if (showStartDialog) {
        val activeProj = projects.firstOrNull { it.id == selectedProjectId }
        val previewCode = NoteCodeGenerator.generate(
            projectCode = if (selectedContextType == "project") activeProj?.code else null,
            subject = when (selectedContextType) {
                "lesson" -> lessons.firstOrNull { it.id == selectedLessonId }?.subject ?: "Óra"
                "project" -> activeProj?.name ?: "Projekt"
                else -> customTopic.ifBlank { "Téma" }
            },
            docType = selectedDocType,
            date = LocalDate.now()
        )

        AlertDialog(
            onDismissRequest = { showStartDialog = false },
            title = { Text("Új Felvétel & Jegyzőkönyv Indítása") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("1. Kontextus / Téma választása:", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item {
                            FilterChip(
                                selected = selectedContextType == "project",
                                onClick = { selectedContextType = "project" },
                                label = { Text("Projekt", maxLines = 1) }
                            )
                        }
                        if (schoolMode) item {
                            FilterChip(
                                selected = selectedContextType == "lesson",
                                onClick = { selectedContextType = "lesson" },
                                label = { Text("Órarendi óra", maxLines = 1) }
                            )
                        }
                        item {
                            FilterChip(
                                selected = selectedContextType == "custom",
                                onClick = { selectedContextType = "custom" },
                                label = { Text("Egyedi téma", maxLines = 1) }
                            )
                        }
                    }

                    if (selectedContextType == "project" && projects.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(projects) { p ->
                                FilterChip(
                                    selected = selectedProjectId == p.id,
                                    onClick = { selectedProjectId = p.id },
                                    label = { Text(p.code, maxLines = 1) }
                                )
                            }
                        }
                    } else if (selectedContextType == "lesson" && lessons.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(lessons) { l ->
                                FilterChip(
                                    selected = selectedLessonId == l.id,
                                    onClick = { selectedLessonId = l.id },
                                    label = { Text(l.subject, maxLines = 1) }
                                )
                            }
                        }
                    } else if (selectedContextType == "custom") {
                        OutlinedTextField(
                            value = customTopic,
                            onValueChange = { customTopic = it },
                            label = { Text("Téma / Megbeszélés neve") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Text("2. Dokumentum Típusa:", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item { FilterChip(selected = selectedDocType == "JEG", onClick = { selectedDocType = "JEG" }, label = { Text("📝 JEG", maxLines = 1) }) }
                        item { FilterChip(selected = selectedDocType == "EML", onClick = { selectedDocType = "EML" }, label = { Text("📌 EML", maxLines = 1) }) }
                        item { FilterChip(selected = selectedDocType == "JZK", onClick = { selectedDocType = "JZK" }, label = { Text("📋 JZK", maxLines = 1) }) }
                        item { FilterChip(selected = selectedDocType == "VEZ", onClick = { selectedDocType = "VEZ" }, label = { Text("👑 VEZ", maxLines = 1) }) }
                        item { FilterChip(selected = selectedDocType == "STA", onClick = { selectedDocType = "STA" }, label = { Text("📊 STA", maxLines = 1) }) }
                    }

                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.padding(10.dp)) {
                            Text("Generált Dok. Kód:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(previewCode, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showStartDialog = false
                    val title = when (selectedContextType) {
                        "lesson" -> lessons.firstOrNull { it.id == selectedLessonId }?.subject ?: "Óra"
                        "project" -> activeProj?.name ?: "Projekt megbeszélés"
                        else -> customTopic.ifBlank { "Megbeszélés" }
                    }
                    val prjId = if (selectedContextType == "project") selectedProjectId else null
                    val prjCode = if (selectedContextType == "project") activeProj?.code else null

                    RecordingService.start(
                        ctx = ctx,
                        lessonId = if (selectedContextType == "lesson") selectedLessonId else null,
                        title = title,
                        projectId = prjId,
                        docType = selectedDocType,
                        projectCode = prjCode
                    )
                }) { Text("● Felvétel Indítása") }
            },
            dismissButton = { TextButton(onClick = { showStartDialog = false }) { Text("Mégse") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                actions = {
                    TextButton(onClick = onOpenAssistant) { Text("Projekt AI", maxLines = 1) }
                    TextButton(onClick = onOpenHistory) { Text("Korábbi", maxLines = 1) }
                    if (schoolMode) TextButton(onClick = onOpenSchedule) { Text("Órarend", maxLines = 1) }
                    TextButton(onClick = onOpenSettings) { Text("Beállítások", maxLines = 1) }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = {
                if (recording) onStopRecording()
                else showStartDialog = true
            }) {
                Text(if (recording) "■ Felvétel leállítása" else "● Felvétel indítása")
            }
        }
    ) { pad ->
        Box(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                    // --- AKCIÓ SÁV & KÜLSŐ HANG IMPORTÁLÁSA ---
                    item {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(if (schoolMode) "Mai órák & Projektek" else "Mai megbeszélések",
                                fontWeight = FontWeight.Medium, fontSize = 18.sp)
                            OutlinedButton(
                                onClick = { audioPicker.launch("audio/*") }
                            ) {
                                Text("📁 Hang", fontSize = 12.sp, maxLines = 1)
                            }
                        }
                        if (lessons.isEmpty() && projects.isEmpty()) {
                            Text(
                                if (schoolMode) "Nincs felvitt óra vagy projekt mára." else "Még nincs projekt. Hozz létre egyet a Projekt AI menüben.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    items(lessons) { l ->
                        val note = notes.firstOrNull { it.lessonId == l.id }
                        Card {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(l.subject, fontWeight = FontWeight.Medium)
                                    Text("${fmt(l.startMin)}–${fmt(l.endMin)}" +
                                        (if (l.room.isNotBlank()) " · ${l.room}" else ""),
                                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                when (note?.status) {
                                    NoteStatus.RECORDING -> Text("● felvétel", color = MaterialTheme.colorScheme.error)
                                    NoteStatus.QUEUED -> ProcessingIndicator(label = note.progress ?: "Sorban áll…")
                                    NoteStatus.PROCESSING -> ProcessingIndicator(label = note.progress)
                                    NoteStatus.DONE -> TextButton(onClick = { onOpenNote(note.id) }) { Text("Jegyzet") }
                                    NoteStatus.ERROR -> Text("hiba", color = MaterialTheme.colorScheme.error)
                                    null -> {}
                                }
                            }
                        }
                    }

                    // --- ÉLŐ FELVÉTELI BANNER A KIJELZŐ KÖZEPÉN (DUPLIKÁLT LEÁLLÍTÁS GOMB NÉLKÜL) ---
                    if (recording) {
                        item {
                            Spacer(Modifier.height(16.dp))
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(54.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.error)
                                            .rotate(earAngle),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("👂", fontSize = 30.sp)
                                    }
                                    Spacer(Modifier.width(16.dp))
                                    Column(horizontalAlignment = Alignment.Start) {
                                        Text(
                                            "● FELVÉTEL FOLYAMATBAN…",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                        val m = elapsedSec / 60
                                        val s = elapsedSec % 60
                                        Text(
                                            "%02d:%02d".format(m, s),
                                            fontWeight = FontWeight.Black,
                                            fontSize = 28.sp,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                        }
                    }

                    item {
                        Spacer(Modifier.height(8.dp))
                        Text("Mai jegyzetek & Dok. Kódok", fontWeight = FontWeight.Medium, fontSize = 18.sp)
                    }

                    val processing = notes.filter {
                        it.status == NoteStatus.PROCESSING || it.status == NoteStatus.QUEUED
                    }
                    items(processing) { n ->
                        Card {
                            Row(Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("${n.subject} · ${n.docCode.ifBlank { fmt(n.startMin) }}", fontWeight = FontWeight.Medium)
                                    ProcessingIndicator(Modifier.padding(top = 4.dp),
                                        label = n.progress ?: if (n.status == NoteStatus.QUEUED) "Sorban áll…" else null)
                                }
                            }
                        }
                    }

                    items(notes.filter { it.status == NoteStatus.DONE }) { n ->
                        Card(onClick = { onOpenNote(n.id) }) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(n.subject, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                        if (n.docCode.isNotBlank()) {
                                            Spacer(Modifier.height(2.dp))
                                            Text(
                                                n.docCode,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                    IconButton(onClick = { onOpenNote(n.id) }) {
                                        Text("✏️", fontSize = 18.sp)
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                EngineBadge(n.summaryEngine)
                                Spacer(Modifier.height(4.dp))
                                Text(n.summary.take(140) + if (n.summary.length > 140) "…" else "",
                                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteDetailScreen(noteId: Long, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { Db.get(ctx) }
    val note by db.noteDao().byIdFlow(noteId).collectAsState(initial = null)
    val n = note ?: return

    var editing by remember { mutableStateOf(false) }
    var eSummary by remember { mutableStateOf("") }
    var eStructured by remember { mutableStateOf("") }
    var eTranscript by remember { mutableStateOf("") }

    var alertMessage by remember { mutableStateOf<String?>(null) }
    var selectedCustomPrompt by remember { mutableStateOf<String?>(null) }
    val savedSummaries by db.aiReportDao().byNote(noteId).collectAsState(initial = emptyList())
    var expandedSavedId by remember { mutableStateOf<Long?>(null) }
    var customSummaryResult by remember { mutableStateOf<String?>(null) }
    var isGeneratingCustomSummary by remember { mutableStateOf(false) }

    // Magyar felolvasó (beépített Android TTS, offline)
    val tts = remember {
        val holder = arrayOfNulls<TextToSpeech>(1)
        holder[0] = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) holder[0]?.setLanguage(Locale("hu", "HU"))
        }
        holder[0]!!
    }
    var speaking by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { runCatching { tts.stop(); tts.shutdown() } } }

    fun fullText(): String = buildString {
        if (n.docCode.isNotBlank()) append("DOK. KÓD: ").append(n.docCode).append("\n")
        append(n.subject).append(" · ").append(n.dateIso).append("\n\n")
        if (n.summary.isNotBlank()) append("ÖSSZEFOGLALÓ\n").append(n.summary).append("\n\n")
        if (n.structured.isNotBlank()) append("JEGYZET\n").append(n.structured).append("\n\n")
        if (n.keywords.isNotBlank()) append("KULCSFOGALMAK\n").append(n.keywords).append("\n\n")
        if (n.transcript.isNotBlank()) append("LEIRAT\n").append(n.transcript)
    }

    fun sendEmail() {
        scope.launch(Dispatchers.IO) {
            val s = Settings.get(ctx)
            if (s.email.isBlank()) {
                alertMessage = "Kérlek add meg a címzett e-mail címet a Beállítások menüpontban!"
            } else {
                try {
                    val intent = Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("mailto:${s.email}")
                        putExtra(Intent.EXTRA_SUBJECT, "[AssiSten-s] ${n.docCode.ifBlank { n.subject }} - ${n.dateIso}")
                        putExtra(Intent.EXTRA_TEXT, fullText())
                    }
                    ctx.startActivity(Intent.createChooser(intent, "E-mail küldése"))
                } catch (e: Exception) {
                    alertMessage = "Hiba az e-mail küldéskor: ${e.message}"
                }
            }
        }
    }

    fun saveToPhone() {
        scope.launch(Dispatchers.IO) {
            try {
                val fileName = "${n.docCode.ifBlank { "JEGYZET_${n.id}" }}.txt"
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val targetFile = File(downloadsDir, fileName)
                targetFile.writeText(fullText())
                alertMessage = "Sikeresen elmentve a Letöltések mappába:\n${targetFile.absolutePath}"
            } catch (e: Exception) {
                alertMessage = "Hiba a fájl mentésekor: ${e.message}"
            }
        }
    }

    fun requestCustomSummary(type: String) {
        isGeneratingCustomSummary = true
        selectedCustomPrompt = type
        customSummaryResult = "Összefoglaló generálása ($type)..."

        scope.launch(Dispatchers.IO) {
            try {
                val s = Settings.get(ctx)
                val sourceText = n.transcript.ifBlank { n.summary }
                // 1.1.1: minden típus saját, határozott szerkezetet kap (SummaryStyles),
                // és ugyanezt kapja a Gemini és a Gemma is.
                val promptInstruction = hu.orajegyzet.ai.SummaryStyles.instruction(
                    hu.orajegyzet.ai.SummaryStyles.fromLabel(type))

                val offline = OfflinePipeline(
                    ctx,
                    useGemma = s.useGemma,
                    whisperThreads = s.whisperThreads,
                    keepWarm = s.keepModelWarm,
                    warmMinutes = s.warmMinutes,
                    summaryBlockChars = s.summaryBlockChars,
                    chunkSec = s.whisperChunkSec,
                    overlapSec = s.whisperOverlapSec,
                    onProgress = { p -> customSummaryResult = "$p …" }
                )

                // 1) Gemini, ha a mód engedi ÉS most tényleg van hálózat; hibánál Gemma.
                var label = type
                var warning: String? = null
                var engine: String = hu.orajegyzet.ai.SummaryEngine.EXTRACTIVE
                val resultText: String = if (hu.orajegyzet.ai.ModeSelector.canUseGeminiNow(ctx, s)) {
                    try {
                        val r = GeminiPipeline(s.geminiApiKey, s.geminiModel)
                            .generateFreeText(promptInstruction, sourceText)
                        label = "$type · ☁ Gemini"
                        engine = hu.orajegyzet.ai.SummaryEngine.GEMINI
                        r
                    } catch (e: Exception) {
                        val st = if (s.useGemma) offline.gemmaStyle(promptInstruction, sourceText)
                            else OfflinePipeline.StyleResult(Extractive.summarize(sourceText, 6),
                                hu.orajegyzet.ai.SummaryEngine.EXTRACTIVE, "A Gemma ki van kapcsolva.")
                        warning = "A Gemini nem volt elérhető (${e.message}), offline készült." +
                            (st.warning?.let { " $it" } ?: "")
                        label = "$type · ${hu.orajegyzet.ai.SummaryEngine.label(st.engine)}"
                        engine = st.engine
                        st.text
                    }
                } else if (s.useGemma) {
                    val st = offline.gemmaStyle(promptInstruction, sourceText)
                    warning = st.warning
                    label = "$type · ${hu.orajegyzet.ai.SummaryEngine.label(st.engine)}"
                    engine = st.engine
                    st.text
                } else {
                    warning = "A Gemma ki van kapcsolva, és most nincs Gemini (hálózat/kulcs/mód) – kivonat készült."
                    label = "$type · Kivonat (nem AI)"
                    Extractive.summarize(sourceText, 6)
                }

                selectedCustomPrompt = label
                val echoed = OfflinePipeline.echoesPrompt(resultText, promptInstruction)
                customSummaryResult = if (echoed)
                    "Az összefoglaló nem sikerült, próbáld újra."
                else resultText + (warning?.let { "\n\n_⚠️ ${it}_" } ?: "")
                // 1.1.5: az AI-val (Gemini / Gemma) készült összefoglaló mentése a jegyzethez és a projekthez
                if (!echoed && (engine == hu.orajegyzet.ai.SummaryEngine.GEMINI || engine == hu.orajegyzet.ai.SummaryEngine.GEMMA)) {
                    hu.orajegyzet.ai.ReportArchive.save(ctx, type, "$type – ${n.subject} (${n.dateIso})", resultText,
                        engine, warning, projectId = n.projectId, noteId = n.id)
                }
            } catch (e: Exception) {
                customSummaryResult = "Hiba: ${e.message}"
            } finally {
                isGeneratingCustomSummary = false
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text(if (n.docCode.isNotBlank()) n.docCode else n.subject) }, navigationIcon = { BackButton(onBack) })
    }) { pad ->
        LazyColumn(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column {
                    Text("${n.subject} · ${n.dateIso} · ${fmt(n.startMin)}–${fmt(n.endMin)}" +
                        (n.processedOnline?.let { if (it) " · online" else " · offline" } ?: ""),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    if (n.summaryEngine != null) {
                        Spacer(Modifier.height(4.dp))
                        EngineBadge(n.summaryEngine)
                    }
                }
            }
            if (!n.summaryWarning.isNullOrBlank() && n.status == NoteStatus.DONE) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF4CE))) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text("⚠️ ${n.summaryWarning}", fontSize = 13.sp, color = Color(0xFF5C4400))
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    scope.launch { db.noteDao().update(n.copy(status = NoteStatus.PROCESSING, error = "")) }
                                    ProcessingWorker.resummarize(ctx, n.id)
                                }) { Text("Összefoglaló újra", maxLines = 1) }
                                if (n.summaryEngine == hu.orajegyzet.ai.SummaryEngine.GEMMA && n.transcript.isNotBlank()) {
                                    OutlinedButton(onClick = {
                                        selectedCustomPrompt = "Kivonat (nem AI)"
                                        customSummaryResult = Extractive.summarize(n.transcript, 8)
                                    }) { Text("Kivonat", maxLines = 1) }
                                }
                            }
                        }
                    }
                }
            }

            if (!editing) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // 1. Akció sor (Görgethető gombok, így nincs függőleges szövegtörés)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item {
                                OutlinedButton(onClick = {
                                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("jegyzet", fullText()))
                                }) { Text("📋 Másolás", maxLines = 1) }
                            }
                            item {
                                OutlinedButton(onClick = {
                                    val i = Intent(Intent.ACTION_SEND).setType("text/plain")
                                        .putExtra(Intent.EXTRA_TEXT, fullText())
                                    ctx.startActivity(Intent.createChooser(i, "Jegyzet megosztása"))
                                }) { Text("🔗 Megosztás", maxLines = 1) }
                            }
                            item {
                                OutlinedButton(onClick = { sendEmail() }) { Text("📧 Email küldése", maxLines = 1) }
                            }
                            item {
                                OutlinedButton(onClick = { saveToPhone() }) { Text("💾 Mentés telefonra", maxLines = 1) }
                            }
                            item {
                                OutlinedButton(onClick = {
                                    eSummary = n.summary; eStructured = n.structured; eTranscript = n.transcript
                                    editing = true
                                }) { Text("✏️ Szerkesztés", maxLines = 1) }
                            }
                            item {
                                OutlinedButton(onClick = {
                                    if (speaking) { runCatching { tts.stop() }; speaking = false }
                                    else {
                                        val text = if (n.summary.isNotBlank() && !n.summary.startsWith("("))
                                            n.summary else n.transcript
                                        if (text.isNotBlank()) {
                                            runCatching { tts.setLanguage(Locale("hu", "HU")) }
                                            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "note")
                                            speaking = true
                                        }
                                    }
                                }) { Text(if (speaking) "⏹️ Leállítás" else "🔊 Felolvasás", maxLines = 1) }
                            }
                        }

                        // 2. Típus szerinti összefoglaló kérése
                        Text("Különböző típusú összefoglaló kérése:", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item {
                                FilterChip(
                                    selected = false,
                                    onClick = { requestCustomSummary("Vezetői") },
                                    label = { Text("👑 Vezetői", maxLines = 1) }
                                )
                            }
                            item {
                                FilterChip(
                                    selected = false,
                                    onClick = { requestCustomSummary("Jegyzőkönyv") },
                                    label = { Text("📋 Jegyzőkönyv", maxLines = 1) }
                                )
                            }
                            item {
                                FilterChip(
                                    selected = false,
                                    onClick = { requestCustomSummary("Teendők") },
                                    label = { Text("📌 Teendők & Akciók", maxLines = 1) }
                                )
                            }
                            item {
                                FilterChip(
                                    selected = false,
                                    onClick = { requestCustomSummary("Részletes jegyzet") },
                                    label = { Text("📝 Részletes Jegyzet", maxLines = 1) }
                                )
                            }
                        }

                        // 3. Másodlagos akciók (Újrafeldolgozás hangfájlból & Újra-összefoglalás)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val audioOk = n.audioPath?.let { File(it).exists() } == true
                            if (audioOk) {
                                item {
                                    Button(onClick = {
                                        scope.launch { db.noteDao().update(n.copy(status = NoteStatus.PROCESSING, error = "")) }
                                        ProcessingWorker.reprocessAudio(ctx, n.id)
                                    }) { Text("🔄 Újrafeldolgozás hangfájlból", maxLines = 1) }
                                }
                            }
                            item {
                                OutlinedButton(onClick = {
                                    scope.launch { db.noteDao().update(n.copy(status = NoteStatus.PROCESSING, error = "")) }
                                    ProcessingWorker.resummarize(ctx, n.id)
                                }) { Text("✨ Újra-összefoglalás leiratból", maxLines = 1) }
                            }
                        }

                        if (n.audioPath != null && File(n.audioPath!!).exists()) {
                            Text("A nyers hangfájl elérhető a készüléken.",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            if (customSummaryResult != null) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Generált Összefoglaló (${selectedCustomPrompt ?: ""}):", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Spacer(Modifier.height(4.dp))
                            MarkdownText(customSummaryResult!!)
                        }
                    }
                }
            }

            // 1.1.5: a jegyzethez mentett AI-összefoglalók
            if (savedSummaries.isNotEmpty() && !editing) {
                item {
                    Text("Mentett összefoglalók (${savedSummaries.size})", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                items(savedSummaries, key = { it.id }) { r ->
                    Card(onClick = { expandedSavedId = if (expandedSavedId == r.id) null else r.id }) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(r.type, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                    Text(
                                        java.time.Instant.ofEpochMilli(r.createdAt).atZone(java.time.ZoneId.systemDefault())
                                            .toLocalDateTime().toString().take(16).replace('T', ' ') +
                                            (r.engine?.let { hu.orajegyzet.ai.SummaryEngine.label(it) }?.let { " · $it" } ?: ""),
                                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(if (expandedSavedId == r.id) "▲" else "▼")
                            }
                            if (expandedSavedId == r.id) {
                                Spacer(Modifier.height(6.dp))
                                MarkdownText(r.text)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = {
                                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cm.setPrimaryClip(ClipData.newPlainText(r.type, r.text))
                                    }) { Text("Másolás") }
                                    TextButton(onClick = {
                                        scope.launch(Dispatchers.IO) { db.aiReportDao().delete(r) }
                                    }) { Text("Törlés", color = MaterialTheme.colorScheme.error) }
                                }
                            }
                        }
                    }
                }
            }

            if (editing) {
                item {
                    OutlinedTextField(value = eSummary, onValueChange = { eSummary = it },
                        label = { Text("Összefoglaló") }, modifier = Modifier.fillMaxWidth())
                }
                item {
                    OutlinedTextField(value = eStructured, onValueChange = { eStructured = it },
                        label = { Text("Jegyzet (markdown)") }, modifier = Modifier.fillMaxWidth())
                }
                item {
                    OutlinedTextField(value = eTranscript, onValueChange = { eTranscript = it },
                        label = { Text("Leirat") }, modifier = Modifier.fillMaxWidth())
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scope.launch {
                                db.noteDao().update(
                                    n.copy(summary = eSummary, structured = eStructured, transcript = eTranscript))
                                editing = false
                            }
                        }) { Text("Mentés") }
                        TextButton(onClick = { editing = false }) { Text("Mégse") }
                    }
                }
            } else {
                if (n.status == NoteStatus.PROCESSING || n.status == NoteStatus.QUEUED) {
                    item { ProcessingIndicator(label = n.progress) }
                }
                if (n.summary.isNotBlank()) {
                    item { Text("Összefoglaló", fontWeight = FontWeight.Medium, fontSize = 18.sp) }
                    item { MarkdownText(n.summary) }
                }
                if (n.structured.isNotBlank()) {
                    item { Text("Jegyzet", fontWeight = FontWeight.Medium, fontSize = 18.sp) }
                    item { MarkdownText(n.structured) }
                }
                if (n.keywords.isNotBlank()) {
                    item { Text("Kulcsfogalmak", fontWeight = FontWeight.Medium, fontSize = 18.sp) }
                    item { Text(n.keywords) }
                }
                if (n.transcript.isNotBlank()) {
                    item { Text("Teljes leirat", fontWeight = FontWeight.Medium, fontSize = 18.sp) }
                    item { Text(n.transcript, fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (n.error.isNotBlank()) {
                    item { Text("Hiba: ${n.error}", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }

    if (alertMessage != null) {
        AlertDialog(
            onDismissRequest = { alertMessage = null },
            title = { Text("Értesítés") },
            text = { Text(alertMessage!!) },
            confirmButton = {
                TextButton(onClick = { alertMessage = null }) { Text("Rendben") }
            }
        )
    }
}
