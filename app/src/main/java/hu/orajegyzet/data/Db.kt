package hu.orajegyzet.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** Egy órarendi bejegyzés. dayOfWeek: 1 = hétfő ... 7 = vasárnap. Idők percben éjféltől. */
@Entity(tableName = "lessons")
data class Lesson(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subject: String,
    val teacher: String = "",
    val room: String = "",
    val dayOfWeek: Int,
    val startMin: Int,
    val endMin: Int
)

enum class NoteStatus { RECORDING, QUEUED, PROCESSING, DONE, ERROR }

/** Egy óra / megbeszélés jegyzete. dateIso: ÉÉÉÉ-HH-NN. */
@Entity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lessonId: Long?,
    val projectId: String? = null,        // Kapcsolat az aktív projekthez (pl. "prj-01")
    val docCode: String = "",             // pl. "PROJ-001-20260910-EML-001" vagy "MAT-20260911-JEG-001"
    val docType: String = "JEG",          // EML, JZK, JEG, VEZ, STA
    val subject: String,
    val dateIso: String,
    val startMin: Int,
    val endMin: Int,
    val status: NoteStatus = NoteStatus.RECORDING,
    val audioPath: String? = null,
    val transcript: String = "",
    val summary: String = "",
    val structured: String = "",
    val keywords: String = "",
    val error: String = "",
    val processedOnline: Boolean? = null,
    val summaryEngine: String? = null,
    val summaryWarning: String? = null,
    /** A feldolgozás aktuális, valódi lépése (pl. „Leirat: 3/12 darab (Whisper)"). 1.0.4 */
    val progress: String? = null
)

// 1. PROJEKT ENTITÁS
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val code: String,                    // pl. "PRJ-01"
    val name: String,                    // pl. "IT Biztonsági & ISO 27001 Audit"
    val description: String = "",
    val status: String = "active",       // "active" | "planning" | "on_hold" | "completed"
    val ragStatus: String = "green",     // "green" (zöld) | "amber" (sárga) | "red" (piros)
    val priority: String = "high",       // "low" | "medium" | "high" | "critical"
    val manager: String = "Projektvezető",
    val startDate: String = "",
    val targetDate: String = "",
    val budget: String? = null,
    val spent: String? = null,
    val members: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

// 2. DÖNTÉSI ÉS ESEMÉNYNAPLÓ (DECISION LOG)
@Entity(tableName = "project_events")
data class ProjectEventEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val projectId: String,
    val date: String = "",               // "YYYY-MM-DD"
    val type: String = "decision",       // "decision" | "milestone" | "risk" | "action" | "status"
    val title: String,
    val description: String = "",
    val impact: String = "medium",       // "low" | "medium" | "high" | "critical"
    val sourceMeetingId: String? = null,
    val owner: String? = null,
    val status: String? = "open",        // "open" | "in_progress" | "resolved" | "done"
    val decisionRationale: String? = null,
    val mitigation: String? = null
)

// 3. GANTT / FELADATHÁLÓZAT (WBS)
@Entity(tableName = "gantt_tasks")
data class GanttTaskEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val projectId: String,
    val title: String,
    val startDate: String = "",          // "YYYY-MM-DD"
    val dueDate: String = "",            // "YYYY-MM-DD"
    val progress: Int = 0,               // 0 .. 100
    val assignee: String = "",
    val status: String = "not_started",  // "not_started" | "in_progress" | "completed" | "blocked"
    val priority: String = "medium",     // "low" | "medium" | "high" | "critical"
    val dependencies: String = "",       // Előfeltétel feladat azonosítók
    val isMilestone: Boolean = false,
    val description: String? = null
)

@Dao
interface LessonDao {
    @Query("SELECT * FROM lessons ORDER BY dayOfWeek, startMin")
    fun all(): Flow<List<Lesson>>

    @Query("SELECT * FROM lessons")
    suspend fun allOnce(): List<Lesson>

    @Query("SELECT * FROM lessons WHERE dayOfWeek = :day ORDER BY startMin")
    fun byDay(day: Int): Flow<List<Lesson>>

    @Query("SELECT * FROM lessons WHERE id = :id")
    suspend fun byId(id: Long): Lesson?

    @Query("DELETE FROM lessons")
    suspend fun deleteAll()

    @Insert suspend fun insert(l: Lesson): Long
    @Delete suspend fun delete(l: Lesson)
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY dateIso DESC, startMin DESC")
    fun all(): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun byId(id: Long): Note?

    @Query("SELECT * FROM notes WHERE id = :id")
    fun byIdFlow(id: Long): Flow<Note?>

    @Query("SELECT * FROM notes WHERE dateIso = :date AND status = 'DONE' ORDER BY startMin")
    suspend fun doneOn(date: String): List<Note>

    @Query("SELECT * FROM notes WHERE dateIso = :date")
    suspend fun allOnDate(date: String): List<Note>

    @Query("SELECT * FROM notes WHERE dateIso >= :sinceDate ORDER BY dateIso DESC, startMin DESC")
    fun since(sinceDate: String): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE projectId = :projectId ORDER BY dateIso DESC")
    fun byProject(projectId: String): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE projectId = :projectId AND status = 'DONE' AND dateIso >= :from AND dateIso <= :to ORDER BY dateIso, startMin")
    suspend fun doneInProjectBetween(projectId: String, from: String, to: String): List<Note>

    @Query("SELECT * FROM notes WHERE status = 'RECORDING' LIMIT 1")
    suspend fun activeRecording(): Note?

    @Query("UPDATE notes SET status = 'ERROR', error = 'Megszakadt feldolgozás (az app újraindult). Indíts új felvételt.' WHERE status = 'PROCESSING'")
    suspend fun failStuckProcessing()

    @Query("UPDATE notes SET progress = :progress WHERE id = :id")
    suspend fun setProgress(id: Long, progress: String?)

    @Query("UPDATE notes SET audioPath = null WHERE audioPath = :path")
    suspend fun clearAudioPath(path: String)

    @Query("DELETE FROM notes")
    suspend fun deleteAll()

    @Insert suspend fun insert(n: Note): Long
    @Update suspend fun update(n: Note)
    @Delete suspend fun delete(n: Note)
}

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY createdAt DESC")
    fun all(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects")
    suspend fun allOnce(): List<ProjectEntity>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun byId(id: String): ProjectEntity?

    @Query("SELECT * FROM projects WHERE id = :id")
    fun byIdFlow(id: String): Flow<ProjectEntity?>

    @Query("DELETE FROM projects")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(p: ProjectEntity)

    @Update suspend fun update(p: ProjectEntity)
    @Delete suspend fun delete(p: ProjectEntity)
}

@Dao
interface ProjectEventDao {
    @Query("SELECT * FROM project_events WHERE projectId = :projectId ORDER BY date DESC")
    fun byProject(projectId: String): Flow<List<ProjectEventEntity>>

    @Query("SELECT * FROM project_events WHERE projectId = :projectId ORDER BY date DESC")
    suspend fun byProjectOnce(projectId: String): List<ProjectEventEntity>

    @Query("DELETE FROM project_events WHERE projectId = :projectId")
    suspend fun deleteByProject(projectId: String)

    @Query("DELETE FROM project_events")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(e: ProjectEventEntity)

    @Update suspend fun update(e: ProjectEventEntity)
    @Delete suspend fun delete(e: ProjectEventEntity)
}

@Dao
interface GanttTaskDao {
    @Query("SELECT * FROM gantt_tasks WHERE projectId = :projectId ORDER BY startDate ASC")
    fun byProject(projectId: String): Flow<List<GanttTaskEntity>>

    @Query("SELECT * FROM gantt_tasks WHERE projectId = :projectId ORDER BY startDate ASC")
    suspend fun byProjectOnce(projectId: String): List<GanttTaskEntity>

    @Query("DELETE FROM gantt_tasks WHERE projectId = :projectId")
    suspend fun deleteByProject(projectId: String)

    @Query("DELETE FROM gantt_tasks")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(t: GanttTaskEntity)

    @Update suspend fun update(t: GanttTaskEntity)
    @Delete suspend fun delete(t: GanttTaskEntity)
}

/**
 * Mentett AI-szövegek (1.1.5): projektjelentések (Vezetői, Státusz, QA, Havi) és a jegyzetek
 * típusos összefoglalói (Vezetői, Jegyzőkönyv, Teendők, Részletes). Ezekből készül a havi összefoglaló is.
 */
@Entity(tableName = "ai_reports")
data class AiReport(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: String? = null,
    val noteId: Long? = null,
    val type: String,
    val title: String,
    val text: String,
    val engine: String? = null,
    val warning: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** Havi összefoglalónál az időszak, pl. „2026-10”. */
    val period: String? = null
)

@Dao
interface AiReportDao {
    @Query("SELECT * FROM ai_reports WHERE projectId = :projectId ORDER BY createdAt DESC")
    fun byProject(projectId: String): Flow<List<AiReport>>

    @Query("SELECT * FROM ai_reports WHERE noteId = :noteId ORDER BY createdAt DESC")
    fun byNote(noteId: Long): Flow<List<AiReport>>

    @Query("SELECT * FROM ai_reports WHERE projectId = :projectId AND createdAt >= :fromMs AND createdAt < :toMs ORDER BY createdAt")
    suspend fun byProjectBetween(projectId: String, fromMs: Long, toMs: Long): List<AiReport>

    @Query("DELETE FROM ai_reports WHERE projectId = :projectId")
    suspend fun deleteByProject(projectId: String)

    @Query("DELETE FROM ai_reports")
    suspend fun deleteAll()

    @Insert suspend fun insert(r: AiReport): Long
    @Delete suspend fun delete(r: AiReport)
}

@Database(
    entities = [
        Lesson::class,
        Note::class,
        ProjectEntity::class,
        ProjectEventEntity::class,
        GanttTaskEntity::class,
        AiReport::class
    ],
    version = 6,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class Db : RoomDatabase() {
    abstract fun lessonDao(): LessonDao
    abstract fun noteDao(): NoteDao
    abstract fun projectDao(): ProjectDao
    abstract fun projectEventDao(): ProjectEventDao
    abstract fun ganttTaskDao(): GanttTaskDao
    abstract fun aiReportDao(): AiReportDao

    companion object {
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN summaryEngine TEXT")
                db.execSQL("ALTER TABLE notes ADD COLUMN summaryWarning TEXT")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN progress TEXT")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ai_reports` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `projectId` TEXT, `noteId` INTEGER, " +
                        "`type` TEXT NOT NULL, `title` TEXT NOT NULL, `text` TEXT NOT NULL, `engine` TEXT, " +
                        "`warning` TEXT, `createdAt` INTEGER NOT NULL, `period` TEXT)"
                )
            }
        }

        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, Db::class.java, "orajegyzet.db")
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build().also { inst = it }
        }

        suspend fun resetAllData(ctx: Context) {
            val db = get(ctx)
            db.noteDao().deleteAll()
            db.aiReportDao().deleteAll()
            db.projectEventDao().deleteAll()
            db.ganttTaskDao().deleteAll()
            db.projectDao().deleteAll()
            db.lessonDao().deleteAll()
            ProjectSeed.seedIfNeeded(ctx)
        }
    }
}

class Converters {
    @TypeConverter fun statusToString(s: NoteStatus) = s.name
    @TypeConverter fun stringToStatus(s: String) = NoteStatus.valueOf(s)
}
