package hu.orajegyzet.bell

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import hu.orajegyzet.App
import hu.orajegyzet.MainActivity
import hu.orajegyzet.data.Db
import hu.orajegyzet.data.LessonDao
import hu.orajegyzet.data.Settings
import hu.orajegyzet.widget.LessonWidgetReceiver
import hu.orajegyzet.rec.RecordingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Becsengetéskor értesítést jelenít meg ("Felvétel indítása"), kicsengetéskor
 * leállítja a futó felvételt. Android 14+ miatt a mikrofonos foreground service
 * NEM indítható háttérből, ezért az indítás mindig egy felhasználói koppintás
 * (értesítés vagy widget) — a leállítás viszont automatikus.
 */
object BellScheduler {

    private const val REQ_START_BASE = 10_000
    private const val REQ_STOP_BASE = 20_000

    /**
     * A következő 7 nap összes órájára beállítja a kezdés- és vég-ébresztőket.
     * Kikapcsolt iskolai módban (1.0.4) minden csengetést töröl, és nem ütemez újat.
     */
    suspend fun rescheduleAll(ctx: Context, dao: LessonDao) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val lessons = dao.allOnce()
        if (!Settings.get(ctx).schoolMode) {
            cancelAll(ctx, am, lessons.map { it.id })
            return
        }
        val now = LocalDateTime.now()
        for (l in lessons) {
            // a hét megfelelő napjának következő előfordulása
            var date = LocalDate.now()
            repeat(8) {
                if (date.dayOfWeek.value == l.dayOfWeek) {
                    val start = LocalDateTime.of(date, LocalTime.of(l.startMin / 60, l.startMin % 60))
                    val end = LocalDateTime.of(date, LocalTime.of(l.endMin / 60, l.endMin % 60))
                    if (start.isAfter(now)) {
                        setExact(ctx, am, start, startIntent(ctx, l.id), REQ_START_BASE + l.id.toInt())
                        setExact(ctx, am, end, stopIntent(ctx, l.id), REQ_STOP_BASE + l.id.toInt())
                        return@repeat
                    }
                }
                date = date.plusDays(1)
            }
        }
    }

    private fun cancelAll(ctx: Context, am: AlarmManager, lessonIds: List<Long>) {
        for (id in lessonIds) {
            listOf(
                PendingIntent.getBroadcast(ctx, REQ_START_BASE + id.toInt(), startIntent(ctx, id),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
                PendingIntent.getBroadcast(ctx, REQ_STOP_BASE + id.toInt(), stopIntent(ctx, id),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            ).forEach { pi -> runCatching { am.cancel(pi); pi.cancel() } }
        }
    }

    private fun setExact(ctx: Context, am: AlarmManager, at: LocalDateTime, intent: Intent, req: Int) {
        val pi = PendingIntent.getBroadcast(
            ctx, req, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setWindow(AlarmManager.RTC_WAKEUP, millis, 60_000, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        }
    }

    private fun startIntent(ctx: Context, lessonId: Long) =
        Intent(ctx, BellReceiver::class.java)
            .setAction(BellReceiver.ACTION_BELL_START).putExtra("lessonId", lessonId)

    private fun stopIntent(ctx: Context, lessonId: Long) =
        Intent(ctx, BellReceiver::class.java)
            .setAction(BellReceiver.ACTION_BELL_STOP).putExtra("lessonId", lessonId)
}

class BellReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val lessonId = intent.getLongExtra("lessonId", -1)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                when (intent.action) {
                    ACTION_BELL_START -> if (Settings.get(ctx).schoolMode) showStartNotification(ctx, lessonId)
                    ACTION_BELL_STOP -> {
                        // Futó FGS mellett az app előtérben van, így küldhetünk stop-parancsot.
                        ctx.startService(
                            Intent(ctx, RecordingService::class.java)
                                .setAction(RecordingService.ACTION_STOP)
                        )
                    }
                }
                // a következő heti előfordulás újraütemezése
                BellScheduler.rescheduleAll(ctx, Db.get(ctx).lessonDao())
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun showStartNotification(ctx: Context, lessonId: Long) {
        val lesson = Db.get(ctx).lessonDao().byId(lessonId) ?: return
        val tap = PendingIntent.getActivity(
            ctx, lessonId.toInt(),
            Intent(ctx, MainActivity::class.java)
                .setAction(MainActivity.ACTION_START_REC)
                .putExtra("lessonId", lessonId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val h = lesson.startMin / 60; val m = lesson.startMin % 60
        val n = Notification.Builder(ctx, App.CH_BELL)
            .setSmallIcon(hu.orajegyzet.R.drawable.ic_stat_note)
            .setContentTitle("${lesson.subject} · %d:%02d".format(h, m))
            .setContentText("Koppints a felvétel indításához")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(lessonId.toInt(), n)
    }

    companion object {
        const val ACTION_BELL_START = "hu.orajegyzet.BELL_START"
        const val ACTION_BELL_STOP = "hu.orajegyzet.BELL_STOP"
    }
}

/** Iskolai mód (1.0.4): órarend, csengetés és widget be/ki. Az órarendi adatok megmaradnak. */
object SchoolMode {
    suspend fun apply(ctx: Context) {
        val enabled = Settings.get(ctx).schoolMode
        runCatching {
            ctx.packageManager.setComponentEnabledSetting(
                ComponentName(ctx, LessonWidgetReceiver::class.java),
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }
        BellScheduler.rescheduleAll(ctx, Db.get(ctx).lessonDao())
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                BellScheduler.rescheduleAll(ctx, Db.get(ctx).lessonDao())
            } finally {
                pending.finish()
            }
        }
    }
}
