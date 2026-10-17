package hu.orajegyzet.ai

import android.content.Context
import android.util.Log
import hu.orajegyzet.data.AiReport
import hu.orajegyzet.data.AppSettings
import hu.orajegyzet.data.Db
import hu.orajegyzet.data.ProjectEntity
import java.time.YearMonth
import java.time.ZoneId

/**
 * Az AI-szövegek mentése és a havi összefoglaló összeállítása (1.1.5).
 * Minden sikeres AI-jelentés és jegyzet-összefoglaló automatikusan mentődik, így később visszanézhető,
 * és bekerül a havi összefoglaló forrásai közé.
 */
object ReportArchive {

    /** Mentés; hibánál nem dob (a felhasználó a szöveget így is látja). */
    suspend fun save(
        ctx: Context, type: String, title: String, text: String, engine: String?, warning: String?,
        projectId: String? = null, noteId: Long? = null, period: String? = null
    ): Long? = runCatching {
        if (text.isBlank()) return null
        Db.get(ctx).aiReportDao().insert(
            AiReport(projectId = projectId, noteId = noteId, type = type, title = title, text = text,
                engine = engine, warning = warning, period = period)
        )
    }.onFailure { Log.w("FuleloAI", "AI-szöveg mentése sikertelen: ${it.message}") }.getOrNull()

    /** A választható hónapok: az aktuális és az előző [count]-1 hónap, legújabb elöl. */
    fun recentMonths(count: Int = 6, now: YearMonth = YearMonth.now()): List<YearMonth> =
        (0 until count).map { now.minusMonths(it.toLong()) }

    /** Havi összefoglaló készítése és mentése. */
    suspend fun createMonthly(
        ctx: Context, s: AppSettings, p: ProjectEntity, month: YearMonth, forceGemini: Boolean = false
    ): Pair<ReportBuilder.Report, Long?> {
        val db = Db.get(ctx)
        val (from, to) = ReportBuilder.monthRange(month)
        val notes = db.noteDao().doneInProjectBetween(p.id, from.toString(), to.toString())
        val zone = ZoneId.systemDefault()
        val fromMs = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val toMs = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val saved = db.aiReportDao().byProjectBetween(p.id, fromMs, toMs)
            .filter { it.type != ReportBuilder.MONTHLY }
        val meetings = notes.map { n ->
            ReportBuilder.MeetingDigest(n.dateIso, n.subject, n.summary.ifBlank { n.transcript })
        }
        val savedDigests = saved.map { r ->
            ReportBuilder.SavedDigest(
                java.time.Instant.ofEpochMilli(r.createdAt).atZone(zone).toLocalDate().toString(), r.type, r.text)
        }
        val events = db.projectEventDao().byProjectOnce(p.id)
        val tasks = db.ganttTaskDao().byProjectOnce(p.id)
        val r = ReportBuilder.generateMonthly(ctx, s, p, month, meetings, savedDigests, events, tasks, forceGemini)
        val id = save(ctx, ReportBuilder.MONTHLY, "${ReportBuilder.MONTHLY} – $month", r.text, r.engine, r.warning,
            projectId = p.id, period = month.toString())
        return r to id
    }
}
