package hu.orajegyzet.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.orajegyzet.data.Db
import hu.orajegyzet.data.NoteStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Folyamatjelző a feldolgozás VALÓDI lépésével (pl. „Leirat: 3/12 darab (Whisper)",
 * „Összefoglaló: Gemma (2/4 blokk)"). A korábbi tréfás, körbeforgó feliratok helyett (1.0.4).
 */
@Composable
fun ProcessingIndicator(modifier: Modifier = Modifier, label: String? = null) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text(label?.takeIf { it.isNotBlank() } ?: "Feldolgozás…", fontSize = 13.sp,
            fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
    }
}

/** Kis címke: mi készítette az összefoglalót (Gemma / Gemini / kivonat / offline vázlat). */
@Composable
fun EngineBadge(engine: String?, modifier: Modifier = Modifier) {
    val label = hu.orajegyzet.ai.SummaryEngine.label(engine) ?: return
    val ai = engine == hu.orajegyzet.ai.SummaryEngine.GEMMA || engine == hu.orajegyzet.ai.SummaryEngine.GEMINI
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = if (ai) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.tertiaryContainer
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium,
            color = if (ai) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onOpenNote: (Long) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val db = remember { Db.get(ctx) }
    val scope = rememberCoroutineScope()
    val since = LocalDate.now().minusDays(14).toString()
    val notes by db.noteDao().since(since).collectAsState(initial = emptyList())
    val selected = remember { mutableStateListOf<Long>() }
    var confirmDelete by remember { mutableStateOf(false) }

    val expandedTopics = remember { mutableStateMapOf<String, Boolean>() }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Kijelöltek törlése") },
            text = { Text("Biztosan törlöd a kijelölt ${selected.size} jegyzetet?") },
            confirmButton = {
                TextButton(onClick = {
                    val ids = selected.toList()
                    scope.launch {
                        ids.forEach { id -> db.noteDao().byId(id)?.let { db.noteDao().delete(it) } }
                    }
                    selected.clear()
                    confirmDelete = false
                }) { Text("Törlés") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Mégse") } }
        )
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (selected.isEmpty()) "Előzmények (Témák & Projektek)" else "${selected.size} kijelölve") },
            navigationIcon = { BackButton(onBack) },
            actions = {
                if (selected.isNotEmpty()) {
                    TextButton(onClick = { selected.clear() }) { Text("Elvet") }
                    TextButton(onClick = { confirmDelete = true }) { Text("Törlés") }
                }
            }
        )
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (notes.isEmpty()) {
                    item {
                        Text(
                            "Nincs jegyzet a korábbi előzményekben.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Témák / Órák / Projektek szerint csoportosítva
                val byTopic = notes.groupBy { it.subject }

                byTopic.forEach { (subject, topicNotes) ->
                    val isExpanded = expandedTopics[subject] ?: false

                    item(key = "header_$subject") {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { expandedTopics[subject] = !isExpanded }
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            subject,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 16.sp
                                        )
                                        Text(
                                            "${topicNotes.size} jegyzet / megbeszélés",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(
                                        if (isExpanded) "▾ Összecsukás" else "▸ Kibontás (${topicNotes.size})",
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }

                                if (isExpanded) {
                                    Column(
                                        Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        topicNotes.forEach { n ->
                                            val isSel = selected.contains(n.id)
                                            Card(
                                                onClick = { if (n.status == NoteStatus.DONE) onOpenNote(n.id) },
                                                colors = if (isSel) CardDefaults.cardColors(
                                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                                ) else CardDefaults.cardColors()
                                            ) {
                                                Row(
                                                    Modifier.fillMaxWidth().padding(10.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Checkbox(
                                                        checked = isSel,
                                                        onCheckedChange = {
                                                            if (isSel) selected.remove(n.id) else selected.add(n.id)
                                                        }
                                                    )
                                                    Column(Modifier.weight(1f)) {
                                                        Text("${n.dateIso} · ${fmt(n.startMin)}", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                                        if (n.docCode.isNotBlank()) {
                                                            Text(n.docCode, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                                        }
                                                        Spacer(Modifier.height(2.dp))
                                                        val preview = when (n.status) {
                                                            NoteStatus.DONE -> n.summary.take(80).ifBlank { "(leirat kész)" }
                                                            NoteStatus.PROCESSING, NoteStatus.QUEUED -> n.progress ?: "feldolgozás alatt…"
                                                            NoteStatus.RECORDING -> "● felvétel"
                                                            NoteStatus.ERROR -> "hiba: ${n.error.take(60)}"
                                                        }
                                                        Text(
                                                            preview,
                                                            fontSize = 12.sp,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
