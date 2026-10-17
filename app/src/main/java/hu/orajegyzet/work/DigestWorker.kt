package hu.orajegyzet.work

import android.content.Context
import androidx.work.*
import hu.orajegyzet.data.Db
import hu.orajegyzet.data.Settings
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/**
 * Naponta egyszer (beállított óra körül) összegyűjti a nap kész jegyzeteit,
 * kivonatot állít össze és SMTP-vel elküldi a felhasználó email-címére.
 * Gmail esetén alkalmazásjelszó kell (Google-fiók → Biztonság → Alkalmazásjelszavak).
 */
class DigestWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val s = Settings.get(applicationContext)
        if (s.email.isBlank() || s.smtpUser.isBlank() || s.smtpPass.isBlank()) return Result.success()

        val today = LocalDate.now().toString()
        val notes = Db.get(applicationContext).noteDao().doneOn(today)
        if (notes.isEmpty()) return Result.success()

        val body = buildString {
            appendLine("Napi órajegyzet-kivonat — $today")
            appendLine("=".repeat(40))
            for (n in notes) {
                appendLine()
                appendLine("■ ${n.subject} (%d:%02d–%d:%02d)".format(
                    n.startMin / 60, n.startMin % 60, n.endMin / 60, n.endMin % 60))
                appendLine("-".repeat(40))
                appendLine(n.summary.ifBlank { "(nincs összefoglaló)" })
                if (n.keywords.isNotBlank()) appendLine("\nKulcsfogalmak: ${n.keywords}")
            }
            appendLine()
            appendLine("— ÓraJegyzet · a teljes jegyzetek az alkalmazásban olvashatók")
        }

        return try {
            sendMail(s.smtpHost, s.smtpPort, s.smtpUser, s.smtpPass, s.email,
                "Napi órajegyzet — $today (${notes.size} óra)", body)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private fun sendMail(host: String, port: Int, user: String, pass: String,
                         to: String, subject: String, body: String) {
        val props = Properties().apply {
            put("mail.smtp.host", host)
            put("mail.smtp.port", port.toString())
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.starttls.required", "true")
            if (port == 465) {
                put("mail.smtp.ssl.enable", "true")
                put("mail.smtp.socketFactory.port", "465")
                put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
            }
        }
        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() = PasswordAuthentication(user, pass)
        })
        val msg = MimeMessage(session).apply {
            setFrom(InternetAddress(user))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
            setSubject(subject, "UTF-8")
            setText(body, "UTF-8")
        }
        Transport.send(msg)
    }

    companion object {
        fun schedule(ctx: Context) {
            val s = kotlinx.coroutines.runBlocking { Settings.get(ctx) }
            val now = LocalDateTime.now()
            var next = LocalDateTime.of(LocalDate.now(), LocalTime.of(s.digestHour, 0))
            if (!next.isAfter(now)) next = next.plusDays(1)
            val delay = Duration.between(now, next)

            val req = PeriodicWorkRequestBuilder<DigestWorker>(Duration.ofDays(1))
                .setInitialDelay(delay)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                "daily_digest", ExistingPeriodicWorkPolicy.UPDATE, req
            )
        }
    }
}
