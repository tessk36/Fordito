package hu.orajegyzet.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.orajegyzet.ai.GeminiPipeline
import hu.orajegyzet.bell.BellScheduler
import hu.orajegyzet.data.*
import hu.orajegyzet.work.DigestWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private val DAYS = listOf("H", "K", "Sze", "Cs", "P", "Szo", "V")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val db = remember { Db.get(ctx) }
    val scope = rememberCoroutineScope()
    val lessons by db.lessonDao().all().collectAsState(initial = emptyList())

    // --- Órarend befényképezése (Gemini képfelismerés) ---
    var photoStatus by remember { mutableStateOf<String?>(null) }
    val cameraUri = remember {
        val dir = java.io.File(ctx.cacheDir, "images").apply { mkdirs() }
        val f = java.io.File(dir, "schedule.jpg")
        androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
    }

    fun importFromImage(uri: android.net.Uri) {
        photoStatus = "Órarend felismerése a képből…"
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val s = Settings.get(ctx)
                if (s.geminiApiKey.isBlank()) {
                    photoStatus = "Ehhez Gemini API-kulcs kell (Beállítások)."
                    return@launch
                }
                val parsed = hu.orajegyzet.ai.ScheduleParser.parse(ctx, uri, s.geminiApiKey, s.geminiModel)
                if (parsed.isEmpty()) {
                    photoStatus = "Nem sikerült órát kiolvasni a képből."
                } else {
                    parsed.forEach { db.lessonDao().insert(it) }
                    BellScheduler.rescheduleAll(ctx, db.lessonDao())
                    photoStatus = "${parsed.size} óra hozzáadva a képből."
                }
            } catch (e: Exception) {
                photoStatus = "Hiba: ${e.message}"
            }
        }
    }

    val galleryPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { importFromImage(it) } }

    val cameraLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture()
    ) { success -> if (success) importFromImage(cameraUri) }

    var subject by remember { mutableStateOf("") }
    var teacher by remember { mutableStateOf("") }
    var room by remember { mutableStateOf("") }
    var day by remember { mutableIntStateOf(1) }
    var start by remember { mutableStateOf("8:00") }
    var end by remember { mutableStateOf("8:45") }

    fun parseMin(t: String): Int? {
        val p = t.split(":"); if (p.size != 2) return null
        val h = p[0].trim().toIntOrNull() ?: return null
        val m = p[1].trim().toIntOrNull() ?: return null
        return if (h in 0..23 && m in 0..59) h * 60 + m else null
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Órarend") },
            navigationIcon = { BackButton(onBack) })
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 640.dp).fillMaxWidth().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text("Órarend befényképezése", fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("Fotózd le a papír/digitális órarended, és az app kitölti.",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { cameraLauncher.launch(cameraUri) },
                                modifier = Modifier.weight(1f)) { Text("Fényképezés") }
                            OutlinedButton(onClick = { galleryPicker.launch("image/*") },
                                modifier = Modifier.weight(1f)) { Text("Galériából") }
                        }
                        photoStatus?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Új óra", fontWeight = FontWeight.Medium)
                OutlinedTextField(subject, { subject = it }, label = { Text("Tantárgy") },
                    modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(teacher, { teacher = it }, label = { Text("Tanár") },
                        modifier = Modifier.weight(1f))
                    OutlinedTextField(room, { room = it }, label = { Text("Terem") },
                        modifier = Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DAYS.forEachIndexed { i, d ->
                        FilterChip(selected = day == i + 1, onClick = { day = i + 1 },
                            label = { Text(d) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(start, { start = it }, label = { Text("Kezdés (Ó:PP)") },
                        modifier = Modifier.weight(1f))
                    OutlinedTextField(end, { end = it }, label = { Text("Vége (Ó:PP)") },
                        modifier = Modifier.weight(1f))
                }
                Button(
                    onClick = {
                        val s = parseMin(start); val e = parseMin(end)
                        if (subject.isNotBlank() && s != null && e != null && e > s) {
                            scope.launch {
                                db.lessonDao().insert(Lesson(
                                    subject = subject.trim(), teacher = teacher.trim(),
                                    room = room.trim(), dayOfWeek = day, startMin = s, endMin = e))
                                BellScheduler.rescheduleAll(ctx, db.lessonDao())
                                subject = ""; teacher = ""; room = ""
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Hozzáadás") }
                Spacer(Modifier.height(8.dp))
                Text("Felvitt órák", fontWeight = FontWeight.Medium)
            }
            items(lessons) { l ->
                Card {
                    Row(Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${DAYS[l.dayOfWeek - 1]} · ${l.subject}",
                                fontWeight = FontWeight.Medium)
                            Text("${fmt(l.startMin)}–${fmt(l.endMin)}" +
                                (if (l.room.isNotBlank()) " · ${l.room}" else ""))
                        }
                        TextButton(onClick = {
                            scope.launch {
                                db.lessonDao().delete(l)
                                BellScheduler.rescheduleAll(ctx, db.lessonDao())
                            }
                        }) { Text("Törlés") }
                    }
                }
            }
        }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved by Settings.flow(ctx).collectAsState(initial = AppSettings())
    var s by remember(saved) { mutableStateOf(saved) }

    var expProc by remember { mutableStateOf(true) }
    var expPerf by remember { mutableStateOf(false) }
    var expSchool by remember { mutableStateOf(false) }
    var expOnline by remember { mutableStateOf(false) }
    var geminiListBusy by remember { mutableStateOf(false) }
    var geminiListMsg by remember { mutableStateOf<String?>(null) }
    var expEmail by remember { mutableStateOf(false) }
    var expModels by remember { mutableStateOf(false) }

    // --- Offline modellek importálása fájlválasztóval (adb nem szükséges) ---
    var modelStatus by remember { mutableStateOf(ModelImporter.status(ctx)) }
    var importing by remember { mutableStateOf<String?>(null) }
    var modelMessage by remember { mutableStateOf<String?>(null) }
    var modelError by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    val gemmaPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            importing = "Gemma importálása és próbabetöltése…"
            modelMessage = null; modelError = null
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                ModelImporter.runCatching { importGemma(ctx, it) }
                    .onSuccess { r ->
                        modelMessage = "✅ Gemma betöltve: ${r.originalName}, " +
                            "%.0f MB, betöltés %.1f mp, gyorsító: ".format(r.sizeBytes / 1_000_000.0, r.loadSeconds) + r.backend
                    }
                    .onFailure { e -> modelError = e.message ?: "Ismeretlen hiba az importálásnál." }
                modelStatus = ModelImporter.status(ctx)
                importing = null
                (modelError ?: modelMessage)?.let { msg -> snackbarHostState.showSnackbar(msg) }
            }
        }
    }
    val whisperPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            importing = "Whisper modell másolása… (nagy fájl, eltarthat)"
            modelMessage = null; modelError = null
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                ModelImporter.runCatching { importWhisper(ctx, it) }
                    .onSuccess { modelMessage = "✅ Whisper modell telepítve." }
                    .onFailure { e -> modelError = e.message ?: "Ismeretlen hiba az importálásnál." }
                modelStatus = ModelImporter.status(ctx)
                importing = null
                (modelError ?: modelMessage)?.let { msg -> snackbarHostState.showSnackbar(msg) }
            }
        }
    }

    var confirmResetAll by remember { mutableStateOf(false) }

    if (confirmResetAll) {
        AlertDialog(
            onDismissRequest = { confirmResetAll = false },
            title = { Text("Adatbázis Törlés & Gyári Reset") },
            text = { Text("Biztosan törölni szeretnéd az ÖSSZES eddigi jegyzetet, döntést és feladatot? A sorszámozás újra a 001-ről fog indulni.") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            Db.resetAllData(ctx)
                        }
                        confirmResetAll = false
                    }
                ) { Text("Igen, Törlés & Reset") }
            },
            dismissButton = {
                TextButton(onClick = { confirmResetAll = false }) { Text("Mégse") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Beállítások") }, navigationIcon = { BackButton(onBack) })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 640.dp).fillMaxWidth().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (s.secretsLost) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Text(
                            "⚠️ A tárolt API-kulcs nem olvasható, kérlek add meg újra.",
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
            item {
                SettingSection("Feldolgozás", expProc, { expProc = !expProc }) {
                    Text("Alap elemző", fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !s.preferGemini, onClick = { s = s.copy(preferGemini = false) },
                            label = { Text("Gemma 4 (helyben)") })
                        FilterChip(selected = s.preferGemini, onClick = { s = s.copy(preferGemini = true) },
                            label = { Text("Gemini (felhő)") })
                    }
                    Text(
                        if (s.preferGemini) "A hangot a Gemini írja át és elemzi. Hálózat nélkül automatikusan " +
                            "helyben készül (Whisper + Gemma 4)."
                        else "Minden a telefonon készül (Whisper + Gemma 4). A Gemini csak kérésre fut, " +
                            "pl. „Újrafogalmazás Geminivel”.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp
                    )
                    if (s.preferGemini) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = s.wifiOnly, onCheckedChange = { s = s.copy(wifiOnly = it) })
                            Spacer(Modifier.width(8.dp))
                            Text("Gemini csak Wi-Fi-n (mobilneten helyben készül)")
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = s.privacyLock, onCheckedChange = { s = s.copy(privacyLock = it) })
                        Spacer(Modifier.width(8.dp))
                        Text("Adatvédelmi zár — a hang és a szöveg soha nem hagyja el a készüléket (kérésre sem fut Gemini)")
                    }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Text("Leirat (Whisper, helyben)", fontWeight = FontWeight.Medium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = s.whisperAccurate, onCheckedChange = { s = s.copy(whisperAccurate = it) })
                        Spacer(Modifier.width(8.dp))
                        Text("Pontosabb átírás (5 változatot mérlegel; kb. 1,5–2× lassabb)")
                    }
                    OutlinedTextField(
                        value = s.whisperGlossary,
                        onValueChange = { s = s.copy(whisperGlossary = it) },
                        label = { Text("Szótár: nevek, szakszavak (vesszővel)") },
                        placeholder = { Text("pl. Kovács Anna, ISO 27001, kockázatelemzés") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )
                    Text("A felvétel témája automatikusan hozzáadódik. A Whisper ezeknek a szavaknak az írásmódját " +
                        "követi; a túl hosszú lista (${hu.orajegyzet.ai.WhisperPrompt.MAX_CHARS} karakter fölött) levágódik.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    // 1.1.3: egyértelmű kijelzés, mi fog ténylegesen futni a mostani beállításokkal
                    val effective = hu.orajegyzet.ai.EngineRouting.describe(
                        preferGemini = s.preferGemini,
                        privacyLock = s.privacyLock,
                        hasApiKey = s.geminiApiKey.isNotBlank(),
                        gemmaInstalled = modelStatus.gemmaInstalled
                    )
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Text("Jelenleg: $effective", modifier = Modifier.padding(10.dp), fontSize = 13.sp,
                            fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
            item {
                // 1.1.7: a többi szakaszhoz hasonlóan összecsukható; a fejléc mutatja az állapotot
                SettingSection("Iskolai mód: " + if (s.schoolMode) "be" else "ki", expSchool, { expSchool = !expSchool }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Iskolai mód (órarend, csengetés, widget)", modifier = Modifier.weight(1f))
                        Switch(checked = s.schoolMode, onCheckedChange = { s = s.copy(schoolMode = it) })
                    }
                    Text("Kikapcsolva az órarend és a csengetés rejtve van, az adatok megmaradnak.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
            item {
                SettingSection("Teljesítmény / haladó", expPerf, { expPerf = !expPerf }) {
                    IntFieldRow("Whisper szálszám (2–8)", s.whisperThreads, 2..8) { s = s.copy(whisperThreads = it) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = s.keepModelWarm, onCheckedChange = { s = s.copy(keepModelWarm = it) })
                        Spacer(Modifier.width(8.dp))
                        Text("Modell melegen tartása (ne töltse újra minden felvételnél)")
                    }
                    IntFieldRow("Melegen tartás (perc)", s.warmMinutes, 1..60) { s = s.copy(warmMinutes = it) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = s.stagedDisplay, onCheckedChange = { s = s.copy(stagedDisplay = it) })
                        Spacer(Modifier.width(8.dp))
                        Text("Szakaszos megjelenítés (előbb a leirat, utána az összefoglaló)")
                    }
                    IntFieldRow("Felvétel-darab (mp)", s.whisperChunkSec, 10..120) { s = s.copy(whisperChunkSec = it) }
                    IntFieldRow("Darab-átfedés (mp)", s.whisperOverlapSec, 0..10) { s = s.copy(whisperOverlapSec = it) }
                    IntFieldRow("Összefoglaló-blokk (karakter)", s.summaryBlockChars, 1500..6000) { s = s.copy(summaryBlockChars = it) }
                    IntFieldRow("Hang megtartása (perc)", s.audioRetentionMin, 1..1440) { s = s.copy(audioRetentionMin = it) }
                    Text("A darabolás a hosszú felvételeknél számít; a default a mostani működés. " +
                        "Több szál gyorsabb lehet, de melegedésnél kevesebb (4–5) stabilabb.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
            item {
                SettingSection("Online (Gemini)", expOnline, { expOnline = !expOnline }) {
                    if (s.privacyLock || !s.preferGemini) {
                        Text(
                            if (s.privacyLock) "⚠️ Az adatvédelmi zár be van kapcsolva, ezért a Gemini nem fut (minden helyben készül). A Gemini használatához kapcsold ki a zárat a Feldolgozás kártyán."
                            else "ℹ️ Az alap elemző a Gemma 4, ezért a Gemini csak kérésre fut (pl. „Újrafogalmazás Geminivel”), vagy ha nincs telepített Gemma-modell. Átállítás: Feldolgozás → Alap elemző.",
                            color = if (s.privacyLock) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp, fontWeight = FontWeight.Medium
                        )
                    }
                    OutlinedTextField(
                        value = s.geminiApiKey,
                        onValueChange = { s = s.copy(geminiApiKey = it.trim()) },
                        label = { Text("Gemini API-kulcs") },
                        trailingIcon = {
                            if (s.geminiApiKey.isNotEmpty()) {
                                IconButton(onClick = { s = s.copy(geminiApiKey = "") }) {
                                    Text("✕", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://aistudio.google.com/apikey")
                                )
                                ctx.startActivity(intent)
                            },
                            modifier = Modifier.weight(1.2f)
                        ) {
                            Text("🔑 Generálás", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
                                if (!clip.isNullOrBlank()) {
                                    s = s.copy(geminiApiKey = clip)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("📋 Beillesztés", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = { s = s.copy(geminiApiKey = "") },
                            modifier = Modifier.weight(0.8f)
                        ) {
                            Text("🗑️ Törlés", fontSize = 12.sp)
                        }
                    }
                    Text(
                        "Az API-kulcs ingyenes. Kattints a gombra, jelentkezz be a Google-fiókodba, kattints a 'Create API key' gombra, másold ki, majd nyomj a 'Beillesztés' gombra.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                    if (s.geminiApiKey.isNotBlank() && s.geminiApiKey.length < 10) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "⚠️ A beírt API-kulcs gyanúsan rövid. Másold ki az aistudio.google.com felületén generált teljes kulcsot!",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    // 1.1.6: a kulccsal ténylegesen elérhető modellek (lekérés), különben a beépített lista
                    val pickerModels = hu.orajegyzet.ai.GeminiModels.forPicker(
                        hu.orajegyzet.ai.GeminiModels.decode(s.geminiModelsCache), s.geminiModel)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Gemini modell (${pickerModels.size}):", fontSize = 12.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f))
                        TextButton(
                            enabled = s.geminiApiKey.isNotBlank() && !geminiListBusy,
                            onClick = {
                                geminiListBusy = true; geminiListMsg = null
                                val key = s.geminiApiKey
                                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    runCatching { GeminiPipeline(key).listModels() }
                                        .onSuccess { ids ->
                                            if (ids.isEmpty()) {
                                                geminiListMsg = "❌ A kulccsal nem érhető el jegyzetkészítésre alkalmas modell."
                                            } else {
                                                val enc = hu.orajegyzet.ai.GeminiModels.encode(ids)
                                                Settings.setGeminiModelsCache(ctx, enc)
                                                s = s.copy(geminiModelsCache = enc)
                                                geminiListMsg = "✅ ${ids.size} modell elérhető a kulcsoddal."
                                            }
                                        }
                                        .onFailure { e -> geminiListMsg = "❌ ${e.message ?: "A lekérés nem sikerült."}" }
                                    geminiListBusy = false
                                }
                            }
                        ) { Text(if (geminiListBusy) "Lekérés…" else "🔄 Frissítés", fontSize = 12.sp) }
                    }
                    geminiListMsg?.let { Text(it, fontSize = 12.sp) }
                    if (s.geminiModelsCache.isBlank()) {
                        Text("Beépített lista látszik. A „Frissítés” a kulcsoddal lekéri az összes elérhető modellt.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    pickerModels.forEach { m ->
                        Row(
                            Modifier.fillMaxWidth().clickable { s = s.copy(geminiModel = m) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = s.geminiModel == m, onClick = { s = s.copy(geminiModel = m) })
                            Column {
                                Text(m, fontSize = 14.sp)
                                hu.orajegyzet.ai.GeminiModels.hint(m).takeIf { it.isNotBlank() }?.let {
                                    Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
            item {
                SettingSection("Napi e-mail kivonat", expEmail, { expEmail = !expEmail }) {
                    OutlinedTextField(s.email, { s = s.copy(email = it) },
                        label = { Text("Címzett email") }, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(s.smtpHost, { s = s.copy(smtpHost = it) },
                            label = { Text("SMTP host") }, modifier = Modifier.weight(2f))
                        OutlinedTextField(s.smtpPort.toString(),
                            { v -> v.toIntOrNull()?.let { s = s.copy(smtpPort = it) } },
                            label = { Text("Port") }, modifier = Modifier.weight(1f))
                    }
                    OutlinedTextField(s.smtpUser, { s = s.copy(smtpUser = it) },
                        label = { Text("SMTP felhasználó (email)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(s.smtpPass, { s = s.copy(smtpPass = it) },
                        label = { Text("SMTP jelszó (Gmail: alkalmazásjelszó)") },
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(s.digestHour.toString(),
                        { v -> v.toIntOrNull()?.takeIf { it in 0..23 }?.let { s = s.copy(digestHour = it) } },
                        label = { Text("Kivonat kiküldése (óra, 0–23)") }, modifier = Modifier.fillMaxWidth())
                }
            }
            item {
                SettingSection("Modellek", expModels, { expModels = !expModels }) {
                    // 1.1.4: a betöltött modellek pontos azonosítása
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Betöltött modellek", fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer)
                            Text(
                                "🎙 Leirat: " + if (modelStatus.whisperInstalled)
                                    "Whisper ${modelStatus.whisperInfo?.label ?: "(ismeretlen típus)"} – " +
                                        hu.orajegyzet.ai.ModelInfo.mb(modelStatus.whisperSizeBytes) +
                                        (s.whisperModelName.takeIf { it.isNotBlank() }?.let { "\n     $it" } ?: "")
                                else "nincs telepítve",
                                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                "🧠 Elemzés: " + when {
                                    modelStatus.gemmaInstalled ->
                                        hu.orajegyzet.ai.ModelInfo.gemmaVariant(s.gemmaModelName, modelStatus.gemmaSizeBytes) +
                                            " – " + hu.orajegyzet.ai.ModelInfo.mb(modelStatus.gemmaSizeBytes) +
                                            (s.gemmaModelName.takeIf { it.isNotBlank() }?.let { "\n     $it" } ?: "")
                                    modelStatus.gemmaInvalidSizeBytes > 0 -> "HIBÁS FÁJL (nem futtatható)"
                                    else -> "nincs telepítve"
                                },
                                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                    if (modelStatus.whisperInfo?.englishOnly == true) {
                        Text("❌ A telepített Whisper csak angol nyelvű (.en) modell – magyarul nem működik. " +
                            "Tölts le egy többnyelvűt (lent).", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                    }
                    if (modelStatus.gemmaInvalidSizeBytes > 0) {
                        Text("❌ A telepített Gemma-fájl (%.0f MB) nem LiteRT-LM (.litertlm) modell – valószínűleg egy régi MediaPipe .task, vagy sérült/félbeszakadt letöltés. Töröld, és importáld a gemma-4-E2B-it.litertlm fájlt."
                            .format(modelStatus.gemmaInvalidSizeBytes / 1_000_000.0),
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = {
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                ModelImporter.deleteInvalidGemma(ctx)
                                modelStatus = ModelImporter.status(ctx)
                                modelMessage = "A hibás Gemma-fájl törölve."; modelError = null
                            }
                        }, enabled = importing == null, modifier = Modifier.fillMaxWidth()) {
                            Text("Hibás Gemma-fájl törlése")
                        }
                    }
                    if (modelStatus.gemmaInstalled) {
                        OutlinedButton(onClick = {
                            importing = "Gemma teszt: betöltés és válasz…"
                            modelMessage = null; modelError = null
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                ModelImporter.runCatching { testGemma(ctx) }
                                    .onSuccess { r ->
                                        modelMessage = "✅ Gemma működik – ${r.backend} (betöltés %.1f mp, válasz %.1f mp): "
                                            .format(r.loadSeconds, r.answerSeconds) + r.answer
                                    }
                                    .onFailure { e -> modelError = e.message ?: "Ismeretlen hiba a teszt közben." }
                                importing = null
                            }
                        }, enabled = importing == null, modifier = Modifier.fillMaxWidth()) {
                            Text("Gemma modell teszt")
                        }
                    }
                    modelMessage?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary) }
                    modelError?.let { Text("❌ $it", fontSize = 13.sp, color = MaterialTheme.colorScheme.error) }
                    importing?.let {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp)); Text(it)
                        }
                    }
                    Button(onClick = { whisperPicker.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth(), enabled = importing == null) {
                        Text("Whisper modell (.bin) importálása")
                    }
                    OutlinedButton(onClick = { gemmaPicker.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth(), enabled = importing == null) {
                        Text("Gemma modell (.litertlm) importálása")
                    }
                    Text("Letöltés (a telefon böngészője a Letöltések mappába menti, utána a fenti gombbal importáld):",
                        fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    hu.orajegyzet.ai.ModelInfo.DOWNLOADS.forEach { d ->
                        OutlinedButton(onClick = {
                            runCatching {
                                ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(d.url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                            }.onFailure { modelError = "Nem nyílt meg a böngésző: ${it.message}" }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("⬇ ${d.title} (${d.note})", fontSize = 13.sp)
                        }
                    }
                    Text("Importáláskor kb. kétszer annyi szabad hely kell, mint a fájl mérete; az import után a " +
                        "Letöltések mappából törölhető. A Gemma letöltésénél a Hugging Face kérheti a licenc elfogadását.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
            item {
                Button(onClick = {
                    scope.launch {
                        try {
                            Settings.save(ctx, s)
                            DigestWorker.schedule(ctx)
                            // az App saját scope-jában, hogy a képernyő bezárása ne szakítsa meg
                            (ctx.applicationContext as hu.orajegyzet.App).appScope.launch {
                                runCatching { hu.orajegyzet.bell.SchoolMode.apply(ctx.applicationContext) }
                            }
                            onBack()
                        } catch (e: SecretStorageException) {
                            snackbarHostState.showSnackbar("Az API-kulcsot nem sikerült biztonságosan elmenteni. Próbáld újra, vagy indítsd újra a telefont.")
                        }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Mentés") }
            }
            item {
                OutlinedButton(
                    onClick = { confirmResetAll = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("💣 Összes adat törlése / Reset (001-ről indul)")
                }
            }
        }
        }
    }
}

@Composable
private fun SettingSection(
    title: String, expanded: Boolean, onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Card {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().clickable { onToggle() }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text(if (expanded) "▾" else "▸")
            }
            if (expanded) Column(
                Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp), content = content
            )
        }
    }
}

@Composable
private fun IntFieldRow(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            text = v
            v.toIntOrNull()?.let { onChange(it.coerceIn(range.first, range.last)) }
        },
        label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true
    )
}
