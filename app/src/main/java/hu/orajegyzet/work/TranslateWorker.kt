package hu.orajegyzet.work

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.work.*
import hu.orajegyzet.App
import hu.orajegyzet.MainActivity
import hu.orajegyzet.R
import hu.orajegyzet.ai.TrStyle
import hu.orajegyzet.ai.Translator
import hu.orajegyzet.data.Settings
import hu.orajegyzet.data.TrDb
import hu.orajegyzet.data.TrKind
import hu.orajegyzet.data.TrStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Egy fordítás elvégzése a háttérben (Fordító 0.1): szövegnél fordítás/átírás,
 * hangnál átirat + fordítás. Az eredmény a translations táblába kerül; a képernyő ezt figyeli.
 */
class TranslateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val n = Notification.Builder(applicationContext, App.CH_REC)
            .setSmallIcon(R.drawable.ic_stat_note)
            .setContentTitle("Fordítás folyamatban")
            .setContentText("A szöveg fordítása / átírása zajlik…")
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 34)
            ForegroundInfo(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(FG_ID, n)
    }

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_ID, -1)
        if (id < 0) return Result.failure()
        runCatching { setForeground(getForegroundInfo()) }

        val dao = TrDb.get(applicationContext).dao()
        val item = dao.byId(id) ?: return Result.failure()
        val settings = Settings.get(applicationContext)
        val style = TrStyle.of(item.style)
        val step: suspend (String) -> Unit = { text -> runCatching { dao.setProgress(id, text) } }

        dao.update(item.copy(status = TrStatus.PROCESSING, error = "", progress = "Előkészítés…"))
        return try {
            if (item.kind == TrKind.AUDIO) {
                val audio = item.audioPath?.let { File(it) }
                if (audio == null || !audio.exists()) error("A hangfájl már nem érhető el.")
                val r = withTimeout(45 * 60_000L) {
                    Translator.translateAudio(
                        applicationContext, settings, audio.absolutePath,
                        item.audioMime ?: "audio/mp4", style, onProgress = step,
                        onTranscript = { partial ->
                            dao.byId(id)?.let { cur -> dao.update(cur.copy(original = partial)) }
                        }
                    )
                }
                val cur = dao.byId(id) ?: item
                dao.update(cur.copy(status = TrStatus.DONE, original = r.transcript,
                    sourceLang = r.result.sourceLang, translation = r.result.text,
                    engine = r.result.engine, warning = r.result.warning, error = "", progress = null))
            } else {
                val r = withTimeout(20 * 60_000L) {
                    Translator.translateText(applicationContext, settings, item.original, style, step)
                }
                dao.update(item.copy(status = TrStatus.DONE, sourceLang = r.sourceLang,
                    translation = r.text, engine = r.engine, warning = r.warning, error = "", progress = null))
            }
            notifyDone(id)
            Result.success()
        } catch (e: TimeoutCancellationException) {
            fail(id, "Időtúllépés: a fordítás túl sokáig tartott. Próbáld rövidebb szöveggel vagy a Geminivel.")
            Result.failure()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Fordito", "fordítás hiba", e)
            fail(id, e.message ?: e.javaClass.simpleName)
            Result.failure()
        }
    }

    private suspend fun fail(id: Long, msg: String) {
        val dao = TrDb.get(applicationContext).dao()
        dao.byId(id)?.let { dao.update(it.copy(status = TrStatus.ERROR, error = msg, progress = null)) }
    }

    private fun notifyDone(id: Long) {
        val pi = PendingIntent.getActivity(
            applicationContext, id.toInt(),
            Intent(applicationContext, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_TRANSLATION)
                .putExtra(MainActivity.EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(applicationContext, App.CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_note)
            .setContentTitle("Kész a fordítás")
            .setContentText("Koppints a megnyitáshoz")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        applicationContext.getSystemService(NotificationManager::class.java).notify(1000 + id.toInt(), n)
    }

    companion object {
        const val KEY_ID = "trId"
        const val FG_ID = 4343

        fun enqueue(ctx: Context, id: Long) {
            val req = OneTimeWorkRequestBuilder<TranslateWorker>()
                .setInputData(workDataOf(KEY_ID to id))
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("tr_$id", ExistingWorkPolicy.REPLACE, req)
        }

        fun cancel(ctx: Context, id: Long) {
            WorkManager.getInstance(ctx).cancelUniqueWork("tr_$id")
        }
    }
}
