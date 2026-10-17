package hu.orajegyzet.ai

import android.content.Context
import android.util.Log
import hu.orajegyzet.data.AppSettings
import hu.orajegyzet.data.GanttTaskEntity
import hu.orajegyzet.data.ProjectEntity
import hu.orajegyzet.data.ProjectEventEntity
import java.time.LocalDate

/**
 * Projektjelentések (Vezetői összefoglaló, Státuszjelentés, QA-audit) – 1.0.4.
 *
 * A bemenet MINDIG a kódban összeállított, rövid tényblokk. Útválasztás:
 *  1. adatvédelmi zár → Gemma;
 *  2. Gemini engedélyezett és most van hálózat → Gemini; hibánál Gemma;
 *  3. különben Gemma;
 *  4. ha nincs Gemma / hibázik / időtúllép → sablonváz („Offline vázlat"), modell nélkül.
 * A prompt soha nem kerül kivonatolóra.
 */
object ReportBuilder {

    const val EXECUTIVE = "Vezetői Összefoglaló"
    const val STATUS = "Státuszjelentés"
    const val QA = "QA Minőségi Audit"
    const val MONTHLY = "Havi összefoglaló"

    data class Report(val text: String, val engine: String, val warning: String? = null)

    /** A Gemma-promptban a tényblokk legfeljebb ennyi tokent foglalhat (4000-es keretből). */
    private const val FACT_TOKEN_BUDGET = 2800

    // ---------------- Tiszta függvények (unit teszt) ----------------

    private fun date(s: String?): LocalDate? = s?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }

    private fun isDone(t: GanttTaskEntity) = t.status == "completed" || t.progress >= 100

    fun overdueTasks(tasks: List<GanttTaskEntity>, today: LocalDate): List<GanttTaskEntity> =
        tasks.filter { !isDone(it) && date(it.dueDate)?.isBefore(today) == true }
            .sortedBy { it.dueDate }

    fun dueSoonTasks(tasks: List<GanttTaskEntity>, today: LocalDate, days: Long): List<GanttTaskEntity> =
        tasks.filter { t -> !isDone(t) && date(t.dueDate)?.let { !it.isBefore(today) && !it.isAfter(today.plusDays(days)) } == true }
            .sortedBy { it.dueDate }

    private fun openRisks(events: List<ProjectEventEntity>) =
        events.filter { it.type == "risk" && (it.status ?: "open") !in setOf("resolved", "done") }
            .sortedBy { impactRank(it.impact) }

    private fun impactRank(i: String) = when (i) { "critical" -> 0; "high" -> 1; "medium" -> 2; else -> 3 }

    private fun ragHu(rag: String) = when (rag.lowercase()) {
        "red" -> "PIROS"; "amber", "yellow" -> "SÁRGA"; else -> "ZÖLD"
    }

    private fun taskLine(t: GanttTaskEntity): String {
        val who = t.assignee.ifBlank { "nincs felelős" }
        val due = t.dueDate.ifBlank { "nincs határidő" }
        return "${t.title} (felelős: $who, határidő: $due, állapot: ${statusHu(t)}, haladás: ${t.progress}%)"
    }

    private fun statusHu(t: GanttTaskEntity) = when {
        isDone(t) -> "kész"
        t.status == "blocked" -> "akadályozott"
        t.status == "in_progress" || t.progress > 0 -> "folyamatban"
        else -> "nem kezdődött el"
    }

    private fun eventLine(e: ProjectEventEntity): String {
        val type = when (e.type) { "decision" -> "Döntés"; "risk" -> "Kockázat"; "milestone" -> "Mérföldkő"; else -> e.type }
        val owner = e.owner?.takeIf { it.isNotBlank() }?.let { ", felelős: $it" } ?: ""
        val d = e.date.takeIf { it.isNotBlank() }?.let { ", dátum: $it" } ?: ""
        return "$type: ${e.title} (hatás: ${e.impact}$owner$d)"
    }

    /**
     * A tényblokk. Ha túl hosszú a Gemma 4000 tokenes keretéhez, a feladatlistát a lejárt és
     * a 14 napon belül esedékes tételekre rövidíti.
     */
    fun factBlock(type: String, p: ProjectEntity, events: List<ProjectEventEntity>,
                  tasks: List<GanttTaskEntity>, today: LocalDate, shortenTasks: Boolean = false): String = buildString {
        val overdue = overdueTasks(tasks, today)
        val risks = openRisks(events)
        appendLine("Projekt: ${p.code} – ${p.name}")
        appendLine("Dátum: $today")
        appendLine("RAG-állapot: ${ragHu(p.ragStatus)} (lejárt feladat: ${overdue.size}, nyitott kockázat: ${risks.size})")
        if (p.manager.isNotBlank()) appendLine("Projektvezető: ${p.manager}")
        if (p.targetDate.isNotBlank()) appendLine("Céldátum: ${p.targetDate}")
        if (type == STATUS) appendLine("Költség: ${p.spent ?: "0"} / ${p.budget ?: "ismeretlen"}")
        val evs = events.sortedByDescending { it.date }.take(15)
        if (evs.isNotEmpty()) {
            appendLine("Döntések, kockázatok, mérföldkövek:")
            evs.forEach { appendLine("- ${eventLine(it)}") }
        } else appendLine("Döntések, kockázatok: nincs rögzítve.")
        val shownTasks = if (shortenTasks) (overdue + dueSoonTasks(tasks, today, 14)).distinctBy { it.id } else tasks
        if (shownTasks.isNotEmpty()) {
            appendLine(if (shortenTasks) "Feladatok (lejárt és 14 napon belül esedékes):" else "Feladatok:")
            shownTasks.take(25).forEach { appendLine("- ${taskLine(it)}") }
        } else appendLine("Feladatok: nincs rögzítve.")
    }.trim()

    /** Egy megbeszélés a havi összefoglalóhoz. */
    data class MeetingDigest(val date: String, val subject: String, val summary: String)

    /** Egy korábban mentett AI-szöveg a havi összefoglalóhoz. */
    data class SavedDigest(val date: String, val type: String, val text: String)

    /** A hónap első és utolsó napja. */
    fun monthRange(month: java.time.YearMonth): Pair<LocalDate, LocalDate> = month.atDay(1) to month.atEndOfMonth()

    private fun clip(text: String, max: Int): String {
        val t = text.replace(Regex("\\s+"), " ").trim()
        return if (t.length <= max) t else t.take(max).substringBeforeLast(' ') + "…"
    }

    /**
     * A havi tényblokk: a hónap megbeszélései (összefoglalóval), a hónapban mentett AI-szövegek,
     * a hónap döntései/kockázatai és a feladatok állapota. [maxChars] a teljes hossz felső korlátja
     * (Gemmánál kb. 11 000 karakter fér a 4000 tokenes keretbe); a szövegek arányosan rövidülnek.
     */
    fun monthlyFactBlock(
        p: ProjectEntity, month: java.time.YearMonth, meetings: List<MeetingDigest>, saved: List<SavedDigest>,
        events: List<ProjectEventEntity>, tasks: List<GanttTaskEntity>, maxChars: Int = 11_000
    ): String {
        val (from, to) = monthRange(month)
        val monthEvents = events.filter { e -> date(e.date)?.let { !it.isBefore(from) && !it.isAfter(to) } == true }
            .sortedBy { it.date }
        val header = buildString {
            appendLine("Projekt: ${p.code} – ${p.name}")
            appendLine("Időszak: $month ($from – $to)")
            appendLine("RAG-állapot: ${ragHu(p.ragStatus)}")
            appendLine("Megbeszélések száma: ${meetings.size}, mentett jelentések: ${saved.size}")
        }
        val eventsPart = buildString {
            if (monthEvents.isNotEmpty()) {
                appendLine("A hónap döntései, kockázatai, mérföldkövei:")
                monthEvents.take(20).forEach { appendLine("- ${eventLine(it)}") }
            } else appendLine("A hónapban nem rögzítettek döntést vagy kockázatot.")
        }
        val tasksPart = buildString {
            val relevant = tasks.filter { t ->
                listOf(t.dueDate, t.startDate).any { d -> date(d)?.let { !it.isBefore(from) && !it.isAfter(to) } == true } ||
                    (!isDone(t) && date(t.dueDate)?.isBefore(from) == true)
            }
            if (relevant.isNotEmpty()) {
                appendLine("A hónapot érintő feladatok:")
                relevant.take(20).forEach { appendLine("- ${taskLine(it)}") }
            } else appendLine("A hónapot érintő feladat nincs rögzítve.")
        }
        val fixed = header.length + eventsPart.length + tasksPart.length + 200
        val budget = (maxChars - fixed).coerceAtLeast(1500)
        // a megbeszélések kapják a keret kb. kétharmadát, a mentett jelentések a maradékot
        val meetBudget = if (saved.isEmpty()) budget else budget * 2 / 3
        val savedBudget = budget - meetBudget
        val perMeeting = if (meetings.isEmpty()) 0 else (meetBudget / meetings.size - 60).coerceAtLeast(120)
        val perSaved = if (saved.isEmpty()) 0 else (savedBudget / saved.size - 60).coerceAtLeast(120)
        return buildString {
            append(header)
            if (meetings.isNotEmpty()) {
                appendLine("Megbeszélések:")
                meetings.forEach { appendLine("- ${it.date} · ${it.subject}: ${clip(it.summary, perMeeting)}") }
            } else appendLine("Megbeszélés ebben a hónapban nem volt rögzítve.")
            if (saved.isNotEmpty()) {
                appendLine("Korábbi jelentések és összefoglalók a hónapból:")
                saved.forEach { appendLine("- ${it.date} · ${it.type}: ${clip(it.text, perSaved)}") }
            }
            append(eventsPart)
            append(tasksPart)
        }.trim().take(maxChars)
    }

    /** Modell nélküli havi vázlat. */
    fun monthlyTemplate(p: ProjectEntity, month: java.time.YearMonth, meetings: List<MeetingDigest>,
                        events: List<ProjectEventEntity>, tasks: List<GanttTaskEntity>): String = buildString {
        val (from, to) = monthRange(month)
        fun inMonth(d: String?) = date(d)?.let { !it.isBefore(from) && !it.isAfter(to) } == true
        appendLine("**${p.code} – ${p.name}** · havi összefoglaló: $month")
        appendLine()
        appendLine("**Megbeszélések (${meetings.size})**")
        if (meetings.isEmpty()) appendLine("- nincs")
        meetings.forEach { appendLine("- ${it.date} – ${it.subject}") }
        appendLine()
        val decisions = events.filter { it.type == "decision" && inMonth(it.date) }
        appendLine("**Döntések**")
        if (decisions.isEmpty()) appendLine("- nincs rögzített döntés")
        decisions.forEach { appendLine("- ${it.date}: ${it.title}") }
        appendLine()
        val risks = events.filter { it.type == "risk" && inMonth(it.date) }
        appendLine("**Kockázatok**")
        if (risks.isEmpty()) appendLine("- nincs új kockázat")
        risks.forEach { appendLine("- ${it.title} (hatás: ${it.impact})") }
        appendLine()
        val done = tasks.filter { isDone(it) && inMonth(it.dueDate) }
        val open = tasks.filter { !isDone(it) && (inMonth(it.dueDate) || date(it.dueDate)?.isBefore(from) == true) }
        appendLine("**Elvégezve**")
        if (done.isEmpty()) appendLine("- nincs")
        done.forEach { appendLine("- ${it.title}") }
        appendLine()
        appendLine("**Nyitott / késésben**")
        if (open.isEmpty()) appendLine("- nincs")
        open.forEach { appendLine("- ${it.title} – ${it.assignee.ifBlank { "nincs felelős" }}, határidő: ${it.dueDate}") }
        appendLine()
        append("_Offline vázlat: modell nélkül, a nyilvántartás adataiból._")
    }.trim()

    private fun instruction(type: String) = when (type) {
        EXECUTIVE -> "Készíts határozott, hivatalos Vezetői Összefoglalót (legfeljebb 200 szó): " +
            "1) RAG-állapot egy mondatban, indoklással; 2) 3–5 legfontosabb eredmény vagy döntés; " +
            "3) a fő kockázatok (legfeljebb 3); 4) „Vezetői döntést igényel” pont, ha van; 5) következő mérföldkő."
        STATUS -> "Készíts hivatalos Projekt Státuszjelentést (legfeljebb 400 szó) ezekkel a részekkel: " +
            "Elvégezve; Folyamatban; Akadályok / kockázatok; Következő időszak tervei; Meghozott döntések."
        MONTHLY -> "Készíts hivatalos Havi összefoglalót a projekt adott hónapjáról (legfeljebb 450 szó) ezekkel a részekkel: " +
            "**A hónap röviden** (2–3 mondat); **Megbeszélések** (dátummal, egy-egy sor); **Döntések**; " +
            "**Elvégzett és folyamatban lévő feladatok**; **Kockázatok, nyitott kérdések**; **Teendők a következő hónapra**. " +
            "A korábbi jelentéseket és jegyzet-összefoglalókat forrásként használd, de ne ismételd szó szerint."
        else -> "Végezz tárgyilagos QA-felülvizsgálatot: nyitott kockázatok, késésben lévő és akadályozott feladatok, " +
            "és mindegyikhez javasolt intézkedés."
    }

    fun prompt(type: String, facts: String): String = """
        ${instruction(type)}
        Kizárólag a megadott tényeket használd. Ne adj hozzá új nevet, dátumot vagy számot.
        A RAG-állapotot ne változtasd meg. Magyarul, markdown-listákkal válaszolj.

        Tények:
        $facts
    """.trimIndent()

    /** Modell nélküli, a nyilvántartás adataiból összerakott vázlat. Soha nem tartalmazza a promptot. */
    fun templateDraft(type: String, p: ProjectEntity, events: List<ProjectEventEntity>,
                      tasks: List<GanttTaskEntity>, today: LocalDate): String = buildString {
        val overdue = overdueTasks(tasks, today)
        val risks = openRisks(events)
        val decisions = events.filter { it.type == "decision" }.sortedByDescending { it.date }
        appendLine("**${p.code} – ${p.name}** · $today")
        appendLine()
        appendLine("**RAG-állapot:** ${ragHu(p.ragStatus)} – lejárt feladat: ${overdue.size}, nyitott kockázat: ${risks.size}.")
        appendLine()
        when (type) {
            EXECUTIVE -> {
                appendLine("**Legfontosabb döntések**")
                if (decisions.isEmpty()) appendLine("- nincs rögzített döntés")
                decisions.take(5).forEach { appendLine("- ${it.title}") }
                appendLine()
                appendLine("**Fő kockázatok**")
                if (risks.isEmpty()) appendLine("- nincs nyitott kockázat")
                risks.take(3).forEach { appendLine("- ${it.title} (hatás: ${it.impact})") }
                appendLine()
                appendLine("**Lejárt feladatok (${overdue.size})**")
                if (overdue.isEmpty()) appendLine("- nincs")
                overdue.take(5).forEach { appendLine("- ${it.title} – ${it.assignee.ifBlank { "nincs felelős" }}, határidő: ${it.dueDate}") }
                appendLine()
                val next = tasks.filter { !isDone(it) && date(it.dueDate)?.isBefore(today) == false }
                    .minByOrNull { it.dueDate }
                appendLine("**Következő határidő:** " + (next?.let { "${it.title} – ${it.dueDate}" } ?: "nincs"))
            }
            STATUS -> {
                val done = tasks.filter { isDone(it) }
                val inProg = tasks.filter { !isDone(it) && (it.status == "in_progress" || it.progress > 0) && it.status != "blocked" }
                val blocked = tasks.filter { !isDone(it) && it.status == "blocked" }
                val next = tasks.filter { !isDone(it) && it.status != "blocked" && it.progress == 0 && it.status != "in_progress" }
                    .sortedBy { it.dueDate }
                fun section(title: String, items: List<String>) {
                    appendLine("**$title**")
                    if (items.isEmpty()) appendLine("- nincs") else items.forEach { appendLine("- $it") }
                    appendLine()
                }
                section("Elvégezve", done.take(8).map { it.title })
                section("Folyamatban", inProg.take(8).map { "${it.title} (${it.progress}%)" })
                section("Akadályok / kockázatok", blocked.map { "${it.title} – akadályozott" } + risks.take(3).map { "${it.title} (kockázat)" })
                section("Következő lépések", next.take(5).map { "${it.title} – ${it.dueDate.ifBlank { "nincs határidő" }}" })
                section("Döntések", decisions.take(5).map { it.title })
                appendLine("**Költség:** ${p.spent ?: "0"} / ${p.budget ?: "ismeretlen"}")
            }
            else -> {
                val blocked = tasks.filter { !isDone(it) && it.status == "blocked" }
                appendLine("**Nyitott kockázatok**")
                if (risks.isEmpty()) appendLine("- nincs")
                risks.forEach { appendLine("- ${it.title} (hatás: ${it.impact})" + (it.mitigation?.takeIf { m -> m.isNotBlank() }?.let { m -> " – enyhítés: $m" } ?: "")) }
                appendLine()
                appendLine("**Késésben lévő feladatok**")
                if (overdue.isEmpty()) appendLine("- nincs")
                overdue.forEach { appendLine("- ${it.title} – ${it.assignee.ifBlank { "nincs felelős" }}, határidő: ${it.dueDate}") }
                appendLine()
                appendLine("**Akadályozott feladatok**")
                if (blocked.isEmpty()) appendLine("- nincs")
                blocked.forEach { appendLine("- ${it.title}") }
            }
        }
        appendLine()
        append("_Offline vázlat: modell nélkül, a nyilvántartás adataiból._")
    }.trim()

    // ---------------- Útválasztás és generálás ----------------

    suspend fun generate(
        ctx: Context, s: AppSettings, type: String, p: ProjectEntity,
        events: List<ProjectEventEntity>, tasks: List<GanttTaskEntity>,
        forceGemini: Boolean = false,
        today: LocalDate = LocalDate.now()
    ): Report {
        val facts = factBlock(type, p, events, tasks, today)
        return route(
            ctx, s, type, forceGemini,
            geminiFacts = facts,
            gemmaFacts = {
                if (GemmaSession.estimateTokens(facts) > FACT_TOKEN_BUDGET)
                    factBlock(type, p, events, tasks, today, shortenTasks = true) else facts
            },
            template = { templateDraft(type, p, events, tasks, today) },
            gemmaTimeoutMs = 180_000L
        )
    }

    /**
     * Havi összefoglaló (1.1.5) a hónap megbeszéléseiből, mentett jelentéseiből, döntéseiből és feladataiból.
     * Geminin bővebb tényblokk, Gemmán a 4000 tokenes kerethez rövidített.
     */
    suspend fun generateMonthly(
        ctx: Context, s: AppSettings, p: ProjectEntity, month: java.time.YearMonth,
        meetings: List<MeetingDigest>, saved: List<SavedDigest>,
        events: List<ProjectEventEntity>, tasks: List<GanttTaskEntity>,
        forceGemini: Boolean = false
    ): Report = route(
        ctx, s, MONTHLY, forceGemini,
        geminiFacts = monthlyFactBlock(p, month, meetings, saved, events, tasks, maxChars = 60_000),
        gemmaFacts = { monthlyFactBlock(p, month, meetings, saved, events, tasks, maxChars = FACT_TOKEN_BUDGET * 4) },
        template = { monthlyTemplate(p, month, meetings, events, tasks) },
        gemmaTimeoutMs = 300_000L
    )

    /** Közös útválasztás: Gemini → Gemma → sablonváz. */
    private suspend fun route(
        ctx: Context, s: AppSettings, type: String, forceGemini: Boolean,
        geminiFacts: String, gemmaFacts: () -> String, template: () -> String, gemmaTimeoutMs: Long
    ): Report {
        var warning: String? = null

        val geminiAllowed = if (forceGemini)
            !s.privacyLock && s.geminiApiKey.isNotBlank() && ModeSelector.hasNetwork(ctx, s.wifiOnly)
        else ModeSelector.canUseGeminiNow(ctx, s)
        if (geminiAllowed) {
            try {
                val text = GeminiPipeline(s.geminiApiKey, s.geminiModel).generateFreeText(prompt(type, geminiFacts))
                if (text.isNotBlank()) {
                    Log.i("FuleloAI", "report=$type engine=GEMINI")
                    return Report(text, SummaryEngine.GEMINI)
                }
                warning = "A Gemini üres választ adott, offline készült."
            } catch (e: Exception) {
                warning = "A Gemini nem volt elérhető (${e.message}), offline készült."
            }
        } else if (forceGemini) {
            warning = "Most nem érhető el a Gemini (hálózat, kulcs vagy adatvédelmi zár), offline készült."
        }

        // Gemma
        val gemmaFile = GemmaModel.file(ctx)
        if (s.useGemma && gemmaFile != null) {
            val facts = gemmaFacts()
            try {
                OfflinePipeline.releaseWarmWhisper()
                val raw = GemmaSession.open(ctx, gemmaFile).use { it.generate(prompt(type, facts), gemmaTimeoutMs) }
                val text = OfflinePipeline.cleanLoops(raw)
                when {
                    text.length < 40 -> warning = listOfNotNull(warning, "A Gemma válasza üres vagy túl rövid volt.").joinToString(" ")
                    OfflinePipeline.echoesPrompt(text, instruction(type)) ->
                        warning = listOfNotNull(warning, "A Gemma a kérést ismételte vissza.").joinToString(" ")
                    else -> {
                        val check = OfflinePipeline.checkCoherence(text, facts, allowedText = prompt(type, facts))
                        if (check.verdict == CoherenceVerdict.REJECT) {
                            warning = listOfNotNull(warning,
                                "A Gemma válasza alig kapcsolódott a tényekhez (egyezés: ${check.percent}%), elvetettem.").joinToString(" ")
                        } else {
                            Log.i("FuleloAI", "report=$type engine=GEMMA coherence=${check.percent}%")
                            return Report(text, SummaryEngine.GEMMA, listOfNotNull(warning, check.warning()).joinToString(" ").ifBlank { null })
                        }
                    }
                }
            } catch (e: Exception) {
                warning = listOfNotNull(warning, "A Gemma nem futott: ${e.message}.").joinToString(" ")
            }
        } else {
            warning = listOfNotNull(warning,
                if (!s.useGemma) "A Gemma ki van kapcsolva." else "Nincs telepítve Gemma-modell.").joinToString(" ")
        }

        Log.i("FuleloAI", "report=$type engine=TEMPLATE reason=$warning")
        return Report(template(), SummaryEngine.TEMPLATE, warning)
    }
}
