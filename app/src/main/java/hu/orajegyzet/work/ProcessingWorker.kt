package hu.orajegyzet.work

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.*
import hu.orajegyzet.App
import hu.orajegyzet.MainActivity
import hu.orajegyzet.R
import hu.orajegyzet.ai.GeminiPipeline
import hu.orajegyzet.ai.ModeSelector
import hu.orajegyzet.ai.OfflinePipeline
import hu.orajegyzet.ai.SummaryEngine
import hu.orajegyzet.ai.WhisperPrompt
import android.util.Log
import hu.orajegyzet.data.Db
import hu.orajegyzet.data.NoteStatus
import hu.orajegyzet.data.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Egy felvétel feldolgozása: mód kiválasztása (offline/online), pipeline
 * futtatása, eredmény mentése.
 */
class ProcessingWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val n = Notification.Builder(applicationContext, App.CH_REC)
            .setSmallIcon(R.drawable.ic_stat_note)
            .setContentTitle("Jegyzet feldolgozása")
            .setContentText("A felvétel átírása és összefoglalása folyamatban…")
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 34)
            ForegroundInfo(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else
            ForegroundInfo(FG_ID, n)
    }

    override suspend fun doWork(): Result {
        val noteId = inputData.getLong(KEY_NOTE_ID, -1)
        if (noteId < 0) return Result.failure()
        runCatching { setForeground(getForegroundInfo()) }

        val db = Db.get(applicationContext)
        val dao = db.noteDao()
        val note = dao.byId(noteId) ?: return Result.failure()
        val settings = Settings.get(applicationContext)
        val online = ModeSelector.choose(applicationContext, settings)
        val dateLabel = "${note.dateIso} %d:%02d".format(note.startMin / 60, note.startMin % 60)
        val resummarize = inputData.getBoolean(KEY_RESUMMARIZE, false)

        val audio = note.audioPath?.let { File(it) }
        var lastStep: String? = null
        val step: suspend (String) -> Unit = { text ->
            lastStep = text
            runCatching { dao.setProgress(noteId, text) }
        }
        Log.i("FuleloAI", "route=${if (online) "GEMINI model=${settings.geminiModel}" else "OFFLINE"} " +
            "mode=${settings.mode} privacy=${settings.privacyLock} key=${settings.geminiApiKey.isNotBlank()}")

        // ÚJRA-ÖSSZEFOGLALÁS / HANG FELDOLGOZÁS:
        // Ha van leirat, arról dolgozik. Ha nincs leirat, de megvan a hangfájl, átvált teljes hangfeldolgozásra!
        if (resummarize && note.transcript.isNotBlank()) {
            dao.update(note.copy(status = NoteStatus.PROCESSING,
                progress = if (online) "Összefoglaló: Gemini" else "Összefoglaló előkészítése…"))
            return try {
                val pipeline = if (online)
                    GeminiPipeline(settings.geminiApiKey, settings.geminiModel)
                else OfflinePipeline(applicationContext, settings.useGemma,
                    summaryBlockChars = settings.summaryBlockChars, onProgress = step)
                val r = withTimeout(12 * 60_000L) {
                    pipeline.resummarize(note.transcript, note.subject, dateLabel, note.lessonId != null)
                }
                dao.update(note.copy(status = NoteStatus.DONE, summary = r.summary,
                    structured = r.structured, keywords = r.keywords.joinToString(", "), error = "",
                    summaryEngine = r.engine ?: if (online) SummaryEngine.GEMINI else null,
                    summaryWarning = r.warning, progress = null))
                Result.success()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                dao.update(note.copy(status = NoteStatus.DONE, progress = null,
                    error = "Újra-összefoglalás hiba: ${e.message}"))
                Result.failure()
            }
        }

        if (audio == null || !audio.exists()) {
            if (note.transcript.isBlank()) {
                dao.update(note.copy(status = NoteStatus.ERROR, error = "Hiányzó leirat és hangfájl"))
                return Result.failure()
            }
        }

        dao.update(note.copy(status = NoteStatus.PROCESSING,
            progress = if (online) "Leirat és elemzés: Gemini (${settings.geminiModel})" else "Leirat előkészítése (Whisper)…"))

        // 1.1.4: Whisper-szótár (téma + nevek, szakszavak) és pontosabb mód
        val whisperPrompt = WhisperPrompt.build(settings.whisperGlossary, note.subject)
        val transcribeTimeout = (if (settings.whisperAccurate) 60 else 30) * 60_000L

        return try {
            val r = if (online && audio != null && audio.exists()) {
                val res = GeminiPipeline(settings.geminiApiKey, settings.geminiModel)
                    .process(audio.absolutePath, note.subject, dateLabel, note.lessonId != null)

                // Biztonsági ellenőrzés: Ha a Gemini nem adott leiratot, de megvan a helyi hangfájl, futtassuk le a Whisper-t helyben!
                var validTranscript = res.transcript
                if (validTranscript.isBlank()) {
                    runCatching {
                        step("A Gemini nem adott leiratot – leirat helyben (Whisper)")
                        val offline = OfflinePipeline(
                            applicationContext, false, settings.whisperThreads,
                            settings.keepModelWarm, settings.warmMinutes, settings.summaryBlockChars,
                            settings.whisperChunkSec, settings.whisperOverlapSec, step,
                            whisperPrompt = whisperPrompt, whisperAccurate = settings.whisperAccurate
                        )
                        validTranscript = offline.transcribeOnly(audio.absolutePath)
                    }
                }
                res.copy(transcript = validTranscript.ifBlank { note.transcript },
                    engine = res.engine ?: SummaryEngine.GEMINI)
            } else if (audio != null && audio.exists()) {
                val pipeline = OfflinePipeline(
                    applicationContext, settings.useGemma, settings.whisperThreads,
                    settings.keepModelWarm, settings.warmMinutes, settings.summaryBlockChars,
                    settings.whisperChunkSec, settings.whisperOverlapSec, step,
                    whisperPrompt = whisperPrompt, whisperAccurate = settings.whisperAccurate)
                if (settings.stagedDisplay) {
                    val transcript = withTimeout(transcribeTimeout) {
                        pipeline.transcribeOnly(audio.absolutePath) { partial ->
                            dao.update(note.copy(status = NoteStatus.PROCESSING, transcript = partial,
                                processedOnline = false, summary = "(leirat készül…)", error = "",
                                progress = lastStep))
                        }
                    }
                    dao.update(note.copy(status = NoteStatus.PROCESSING, transcript = transcript,
                        processedOnline = false, summary = "(összefoglaló készül…)", error = "",
                        progress = "Összefoglaló előkészítése…"))
                    withTimeout(12 * 60_000L) {
                        pipeline.finishSummary(transcript, note.subject, dateLabel, note.lessonId != null)
                    }
                } else {
                    withTimeout(transcribeTimeout + 12 * 60_000L) {
                        pipeline.process(audio.absolutePath, note.subject, dateLabel, note.lessonId != null)
                    }
                }
            } else {
                throw RuntimeException("Nincs elérhető hangfájl a feldolgozáshoz.")
            }

            dao.update(
                note.copy(
                    status = NoteStatus.DONE,
                    transcript = r.transcript,
                    summary = r.summary,
                    structured = r.structured,
                    keywords = r.keywords.joinToString(", "),
                    processedOnline = online,
                    error = "",
                    summaryEngine = r.engine,
                    summaryWarning = r.warning,
                    progress = null
                )
            )

            // Kinyert döntések és feladatok mentése az aktív projekthez
            val targetProjectId = note.projectId ?: "prj-01"
            r.decisions.forEach { dec ->
                db.projectEventDao().insert(dec.copy(projectId = targetProjectId, sourceMeetingId = note.id.toString()))
            }
            r.tasks.forEach { task ->
                db.ganttTaskDao().insert(task.copy(projectId = targetProjectId))
            }

            if (audio != null && audio.exists()) {
                AudioCleanupWorker.schedule(applicationContext, audio.absolutePath, noteId, settings.audioRetentionMin)
            }
            notifyDone(note.subject, noteId)
            Result.success()
        } catch (e: TimeoutCancellationException) {
            dao.update(note.copy(status = NoteStatus.ERROR, progress = null,
                error = "Offline feldolgozás időtúllépés. Próbáld a kisebb q5 Whisper modellt, vagy kapcsold ki a Gemmát a Beállításokban."))
            Result.failure()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (runAttemptCount < 2) {
                dao.update(note.copy(status = NoteStatus.QUEUED, progress = "Újrapróbálás hamarosan…",
                    error = e.message ?: "ismeretlen hiba"))
                Result.retry()
            } else {
                dao.update(note.copy(status = NoteStatus.ERROR, progress = null, error = e.message ?: "ismeretlen hiba"))
                Result.failure()
            }
        }
    }

    private fun notifyDone(subject: String, noteId: Long) {
        val pi = PendingIntent.getActivity(
            applicationContext, noteId.toInt(),
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(applicationContext, App.CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_note)
            .setContentTitle("Kész a jegyzet: $subject")
            .setContentText("Koppints a megnyitáshoz")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        applicationContext.getSystemService(NotificationManager::class.java)
            .notify(1000 + noteId.toInt(), n)
    }

    companion object {
        const val KEY_NOTE_ID = "noteId"
        const val KEY_RESUMMARIZE = "resummarize"
        const val FG_ID = 4242

        fun resummarize(ctx: Context, noteId: Long) {
            val req = OneTimeWorkRequestBuilder<ProcessingWorker>()
                .setInputData(workDataOf(KEY_NOTE_ID to noteId, KEY_RESUMMARIZE to true))
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork("resummarize_$noteId", ExistingWorkPolicy.REPLACE, req)
        }

        fun reprocessAudio(ctx: Context, noteId: Long) {
            val req = OneTimeWorkRequestBuilder<ProcessingWorker>()
                .setInputData(workDataOf(KEY_NOTE_ID to noteId, KEY_RESUMMARIZE to false))
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork("reprocess_$noteId", ExistingWorkPolicy.REPLACE, req)
        }

        fun enqueue(ctx: Context, noteId: Long) {
            val req = OneTimeWorkRequestBuilder<ProcessingWorker>()
                .setInputData(workDataOf(KEY_NOTE_ID to noteId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork("process_$noteId", ExistingWorkPolicy.KEEP, req)
        }
    }
}
