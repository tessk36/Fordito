package hu.orajegyzet.data

import android.content.Context

object ProjectSeed {

    suspend fun seedIfNeeded(ctx: Context) {
        val db = Db.get(ctx)
        val existing = db.projectDao().allOnce()
        if (existing.isNotEmpty()) return

        val sampleProject = ProjectEntity(
            id = "prj-01",
            code = "PRJ-01",
            name = "IT Biztonsági & ISO 27001 Audit",
            description = "A vállalat információbiztonsági irányítási rendszerének felülvizsgálata, kockázatelemzés és megfelelőségi audit.",
            status = "active",
            ragStatus = "green",
            priority = "critical",
            manager = "Kovács Péter PM",
            startDate = "2026-09-01",
            targetDate = "2026-12-15",
            budget = "12 500 000 Ft",
            spent = "4 200 000 Ft",
            members = "Kovács Péter, Nagy Anna, Szabó Gábor, Tóth Dániel"
        )
        db.projectDao().insert(sampleProject)

        val events = listOf(
            ProjectEventEntity(
                id = "evt-1",
                projectId = "prj-01",
                date = "2026-09-05",
                type = "decision",
                title = "ISO 27001:2022 szabványverzió kiválasztása",
                description = "Az audit az új ISO 27001:2022-es követelményrendszer alapján történik.",
                impact = "high",
                owner = "Szabó Gábor CISO",
                status = "resolved",
                decisionRationale = "A korábbi 2013-as verzió kivezetésre kerül, így az új felülvizsgálat szükséges."
            ),
            ProjectEventEntity(
                id = "evt-2",
                projectId = "prj-01",
                date = "2026-09-12",
                type = "risk",
                title = "Módszertani hiányosságok a felhős infrastruktúrában",
                description = "Az AWS/Azure biztonsági beállítások nem rendelkeznek naprakész dokumentációval.",
                impact = "critical",
                owner = "Nagy Anna Cloud Architekt",
                status = "in_progress",
                mitigation = "Rendkívüli felhő-konfigurációs felmérés indítása és automatizált megfelelőségi riport generálás."
            ),
            ProjectEventEntity(
                id = "evt-3",
                projectId = "prj-01",
                date = "2026-10-01",
                type = "milestone",
                title = "Mérföldkő: Elsődleges GAP Elemzés elkészülte",
                description = "Az elsődleges hiányelemzés záróriportjának elfogadása a vezetőség által.",
                impact = "high",
                owner = "Kovács Péter PM",
                status = "open"
            )
        )
        events.forEach { db.projectEventDao().insert(it) }

        val tasks = listOf(
            GanttTaskEntity(
                id = "task-1",
                projectId = "prj-01",
                title = "Audit terjedelmének és szabályzatainak kijelölése",
                startDate = "2026-09-01",
                dueDate = "2026-09-10",
                progress = 100,
                assignee = "Kovács Péter",
                status = "completed",
                priority = "high"
            ),
            GanttTaskEntity(
                id = "task-2",
                projectId = "prj-01",
                title = "Infrastruktúra és felhős sérülékenységvizsgálat",
                startDate = "2026-09-11",
                dueDate = "2026-09-25",
                progress = 75,
                assignee = "Nagy Anna",
                status = "in_progress",
                priority = "critical"
            ),
            GanttTaskEntity(
                id = "task-3",
                projectId = "prj-01",
                title = "Adatvédelmi (GDPR) és jogosultsági mátrix ellenőrzés",
                startDate = "2026-09-20",
                dueDate = "2026-10-05",
                progress = 30,
                assignee = "Tóth Dániel",
                status = "in_progress",
                priority = "medium"
            ),
            GanttTaskEntity(
                id = "task-4",
                projectId = "prj-01",
                title = "Záró tanúsítási audit és vezetői beszámoló",
                startDate = "2026-10-10",
                dueDate = "2026-10-20",
                progress = 0,
                assignee = "Szabó Gábor",
                status = "not_started",
                priority = "high",
                isMilestone = true
            )
        )
        tasks.forEach { db.ganttTaskDao().insert(it) }
    }
}
