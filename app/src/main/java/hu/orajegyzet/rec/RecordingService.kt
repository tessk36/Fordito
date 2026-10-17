package hu.orajegyzet.rec

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import hu.orajegyzet.App
import hu.orajegyzet.MainActivity
import hu.orajegyzet.R
import hu.orajegyzet.data.Db
import hu.orajegyzet.data.Note
import hu.orajegyzet.data.NoteCodeGenerator
import hu.orajegyzet.data.NoteStatus
import hu.orajegyzet.work.ProcessingWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/**
 * Mikrofonos előtér-szolgáltatás. 16 kHz mono AAC/M4A-t rögzít (~21 MB / 45 perc),
 * ami a Whisper-nek (16 kHz PCM-re dekódolva) és a Gemini-nek is ideális.
 * Indítás: csak felhasználói interakcióból (értesítés/widget/app). Leállás:
 * kicsengetési alarm, kézi stop, vagy biztonsági időkorlát.
 */
class RecordingService : Service() {

    private var recorder: MediaRecorder? = null
    private var noteId: Long = -1
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val safetyStop = Runnable { stopAndProcess() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopAndProcess(); return START_NOT_STICKY }
            ACTION_START -> {
                if (isStarting || isRecordingActive || recorder != null) {
                    return START_NOT_STICKY
                }
                val lessonId = intent.getLongExtra("lessonId", -1).takeIf { it >= 0 }
                val title = intent.getStringExtra("title")
                val projectId = intent.getStringExtra("projectId")
                val docType = intent.getStringExtra("docType") ?: "JEG"
                val projectCode = intent.getStringExtra("projectCode")

                // 1. Azonnali startForeground, hogy elkerüljük az ANR-t és a ForegroundServiceDidNotStartInTimeException-t
                val initialNotif = buildNotification("Felvétel indítása…")
                if (Build.VERSION.SDK_INT >= 30) {
                    startForeground(NOTIF_ID, initialNotif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                } else {
                    startForeground(NOTIF_ID, initialNotif)
                }

                startRecording(lessonId, title, projectId, docType, projectCode)
            }
        }
        return START_NOT_STICKY
    }

    private fun startRecording(
        lessonId: Long?,
        title: String?,
        projectId: String? = null,
        docType: String = "JEG",
        projectCode: String? = null
    ) {
        isStarting = true
        scope.launch(Dispatchers.IO) {
            try {
                val db = Db.get(this@RecordingService)
                val now = LocalTime.now()
                val nowMin = now.hour * 60 + now.minute
                val todayStr = LocalDate.now().toString()

                val l = lessonId?.let { db.lessonDao().byId(it) }
                val subject = (title?.takeIf { it.isNotBlank() } ?: l?.subject ?: "Felvétel")
                val endMin = l?.endMin ?: (nowMin + DEFAULT_MAX_MIN)

                val existingToday = db.noteDao().allOnDate(todayStr)
                val docCode = NoteCodeGenerator.generate(
                    projectCode = projectCode ?: projectId,
                    subject = subject,
                    docType = docType,
                    date = LocalDate.now(),
                    sequence = existingToday.size + 1
                )

                val file = File(filesDir, "rec_${System.currentTimeMillis()}.m4a")

                val newNoteId = db.noteDao().insert(
                    Note(
                        lessonId = lessonId,
                        projectId = projectId,
                        docCode = docCode,
                        docType = docType,
                        subject = subject,
                        dateIso = todayStr,
                        startMin = nowMin, endMin = endMin,
                        status = NoteStatus.RECORDING, audioPath = file.absolutePath
                    )
                )

                withContext(Dispatchers.Main) {
                    noteId = newNoteId
                    isRecordingActive = true
                    recordingStartMillis = System.currentTimeMillis()

                    val mr = (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this@RecordingService) else @Suppress("DEPRECATION") MediaRecorder()).apply {
                        setAudioSource(MediaRecorder.AudioSource.MIC)
                        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                        setAudioSamplingRate(16_000)
                        setAudioChannels(1)
                        setAudioEncodingBitRate(64_000)
                        setOutputFile(file.absolutePath)
                        prepare()
                        start()
                    }
                    recorder = mr

                    val notif = buildNotification(subject)
                    val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(NOTIF_ID, notif)

                    val remainingMin = ((endMin - nowMin).coerceIn(1, 90)) + 2
                    handler.postDelayed(safetyStop, remainingMin * 60_000L)
                }
            } catch (e: Exception) {
                isRecordingActive = false
                val idToFail = noteId
                if (idToFail >= 0) {
                    (applicationContext as App).appScope.launch {
                        val dao = Db.get(this@RecordingService).noteDao()
                        dao.byId(idToFail)?.let { dao.update(it.copy(status = NoteStatus.ERROR, error = "Mikrofon indítási hiba: ${e.message}")) }
                    }
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } finally {
                isStarting = false
            }
        }
    }

    private fun buildNotification(subject: String): Notification {
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, App.CH_REC)
            .setSmallIcon(R.drawable.ic_stat_note)
            .setContentTitle("Felvétel: $subject")
            .setContentText("Kicsengetéskor automatikusan leáll")
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Leállítás", stopPi).build())
            .build()
    }

    private fun stopAndProcess() {
        val active = isRecordingActive
        isRecordingActive = false
        handler.removeCallbacks(safetyStop)
        try { recorder?.stop() } catch (_: Exception) {}
        recorder?.release(); recorder = null
        val id = noteId
        noteId = -1
        val ctx: Context = applicationContext
        (applicationContext as App).appScope.launch {
            if (id >= 0 && active) {
                val dao = Db.get(ctx).noteDao()
                dao.byId(id)?.let { dao.update(it.copy(status = NoteStatus.QUEUED)) }
                ProcessingWorker.enqueue(ctx, id)
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (isRecordingActive) {
            stopAndProcess()
        } else {
            try { recorder?.stop() } catch (_: Exception) {}
            recorder?.release(); recorder = null
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "hu.orajegyzet.REC_START"
        const val ACTION_STOP = "hu.orajegyzet.REC_STOP"
        const val NOTIF_ID = 42
        const val DEFAULT_MAX_MIN = 45

        @Volatile var isStarting = false
        @Volatile var isRecordingActive = false
        @Volatile var recordingStartMillis: Long = 0

        fun start(
            ctx: Context,
            lessonId: Long?,
            title: String? = null,
            projectId: String? = null,
            docType: String = "JEG",
            projectCode: String? = null
        ) {
            val i = Intent(ctx, RecordingService::class.java)
                .setAction(ACTION_START)
            lessonId?.let { i.putExtra("lessonId", it) }
            title?.let { i.putExtra("title", it) }
            projectId?.let { i.putExtra("projectId", it) }
            projectCode?.let { i.putExtra("projectCode", it) }
            i.putExtra("docType", docType)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, RecordingService::class.java).setAction(ACTION_STOP))
        }
    }
}
