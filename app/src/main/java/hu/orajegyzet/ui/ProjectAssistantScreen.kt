package hu.orajegyzet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.orajegyzet.ai.ModeSelector
import hu.orajegyzet.ai.ReportBuilder
import hu.orajegyzet.ai.SummaryEngine
import hu.orajegyzet.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate

enum class Granularity { EXECUTIVE, COORDINATOR, OPERATIONAL }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectAssistantScreen(onBack: () -> Unit, onOpenNote: (Long) -> Unit) {
    val ctx = LocalContext.current
    val db = remember { Db.get(ctx) }
    val scope = rememberCoroutineScope()

    val projects by db.projectDao().all().collectAsState(initial = emptyList())
    var selectedProjectId by remember { mutableStateOf("prj-01") }

    val activeProject = projects.firstOrNull { it.id == selectedProjectId } ?: projects.firstOrNull()

    val events by db.projectEventDao().byProject(activeProject?.id ?: "prj-01")
        .collectAsState(initial = emptyList())
    val tasks by db.ganttTaskDao().byProject(activeProject?.id ?: "prj-01")
        .collectAsState(initial = emptyList())
    val meetings by db.noteDao().byProject(activeProject?.id ?: "prj-01")
        .collectAsState(initial = emptyList())
    val savedReports by db.aiReportDao().byProject(activeProject?.id ?: "prj-01")
        .collectAsState(initial = emptyList())

    var granularity by remember { mutableStateOf(Granularity.EXECUTIVE) }
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Döntési napló, 1 = Gantt / WBS, 2 = Megbeszélések

    var showNewProjectDialog by remember { mutableStateOf(false) }
    var showEditProjectDialog by remember { mutableStateOf(false) }
    var showDeleteProjectConfirm by remember { mutableStateOf(false) }
    var showNewEventDialog by remember { mutableStateOf(false) }
    var showNewTaskDialog by remember { mutableStateOf(false) }
    var editingEvent by remember { mutableStateOf<ProjectEventEntity?>(null) }
    var editingTask by remember { mutableStateOf<GanttTaskEntity?>(null) }

    var aiReportTitle by remember { mutableStateOf<String?>(null) }
    var aiReportText by remember { mutableStateOf<String?>(null) }
    var aiReportEngine by remember { mutableStateOf<String?>(null) }
    var aiReportWarning by remember { mutableStateOf<String?>(null) }
    var aiReportBusy by remember { mutableStateOf(false) }
    var canRephraseWithGemini by remember { mutableStateOf(false) }
    /** 1.1.5: a megjelenített jelentés újrafogalmazása (projektjelentés vagy havi). */
    var rephraseAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showMonthPicker by remember { mutableStateOf(false) }
    var reportToDelete by remember { mutableStateOf<hu.orajegyzet.data.AiReport?>(null) }

    // --- AI Jelentés Generáló (1.0.4: tényblokk + Gemini / Gemma / offline vázlat) ---
    fun runAiAnalysis(type: String, forceGemini: Boolean = false) {
        val prj = activeProject ?: return
        aiReportTitle = type
        aiReportEngine = null
        aiReportWarning = null
        aiReportBusy = true
        aiReportText = if (forceGemini) "Újrafogalmazás Geminivel…" else "Jelentés készítése…"
        val evs = events
        val tks = tasks

        scope.launch(Dispatchers.IO) {
            try {
                val s = Settings.get(ctx)
                val r = ReportBuilder.generate(ctx, s, type, prj, evs, tks, forceGemini = forceGemini)
                aiReportText = r.text
                aiReportEngine = r.engine
                aiReportWarning = r.warning
                canRephraseWithGemini = r.engine != SummaryEngine.GEMINI && !s.privacyLock &&
                    s.geminiApiKey.isNotBlank() && ModeSelector.hasNetwork(ctx, s.wifiOnly)
                rephraseAction = { runAiAnalysis(type, forceGemini = true) }
                // 1.1.5: minden elkészült jelentés mentődik (Jelentések fül, havi összefoglaló forrása)
                hu.orajegyzet.ai.ReportArchive.save(ctx, type, "$type – ${prj.code}", r.text, r.engine, r.warning,
                    projectId = prj.id)
            } catch (e: Exception) {
                aiReportText = "Hiba történt a jelentés készítése során: ${e.message}"
            } finally {
                aiReportBusy = false
            }
        }
    }

    // --- 1.1.5: Havi összefoglaló (a hónap megbeszéléseiből és mentett jelentéseiből) ---
    fun runMonthly(month: java.time.YearMonth, forceGemini: Boolean = false) {
        val prj = activeProject ?: return
        aiReportTitle = "${ReportBuilder.MONTHLY} – $month"
        aiReportEngine = null
        aiReportWarning = null
        aiReportBusy = true
        aiReportText = if (forceGemini) "Újrafogalmazás Geminivel…" else "Havi összefoglaló készítése ($month)…"
        scope.launch(Dispatchers.IO) {
            try {
                val s = Settings.get(ctx)
                val (r, _) = hu.orajegyzet.ai.ReportArchive.createMonthly(ctx, s, prj, month, forceGemini)
                aiReportText = r.text
                aiReportEngine = r.engine
                aiReportWarning = r.warning
                canRephraseWithGemini = r.engine != SummaryEngine.GEMINI && !s.privacyLock &&
                    s.geminiApiKey.isNotBlank() && ModeSelector.hasNetwork(ctx, s.wifiOnly)
                rephraseAction = { runMonthly(month, forceGemini = true) }
            } catch (e: Exception) {
                aiReportText = "Hiba történt a havi összefoglaló készítése során: ${e.message}"
            } finally {
                aiReportBusy = false
            }
        }
    }

    fun openSaved(r: hu.orajegyzet.data.AiReport) {
        aiReportTitle = r.title
        aiReportText = r.text
        aiReportEngine = r.engine
        aiReportWarning = r.warning
        aiReportBusy = false
        canRephraseWithGemini = false
        rephraseAction = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Projekt Asszisztens", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    TextButton(onClick = { showNewProjectDialog = true }) {
                        Text("+ Új Projekt", maxLines = 1)
                    }
                    if (activeProject != null) {
                        TextButton(
                            onClick = { showDeleteProjectConfirm = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("🗑️", maxLines = 1)
                        }
                    }
                }
            )
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. Projekt Kiválasztó & Fejléc Kártya (Összecsukható részletekkel)
                item {
                    if (activeProject != null) {
                        var showProjectDetails by remember { mutableStateOf(false) }

                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "${activeProject.code} · ${activeProject.name}",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 17.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    RagStatusBadge(activeProject.ragStatus)
                                    Spacer(Modifier.width(6.dp))
                                    IconButton(
                                        onClick = { showEditProjectDialog = true },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Text("✏️", fontSize = 15.sp)
                                    }
                                }

                                Spacer(Modifier.height(4.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable { showProjectDetails = !showProjectDetails }
                                ) {
                                    Text(
                                        if (showProjectDetails) "▾ Részletek elrejtése" else "▸ Részletek kibontása (felelős, leírás, keret...)",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }

                                if (showProjectDetails) {
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "Felelős: ${activeProject.manager} · Céldátum: ${activeProject.targetDate}",
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (activeProject.description.isNotBlank()) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            activeProject.description,
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (activeProject.budget != null) {
                                        Spacer(Modifier.height(6.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                            Text("Költségkeret: ${activeProject.budget}", fontSize = 12.sp)
                                            Text("Felhasznált: ${activeProject.spent ?: "0 Ft"}", fontSize = 12.sp)
                                        }
                                    }
                                }

                                // Projekt választó vízszintes görgethető sor
                                if (projects.size > 1) {
                                    Spacer(Modifier.height(8.dp))
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        items(projects) { p ->
                                            FilterChip(
                                                selected = p.id == activeProject.id,
                                                onClick = { selectedProjectId = p.id },
                                                label = { Text(p.code, maxLines = 1) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. Granularitási Szűrő (Vezetői / Koordinátori / Operatív)
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Granularitás / Nézet", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item {
                                FilterChip(
                                    selected = granularity == Granularity.EXECUTIVE,
                                    onClick = { granularity = Granularity.EXECUTIVE },
                                    label = { Text("👑 Vezetői", maxLines = 1) }
                                )
                            }
                            item {
                                FilterChip(
                                    selected = granularity == Granularity.COORDINATOR,
                                    onClick = { granularity = Granularity.COORDINATOR },
                                    label = { Text("👥 Koordinátori", maxLines = 1) }
                                )
                            }
                            item {
                                FilterChip(
                                    selected = granularity == Granularity.OPERATIONAL,
                                    onClick = { granularity = Granularity.OPERATIONAL },
                                    label = { Text("💻 Operatív", maxLines = 1) }
                                )
                            }
                        }
                    }
                }

                // 3. AI Akciógombok (Vezetői Összefoglaló, Státuszjelentés, QA Audit)
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            OutlinedButton(onClick = { runAiAnalysis(ReportBuilder.EXECUTIVE) }) {
                                Text("✨ Vezetői Összefoglaló", fontSize = 12.sp, maxLines = 1)
                            }
                        }
                        item {
                            OutlinedButton(onClick = { runAiAnalysis(ReportBuilder.STATUS) }) {
                                Text("📊 Státuszjelentés", fontSize = 12.sp, maxLines = 1)
                            }
                        }
                        item {
                            OutlinedButton(onClick = { runAiAnalysis(ReportBuilder.QA) }) {
                                Text("🛡️ QA Audit", fontSize = 12.sp, maxLines = 1)
                            }
                        }
                        item {
                            OutlinedButton(onClick = { showMonthPicker = true }) {
                                Text("📅 Havi összefoglaló", fontSize = 12.sp, maxLines = 1)
                            }
                        }
                    }
                }

                // 4. Tabok: Döntési Napló (0) vs Gantt WBS (1) vs Megbeszélések (2)
                item {
                    ScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 0.dp) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("Döntések (${events.size})", maxLines = 1) }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("Gantt WBS (${tasks.size})", maxLines = 1) }
                        )
                        Tab(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            text = { Text("Megbeszélések (${meetings.size})", maxLines = 1) }
                        )
                        Tab(
                            selected = selectedTab == 3,
                            onClick = { selectedTab = 3 },
                            text = { Text("Jelentések (${savedReports.size})", maxLines = 1) }
                        )
                    }
                }

                // Tab 0: Döntési Napló
                if (selectedTab == 0) {
                    item {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Döntések & Kockázatok", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            TextButton(onClick = { showNewEventDialog = true }) {
                                Text("+ Új Döntés", maxLines = 1)
                            }
                        }
                    }

                    val filteredEvents = when (granularity) {
                        Granularity.EXECUTIVE -> events.filter { it.impact == "critical" || it.impact == "high" || it.type == "milestone" }
                        Granularity.COORDINATOR -> events.filter { it.type == "decision" || it.type == "risk" || it.type == "milestone" }
                        Granularity.OPERATIONAL -> events
                    }

                    if (filteredEvents.isEmpty()) {
                        item {
                            Text(
                                "Nincs rögzített döntés vagy kockázat ebben a nézetben.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp
                            )
                        }
                    }

                    items(filteredEvents) { e ->
                        Card {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        when (e.type) {
                                            "decision" -> "⚖️ Döntés"
                                            "milestone" -> "🚩 Mérföldkő"
                                            "risk" -> "⚠️ Kockázat"
                                            else -> "📌 Akció"
                                        },
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.weight(1f))
                                    ImpactBadge(e.impact)
                                    Spacer(Modifier.width(4.dp))
                                    IconButton(
                                        onClick = { editingEvent = e },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Text("✏️", fontSize = 15.sp)
                                    }
                                }
                                Text(e.title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                if (e.description.isNotBlank()) {
                                    Text(
                                        e.description,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (!e.owner.isNullOrBlank()) {
                                    Text("Felelős: ${e.owner} · Dátum: ${e.date}", fontSize = 12.sp)
                                }
                                if (!e.decisionRationale.isNullOrBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text("Indoklás: ${e.decisionRationale}", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                }
                                if (!e.mitigation.isNullOrBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text("Kockázatenyhítés: ${e.mitigation}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }

                // Tab 1: Gantt WBS Feladatok
                if (selectedTab == 1) {
                    item {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Gantt Feladathálózat (WBS)", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            TextButton(onClick = { showNewTaskDialog = true }) {
                                Text("+ Új Feladat", maxLines = 1)
                            }
                        }
                    }

                    val filteredTasks = when (granularity) {
                        Granularity.EXECUTIVE -> tasks.filter { it.isMilestone || it.priority == "critical" || it.priority == "high" }
                        Granularity.COORDINATOR -> tasks.filter { it.status == "in_progress" || it.status == "blocked" || it.priority == "high" || it.priority == "critical" }
                        Granularity.OPERATIONAL -> tasks
                    }

                    if (filteredTasks.isEmpty()) {
                        item {
                            Text(
                                "Nincs megjeleníthető feladat ebben a nézetben.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp
                            )
                        }
                    }

                    items(filteredTasks) { t ->
                        Card {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        if (t.isMilestone) "🚩 MÉRFFÖLDKŐ" else t.title,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    PriorityBadge(t.priority)
                                    Spacer(Modifier.width(4.dp))
                                    IconButton(
                                        onClick = { editingTask = t },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Text("✏️", fontSize = 15.sp)
                                    }
                                }
                                if (t.isMilestone) {
                                    Text(t.title, fontSize = 14.sp)
                                }
                                Spacer(Modifier.height(4.dp))
                                LinearProgressIndicator(
                                    progress = { t.progress / 100f },
                                    modifier = Modifier.fillMaxWidth().height(6.dp)
                                )
                                Spacer(Modifier.height(4.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Haladás: ${t.progress}% · Felelős: ${t.assignee}", fontSize = 12.sp)
                                    Text("${t.startDate} ➔ ${t.dueDate}", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                // Tab 3: Mentett jelentések és összefoglalók (1.1.5)
                if (selectedTab == 3) {
                    item {
                        Text("Mentett AI-jelentések és összefoglalók", fontWeight = FontWeight.Bold)
                    }
                    if (savedReports.isEmpty()) {
                        item {
                            Text(
                                "Még nincs mentett jelentés. Az AI-jelentések (Vezetői, Státusz, QA, Havi) és a jegyzetek " +
                                    "típusos összefoglalói elkészültükkor automatikusan ide kerülnek.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp
                            )
                        }
                    }
                    items(savedReports, key = { it.id }) { r ->
                        Card(onClick = { openSaved(r) }) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(r.title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text(
                                        java.time.Instant.ofEpochMilli(r.createdAt).atZone(java.time.ZoneId.systemDefault())
                                            .toLocalDateTime().toString().take(16).replace('T', ' ') +
                                            (r.engine?.let { SummaryEngine.label(it) }?.let { " · $it" } ?: ""),
                                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        r.text.replace(Regex("[*#|_]"), "").replace(Regex("\\s+"), " ").take(110) + "…",
                                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2
                                    )
                                }
                                IconButton(onClick = { reportToDelete = r }) { Text("🗑", fontSize = 16.sp) }
                            }
                        }
                    }
                }

                // Tab 2: Megbeszélések
                if (selectedTab == 2) {
                    item {
                        Text("Projekt Megbeszélések & Átiratok", fontWeight = FontWeight.Bold)
                    }

                    if (meetings.isEmpty()) {
                        item {
                            Text(
                                "Még nincs ehhez a projekthez csatolt megbeszélés. A felvételeket a főképernyőn indíthatod el.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp
                            )
                        }
                    }

                    items(meetings) { m ->
                        Card(onClick = { onOpenNote(m.id) }) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Text("${m.subject} · ${m.dateIso}", fontWeight = FontWeight.Bold)
                                Text(
                                    m.summary.take(120) + if (m.summary.length > 120) "…" else "",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // --- AI Jelentés Kijelző Dialog ---
    if (aiReportText != null) {
        AlertDialog(
            onDismissRequest = { if (!aiReportBusy) { aiReportText = null; aiReportTitle = null } },
            title = { Text(aiReportTitle ?: "AI Elemzés") },
            text = {
                Box(Modifier.heightIn(max = 420.dp)) {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (aiReportBusy) item { ProcessingIndicator(label = aiReportText) }
                        else {
                            if (aiReportEngine != null) item { EngineBadge(aiReportEngine) }
                            aiReportWarning?.let { w ->
                                item {
                                    Text("⚠️ $w", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            item { MarkdownText(aiReportText!!) }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !aiReportBusy, onClick = { aiReportText = null; aiReportTitle = null }) {
                    Text("Bezárás")
                }
            },
            dismissButton = {
                val again = rephraseAction
                if (!aiReportBusy && canRephraseWithGemini && again != null) {
                    TextButton(onClick = { again() }) {
                        Text("Újrafogalmazás Geminivel")
                    }
                }
            }
        )
    }

    // --- 1.1.5: Hónap választása a havi összefoglalóhoz ---
    if (showMonthPicker) {
        AlertDialog(
            onDismissRequest = { showMonthPicker = false },
            title = { Text("Havi összefoglaló") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("A hónap megbeszéléseiből, mentett jelentéseiből, döntéseiből és feladataiból készül, " +
                        "és a Jelentések fülre mentődik.", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    hu.orajegyzet.ai.ReportArchive.recentMonths().forEach { m ->
                        OutlinedButton(onClick = { showMonthPicker = false; runMonthly(m) },
                            modifier = Modifier.fillMaxWidth()) {
                            Text(m.toString())
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showMonthPicker = false }) { Text("Mégse") } }
        )
    }

    reportToDelete?.let { r ->
        AlertDialog(
            onDismissRequest = { reportToDelete = null },
            title = { Text("Jelentés törlése") },
            text = { Text("Biztosan törlöd: „${r.title}”?") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) { db.aiReportDao().delete(r) }
                    reportToDelete = null
                }) { Text("Törlés", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { reportToDelete = null }) { Text("Mégse") } }
        )
    }

    // --- Projekt Szerkesztése Dialog ---
    if (showEditProjectDialog && activeProject != null) {
        var pCode by remember(activeProject) { mutableStateOf(activeProject.code) }
        var pName by remember(activeProject) { mutableStateOf(activeProject.name) }
        var pDesc by remember(activeProject) { mutableStateOf(activeProject.description) }
        var pManager by remember(activeProject) { mutableStateOf(activeProject.manager) }
        var pRag by remember(activeProject) { mutableStateOf(activeProject.ragStatus) }
        var pTarget by remember(activeProject) { mutableStateOf(activeProject.targetDate) }
        var pBudget by remember(activeProject) { mutableStateOf(activeProject.budget ?: "") }
        var pSpent by remember(activeProject) { mutableStateOf(activeProject.spent ?: "") }

        AlertDialog(
            onDismissRequest = { showEditProjectDialog = false },
            title = { Text("Projekt Adatainak Szerkesztése") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = pCode, onValueChange = { pCode = it }, label = { Text("Projekt kód (pl. PRJ-01)") }, singleLine = true)
                    OutlinedTextField(value = pName, onValueChange = { pName = it }, label = { Text("Projekt neve") }, singleLine = true)
                    OutlinedTextField(value = pDesc, onValueChange = { pDesc = it }, label = { Text("Leírás") })
                    OutlinedTextField(value = pManager, onValueChange = { pManager = it }, label = { Text("Projektvezető / Felelős") }, singleLine = true)
                    Text("RAG Státusz:", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        item { FilterChip(selected = pRag == "green", onClick = { pRag = "green" }, label = { Text("🟢 Zöld") }) }
                        item { FilterChip(selected = pRag == "amber", onClick = { pRag = "amber" }, label = { Text("🟡 Sárga") }) }
                        item { FilterChip(selected = pRag == "red", onClick = { pRag = "red" }, label = { Text("🔴 Piros") }) }
                    }
                    OutlinedTextField(value = pTarget, onValueChange = { pTarget = it }, label = { Text("Céldátum (ÉÉÉÉ-HH-NN)") }, singleLine = true)
                    OutlinedTextField(value = pBudget, onValueChange = { pBudget = it }, label = { Text("Költségkeret") }, singleLine = true)
                    OutlinedTextField(value = pSpent, onValueChange = { pSpent = it }, label = { Text("Felhasznált keret") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(onClick = {
                    val updated = activeProject.copy(
                        code = pCode.trim(),
                        name = pName.trim(),
                        description = pDesc.trim(),
                        manager = pManager.trim(),
                        ragStatus = pRag,
                        targetDate = pTarget.trim(),
                        budget = pBudget.ifBlank { null },
                        spent = pSpent.ifBlank { null }
                    )
                    scope.launch(Dispatchers.IO) {
                        db.projectDao().update(updated)
                    }
                    showEditProjectDialog = false
                }) { Text("Mentés") }
            },
            dismissButton = {
                TextButton(onClick = { showEditProjectDialog = false }) { Text("Mégse") }
            }
        )
    }

    // --- Projekt Törlése Dialog ---
    if (showDeleteProjectConfirm && activeProject != null) {
        AlertDialog(
            onDismissRequest = { showDeleteProjectConfirm = false },
            title = { Text("Projekt Törlése") },
            text = { Text("Biztosan törölni szeretnéd a(z) '${activeProject.name}' (${activeProject.code}) projektet és a hozzá tartozó döntéseket?") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            db.projectEventDao().deleteByProject(activeProject.id)
                            db.ganttTaskDao().deleteByProject(activeProject.id)
                            db.aiReportDao().deleteByProject(activeProject.id)
                            db.projectDao().delete(activeProject)
                        }
                        showDeleteProjectConfirm = false
                    }
                ) { Text("Igen, Törlés") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteProjectConfirm = false }) { Text("Mégse") }
            }
        )
    }

    // --- Új Projekt Létrehozása Dialog ---
    if (showNewProjectDialog) {
        var pCode by remember { mutableStateOf("PRJ-02") }
        var pName by remember { mutableStateOf("") }
        var pDesc by remember { mutableStateOf("") }
        var pManager by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showNewProjectDialog = false },
            title = { Text("Új Projekt Létrehozása") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pCode,
                        onValueChange = { pCode = it },
                        label = { Text("Projekt kód (pl. PRJ-02)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = pName,
                        onValueChange = { pName = it },
                        label = { Text("Projekt neve") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = pDesc,
                        onValueChange = { pDesc = it },
                        label = { Text("Leírás") }
                    )
                    OutlinedTextField(
                        value = pManager,
                        onValueChange = { pManager = it },
                        label = { Text("Projektvezető") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (pName.isNotBlank()) {
                            val newPrj = ProjectEntity(
                                id = "prj-${System.currentTimeMillis()}",
                                code = pCode.ifBlank { "PRJ-00" },
                                name = pName.trim(),
                                description = pDesc.trim(),
                                manager = pManager.ifBlank { "Projektvezető" },
                                startDate = LocalDate.now().toString(),
                                targetDate = LocalDate.now().plusMonths(3).toString()
                            )
                            scope.launch {
                                db.projectDao().insert(newPrj)
                                selectedProjectId = newPrj.id
                            }
                            showNewProjectDialog = false
                        }
                    }
                ) { Text("Mentés") }
            },
            dismissButton = {
                TextButton(onClick = { showNewProjectDialog = false }) { Text("Mégse") }
            }
        )
    }

    // --- Új Döntés / Kockázat Dialog ---
    if (showNewEventDialog && activeProject != null) {
        var title by remember { mutableStateOf("") }
        var desc by remember { mutableStateOf("") }
        var type by remember { mutableStateOf("decision") }
        var impact by remember { mutableStateOf("high") }
        var owner by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showNewEventDialog = false },
            title = { Text("Új Döntés / Kockázat / Mérföldkő") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        item { FilterChip(selected = type == "decision", onClick = { type = "decision" }, label = { Text("Döntés", maxLines = 1) }) }
                        item { FilterChip(selected = type == "risk", onClick = { type = "risk" }, label = { Text("Kockázat", maxLines = 1) }) }
                        item { FilterChip(selected = type == "milestone", onClick = { type = "milestone" }, label = { Text("Mérföldkő", maxLines = 1) }) }
                    }
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Cím") }, singleLine = true)
                    OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Részletes leírás") })
                    OutlinedTextField(value = owner, onValueChange = { owner = it }, label = { Text("Felelős") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (title.isNotBlank()) {
                        val evt = ProjectEventEntity(
                            projectId = activeProject.id,
                            date = LocalDate.now().toString(),
                            type = type,
                            title = title.trim(),
                            description = desc.trim(),
                            impact = impact,
                            owner = owner.ifBlank { activeProject.manager }
                        )
                        scope.launch { db.projectEventDao().insert(evt) }
                        showNewEventDialog = false
                    }
                }) { Text("Hozzáadás") }
            },
            dismissButton = { TextButton(onClick = { showNewEventDialog = false }) { Text("Mégse") } }
        )
    }

    // --- Új Gantt WBS Feladat Dialog ---
    if (showNewTaskDialog && activeProject != null) {
        var title by remember { mutableStateOf("") }
        var assignee by remember { mutableStateOf("") }
        var priority by remember { mutableStateOf("medium") }

        AlertDialog(
            onDismissRequest = { showNewTaskDialog = false },
            title = { Text("Új Gantt WBS Feladat") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Feladat címe") }, singleLine = true)
                    OutlinedTextField(value = assignee, onValueChange = { assignee = it }, label = { Text("Felelős neve") }, singleLine = true)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        item { FilterChip(selected = priority == "low", onClick = { priority = "low" }, label = { Text("Alacsony", maxLines = 1) }) }
                        item { FilterChip(selected = priority == "medium", onClick = { priority = "medium" }, label = { Text("Közepes", maxLines = 1) }) }
                        item { FilterChip(selected = priority == "high", onClick = { priority = "high" }, label = { Text("Magas", maxLines = 1) }) }
                        item { FilterChip(selected = priority == "critical", onClick = { priority = "critical" }, label = { Text("Kritikus", maxLines = 1) }) }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (title.isNotBlank()) {
                        val t = GanttTaskEntity(
                            projectId = activeProject.id,
                            title = title.trim(),
                            startDate = LocalDate.now().toString(),
                            dueDate = LocalDate.now().plusDays(14).toString(),
                            assignee = assignee.ifBlank { activeProject.manager },
                            priority = priority
                        )
                        scope.launch { db.ganttTaskDao().insert(t) }
                        showNewTaskDialog = false
                    }
                }) { Text("Hozzáadás") }
            },
            dismissButton = { TextButton(onClick = { showNewTaskDialog = false }) { Text("Mégse") } }
        )
    }

    // --- Döntés / Kockázat Szerkesztő Dialog ---
    if (editingEvent != null) {
        var eType by remember { mutableStateOf(editingEvent!!.type) }
        var eTitle by remember { mutableStateOf(editingEvent!!.title) }
        var eDesc by remember { mutableStateOf(editingEvent!!.description) }
        var eOwner by remember { mutableStateOf(editingEvent!!.owner ?: "") }
        var eImpact by remember { mutableStateOf(editingEvent!!.impact) }
        var eRationale by remember { mutableStateOf(editingEvent!!.decisionRationale ?: "") }
        var eMitigation by remember { mutableStateOf(editingEvent!!.mitigation ?: "") }

        AlertDialog(
            onDismissRequest = { editingEvent = null },
            title = { Text("Döntés / Kockázat Szerkesztése ✏️") },
            text = {
                Box(Modifier.heightIn(max = 400.dp)) {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                item { FilterChip(selected = eType == "decision", onClick = { eType = "decision" }, label = { Text("Döntés", maxLines = 1) }) }
                                item { FilterChip(selected = eType == "risk", onClick = { eType = "risk" }, label = { Text("Kockázat", maxLines = 1) }) }
                                item { FilterChip(selected = eType == "milestone", onClick = { eType = "milestone" }, label = { Text("Mérföldkő", maxLines = 1) }) }
                                item { FilterChip(selected = eType == "action", onClick = { eType = "action" }, label = { Text("Akció", maxLines = 1) }) }
                            }
                        }
                        item {
                            OutlinedTextField(value = eTitle, onValueChange = { eTitle = it }, label = { Text("Cím") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                        item {
                            OutlinedTextField(value = eDesc, onValueChange = { eDesc = it }, label = { Text("Részletes leírás") }, modifier = Modifier.fillMaxWidth())
                        }
                        item {
                            OutlinedTextField(value = eOwner, onValueChange = { eOwner = it }, label = { Text("Felelős") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                        item {
                            Text("Hatás / Impakt:", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                item { FilterChip(selected = eImpact == "low", onClick = { eImpact = "low" }, label = { Text("Alacsony", maxLines = 1) }) }
                                item { FilterChip(selected = eImpact == "medium", onClick = { eImpact = "medium" }, label = { Text("Közepes", maxLines = 1) }) }
                                item { FilterChip(selected = eImpact == "high", onClick = { eImpact = "high" }, label = { Text("Magas", maxLines = 1) }) }
                                item { FilterChip(selected = eImpact == "critical", onClick = { eImpact = "critical" }, label = { Text("Kritikus", maxLines = 1) }) }
                            }
                        }
                        item {
                            OutlinedTextField(value = eRationale, onValueChange = { eRationale = it }, label = { Text("Indoklás") }, modifier = Modifier.fillMaxWidth())
                        }
                        item {
                            OutlinedTextField(value = eMitigation, onValueChange = { eMitigation = it }, label = { Text("Kockázatenyhítés") }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        if (eTitle.isNotBlank()) {
                            val updated = editingEvent!!.copy(
                                type = eType,
                                title = eTitle.trim(),
                                description = eDesc.trim(),
                                owner = eOwner.trim(),
                                impact = eImpact,
                                decisionRationale = eRationale.trim(),
                                mitigation = eMitigation.trim()
                            )
                            scope.launch { db.projectEventDao().insert(updated) }
                            editingEvent = null
                        }
                    }) { Text("Mentés") }
                    TextButton(
                        onClick = {
                            // 1.1.8: a törlendő elemet a korutin indítása ELŐTT rögzítjük – különben a
                            // „editingEvent = null” után a korutinban null lesz (NullPointerException)
                            val toDelete = editingEvent
                            editingEvent = null
                            if (toDelete != null) scope.launch { db.projectEventDao().delete(toDelete) }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("Törlés") }
                }
            },
            dismissButton = {
                TextButton(onClick = { editingEvent = null }) { Text("Mégse") }
            }
        )
    }

    // --- Gantt Feladat Szerkesztő Dialog ---
    if (editingTask != null) {
        var tTitle by remember { mutableStateOf(editingTask!!.title) }
        var tAssignee by remember { mutableStateOf(editingTask!!.assignee) }
        var tPriority by remember { mutableStateOf(editingTask!!.priority) }
        var tProgress by remember { mutableFloatStateOf(editingTask!!.progress.toFloat()) }

        AlertDialog(
            onDismissRequest = { editingTask = null },
            title = { Text("Gantt Feladat Szerkesztése ✏️") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = tTitle, onValueChange = { tTitle = it }, label = { Text("Feladat címe") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = tAssignee, onValueChange = { tAssignee = it }, label = { Text("Felelős neve") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Haladás: ${tProgress.toInt()}%", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Slider(
                        value = tProgress,
                        onValueChange = { tProgress = it },
                        valueRange = 0f..100f
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        item { FilterChip(selected = tPriority == "low", onClick = { tPriority = "low" }, label = { Text("Alacsony", maxLines = 1) }) }
                        item { FilterChip(selected = tPriority == "medium", onClick = { tPriority = "medium" }, label = { Text("Közepes", maxLines = 1) }) }
                        item { FilterChip(selected = tPriority == "high", onClick = { tPriority = "high" }, label = { Text("Magas", maxLines = 1) }) }
                        item { FilterChip(selected = tPriority == "critical", onClick = { tPriority = "critical" }, label = { Text("Kritikus", maxLines = 1) }) }
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        if (tTitle.isNotBlank()) {
                            val updated = editingTask!!.copy(
                                title = tTitle.trim(),
                                assignee = tAssignee.trim(),
                                priority = tPriority,
                                progress = tProgress.toInt()
                            )
                            scope.launch { db.ganttTaskDao().insert(updated) }
                            editingTask = null
                        }
                    }) { Text("Mentés") }
                    TextButton(
                        onClick = {
                            val toDelete = editingTask
                            editingTask = null
                            if (toDelete != null) scope.launch { db.ganttTaskDao().delete(toDelete) }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("Törlés") }
                }
            },
            dismissButton = {
                TextButton(onClick = { editingTask = null }) { Text("Mégse") }
            }
        )
    }
}

@Composable
private fun RagStatusBadge(rag: String) {
    val (color, text) = when (rag.lowercase()) {
        "red" -> Color(0xFFD32F2F) to "🔴 KRITIKUS"
        "amber", "yellow" -> Color(0xFFF57C00) to "🟡 FIGYELEM"
        else -> Color(0xFF388E3C) to "🟢 RENDBEN"
    }
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), shape = MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(text, color = color, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable
private fun ImpactBadge(impact: String) {
    val color = when (impact.lowercase()) {
        "critical" -> Color(0xFFD32F2F)
        "high" -> Color(0xFFF57C00)
        "medium" -> Color(0xFF1976D2)
        else -> Color(0xFF388E3C)
    }
    Text(impact.uppercase(), color = color, fontWeight = FontWeight.Bold, fontSize = 11.sp)
}

@Composable
private fun PriorityBadge(priority: String) {
    val color = when (priority.lowercase()) {
        "critical" -> Color(0xFFD32F2F)
        "high" -> Color(0xFFF57C00)
        "medium" -> Color(0xFF1976D2)
        else -> Color(0xFF388E3C)
    }
    Text(priority.uppercase(), color = color, fontWeight = FontWeight.Bold, fontSize = 11.sp)
}
