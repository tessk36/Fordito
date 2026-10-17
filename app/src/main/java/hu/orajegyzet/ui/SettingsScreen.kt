package hu.orajegyzet.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.orajegyzet.ai.GeminiPipeline
import hu.orajegyzet.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved by Settings.flow(ctx).collectAsState(initial = AppSettings())
    var s by remember(saved) { mutableStateOf(saved) }

    var expProc by remember { mutableStateOf(true) }
    var expPerf by remember { mutableStateOf(false) }
    var expOnline by remember { mutableStateOf(false) }
    var geminiListBusy by remember { mutableStateOf(false) }
    var geminiListMsg by remember { mutableStateOf<String?>(null) }
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
            title = { Text("Előzmények törlése") },
            text = { Text("Biztosan törölni szeretnéd az összes eddigi fordítást és a hozzájuk tartozó hangfelvételeket?") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            TrDb.get(ctx).dao().deleteAll()
                            ctx.filesDir.listFiles()?.filter { it.name.startsWith("rec_") || it.name.startsWith("imp_") }
                                ?.forEach { it.delete() }
                        }
                        confirmResetAll = false
                    }
                ) { Text("Igen, törlés") }
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
                        if (s.preferGemini) "A fordítást a Gemini készíti (hangnál az átiratot is). Hálózat nélkül " +
                            "automatikusan helyben készül (Whisper + Gemma 4)."
                        else "Minden a telefonon készül (Whisper + Gemma 4). Ritkább nyelveknél a Gemini " +
                            "pontosabb lehet.",
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
                    Text("A Whisper ezeknek a szavaknak az írásmódját követi; a nyelvet automatikusan felismeri. " +
                        "A túl hosszú lista (${hu.orajegyzet.ai.WhisperPrompt.MAX_CHARS} karakter fölött) levágódik.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    // 1.1.3: egyértelmű kijelzés, mi fog ténylegesen futni a mostani beállításokkal
                    val effective = hu.orajegyzet.ai.EngineRouting.describe(
                        preferGemini = s.preferGemini,
                        privacyLock = s.privacyLock,
                        hasApiKey = s.geminiApiKey.isNotBlank(),
                        gemmaInstalled = modelStatus.gemmaInstalled
                    ).replace("Leirat és elemzés", "Átirat és fordítás").replace("kivonat", "nincs fordítómotor")
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Text("Jelenleg: $effective", modifier = Modifier.padding(10.dp), fontSize = 13.sp,
                            fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
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
                    IntFieldRow("Felvétel-darab (mp)", s.whisperChunkSec, 10..120) { s = s.copy(whisperChunkSec = it) }
                    IntFieldRow("Darab-átfedés (mp)", s.whisperOverlapSec, 0..10) { s = s.copy(whisperOverlapSec = it) }
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
                            else "ℹ️ Az alap elemző a Gemma 4, ezért a Gemini csak akkor fut, ha nincs telepített Gemma-modell. Átállítás: Feldolgozás → Alap elemző.",
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
                                                geminiListMsg = "❌ A kulccsal nem érhető el fordításra alkalmas modell."
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
                    OutlinedTextField(
                        value = s.liveModel,
                        onValueChange = { s = s.copy(liveModel = it.trim()) },
                        label = { Text("Élő tolmács modell") },
                        supportingText = { Text("Alapértelmezés: gemini-3.5-live-translate-preview (WebSocketes élő modell, a lenti listában nem jelenik meg)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
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
                        Text("❌ A telepített Whisper csak angol nyelvű (.en) modell – a fordítóhoz többnyelvű kell " +
                            "(automatikus nyelvfelismerés). Tölts le egy többnyelvűt (lent).", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
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
                    Text("🗑️ Összes fordítás törlése")
                }
            }
            item {
                Text("Fordító ${hu.orajegyzet.BuildConfig.VERSION_NAME}", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
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
