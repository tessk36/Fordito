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
import hu.orajegyzet.data.TrDb
import hu.orajegyzet.data.TrKind
import hu.orajegyzet.data.TrStatus
import hu.orajegyzet.data.Translation
import hu.orajegyzet.work.TranslateWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Mikrofonos előtér-szolgáltatás (Fordító 0.1). 16 kHz mono AAC/M4A-t rögzít; leállításkor
 * a felvétel a fordítási sorba kerül (TranslateWorker). Biztonsági időkorlát: [MAX_MIN] perc.
 */
class RecordingService : Service() {

    private var recorder: MediaRecorder? = null
    private var trId: Long = -1
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val safetyStop = Runnable { stopAndProcess() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopAndProcess(); return START_NOT_STICKY }
            ACTION_START -> {
                if (isStarting || recorder != null || _active.value != null) return START_NOT_STICKY
                val style = intent.getStringExtra(EXTRA_STYLE) ?: "NATURAL"
                val n = buildNotification("Felvétel indítása…")
                if (Build.VERSION.SDK_INT >= 30) {
                    startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                } else startForeground(NOTIF_ID, n)
                startRecording(style)
            }
        }
        return START_NOT_STICKY
    }

    private fun startRecording(style: String) {
        isStarting = true
        scope.launch {
            var newId = -1L
            try {
                val file = File(filesDir, "rec_${System.currentTimeMillis()}.m4a")
                newId = TrDb.get(this@RecordingService).dao().insert(
                    Translation(kind = TrKind.AUDIO, status = TrStatus.RECORDING, style = style,
                        audioPath = file.absolutePath, audioMime = "audio/mp4")
                )
                withContext(Dispatchers.Main) {
                    val mr = (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this@RecordingService)
                        else @Suppress("DEPRECATION") MediaRecorder()).apply {
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
                    trId = newId
                    startedAt = System.currentTimeMillis()
                    _active.value = newId
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIF_ID, buildNotification("Felvétel folyamatban – a leállítás után fordítok"))
                    handler.postDelayed(safetyStop, MAX_MIN * 60_000L)
                }
            } catch (e: Exception) {
                _active.value = null
                if (newId >= 0) {
                    val dao = TrDb.get(this@RecordingService).dao()
                    dao.byId(newId)?.let {
                        dao.update(it.copy(status = TrStatus.ERROR, error = "Mikrofon indítási hiba: ${e.message}"))
                    }
                }
                withContext(Dispatchers.Main) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            } finally {
                isStarting = false
            }
        }
    }

    private fun buildNotification(text: String): Notification {
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
            .setContentTitle("Fordító – felvétel")
            .setContentText(text)
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Leállítás és fordítás", stopPi).build())
            .build()
    }

    private fun stopAndProcess() {
        handler.removeCallbacks(safetyStop)
        val hadRecorder = recorder != null
        try { recorder?.stop() } catch (_: Exception) {}
        recorder?.release(); recorder = null
        val id = trId
        trId = -1
        _active.value = null
        val ctx: Context = applicationContext
        if (id >= 0 && hadRecorder) {
            (applicationContext as App).appScope.launch {
                val dao = TrDb.get(ctx).dao()
                dao.byId(id)?.let { dao.update(it.copy(status = TrStatus.QUEUED, progress = "Sorban áll…")) }
                TranslateWorker.enqueue(ctx, id)
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (recorder != null) stopAndProcess()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "hu.assisten.fordito.REC_START"
        const val ACTION_STOP = "hu.assisten.fordito.REC_STOP"
        const val EXTRA_STYLE = "style"
        const val NOTIF_ID = 42
        const val MAX_MIN = 60

        @Volatile var isStarting = false
        @Volatile var startedAt: Long = 0

        private val _active = MutableStateFlow<Long?>(null)
        /** A folyamatban lévő felvétel fordítás-azonosítója (null = nincs felvétel). */
        val active: StateFlow<Long?> = _active

        fun start(ctx: Context, style: String) {
            ctx.startForegroundService(
                Intent(ctx, RecordingService::class.java).setAction(ACTION_START).putExtra(EXTRA_STYLE, style)
            )
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, RecordingService::class.java).setAction(ACTION_STOP))
        }
    }
}
