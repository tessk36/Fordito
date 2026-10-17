package hu.orajegyzet.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Egy fordítás állapota. */
object TrStatus {
    const val RECORDING = "RECORDING"
    const val QUEUED = "QUEUED"
    const val PROCESSING = "PROCESSING"
    const val DONE = "DONE"
    const val ERROR = "ERROR"
}

/** A bemenet fajtája. */
object TrKind {
    const val TEXT = "TEXT"
    const val AUDIO = "AUDIO"
}

/**
 * Egy fordítás / átírás (Fordító 0.1).
 *  - TEXT: az [original] a beírt / beillesztett / megosztott szöveg.
 *  - AUDIO: az [audioPath] hangból készül az [original] (átirat az eredeti nyelven).
 * A [translation] mindig magyar, a [style] szerinti átírással.
 */
@Entity(tableName = "translations")
data class Translation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val kind: String = TrKind.TEXT,
    val status: String = TrStatus.QUEUED,
    /** [hu.orajegyzet.ai.TrStyle] neve. */
    val style: String = "NATURAL",
    val audioPath: String? = null,
    val audioMime: String? = null,
    /** Eredeti szöveg vagy átirat (az eredeti nyelven). */
    val original: String = "",
    /** A felismert forrásnyelv magyarul (pl. „angol”). */
    val sourceLang: String = "",
    val translation: String = "",
    /** GEMMA | GEMINI */
    val engine: String? = null,
    val warning: String? = null,
    val error: String = "",
    val progress: String? = null
)

@Dao
interface TranslationDao {
    @Query("SELECT * FROM translations ORDER BY createdAt DESC")
    fun all(): Flow<List<Translation>>

    @Query("SELECT * FROM translations WHERE id = :id")
    fun byIdFlow(id: Long): Flow<Translation?>

    @Query("SELECT * FROM translations WHERE id = :id")
    suspend fun byId(id: Long): Translation?

    @Query("UPDATE translations SET progress = :progress WHERE id = :id")
    suspend fun setProgress(id: Long, progress: String?)

    @Query("UPDATE translations SET status = 'ERROR', progress = NULL, error = 'Megszakadt feldolgozás (az app újraindult). Indítsd újra a fordítást.' WHERE status = 'PROCESSING' OR status = 'RECORDING'")
    suspend fun failStuck()

    @Query("DELETE FROM translations")
    suspend fun deleteAll()

    @Insert suspend fun insert(t: Translation): Long
    @Update suspend fun update(t: Translation)
    @Delete suspend fun delete(t: Translation)
}

@Database(entities = [Translation::class], version = 1, exportSchema = true)
abstract class TrDb : RoomDatabase() {
    abstract fun dao(): TranslationDao

    companion object {
        @Volatile private var inst: TrDb? = null
        fun get(ctx: Context): TrDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, TrDb::class.java, "fordito.db")
                .build().also { inst = it }
        }
    }
}
