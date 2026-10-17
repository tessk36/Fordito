package hu.orajegyzet.work

import android.content.Context
import androidx.work.*
import hu.orajegyzet.data.Db
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A feldolgozott felvétel nyers hangját ~30 perc után törli (adatminimalizálás),
 * de addig megmarad visszahallgatásra / mentésre / újra-feldolgozásra.
 */
class AudioCleanupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val path = inputData.getString(KEY_PATH) ?: return Result.success()
        runCatching { File(path).delete() }
        runCatching { Db.get(applicationContext).noteDao().clearAudioPath(path) }
        return Result.success()
    }

    companion object {
        const val KEY_PATH = "path"
        const val KEY_MIN = "min"
        const val DEFAULT_MIN = 30L

        fun schedule(ctx: Context, path: String, noteId: Long, minutes: Int = DEFAULT_MIN.toInt()) {
            val req = OneTimeWorkRequestBuilder<AudioCleanupWorker>()
                .setInputData(workDataOf(KEY_PATH to path))
                .setInitialDelay(minutes.coerceAtLeast(1).toLong(), TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork("cleanup_$noteId", ExistingWorkPolicy.REPLACE, req)
        }

        /** Biztonsági söprés indításkor: a megmaradt rec_*.m4a fájlokat, amelyek
         *  a megadott percnél régebbiek, törli (ha a késleltetett worker kimaradt). */
        fun sweep(ctx: Context, minutes: Int = DEFAULT_MIN.toInt()) {
            val cutoff = System.currentTimeMillis() - minutes.coerceAtLeast(1) * 60_000L
            ctx.filesDir.listFiles { f -> f.name.startsWith("rec_") && f.name.endsWith(".m4a") }
                ?.forEach { f -> if (f.lastModified() < cutoff) runCatching { f.delete() } }
        }
    }
}
