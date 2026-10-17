package hu.orajegyzet

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import hu.orajegyzet.data.Settings
import hu.orajegyzet.data.TrDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        createChannels()
        appScope.launch {
            runCatching { Settings.migrateSecretsIfNeeded(this@App) }
            runCatching { Settings.migrateProcessingSettingsIfNeeded(this@App) }
            // a régi néven mentett Gemma-fájl átnevezése a rögzített gemma.litertlm névre
            runCatching {
                hu.orajegyzet.ai.GemmaModel.migrateLegacyName(this@App)?.let { orig ->
                    val shown = if (orig in hu.orajegyzet.ai.GemmaModel.LEGACY_NAMES) "Gemma (.litertlm)" else orig
                    Settings.setGemmaModelName(this@App, shown)
                }
            }
            // korábbi futásból „feldolgozás alatt” ragadt fordítások feloldása
            runCatching { TrDb.get(this@App).dao().failStuck() }
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_REC, "Felvétel és fordítás", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Folyamatban lévő felvétel és fordítás"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, "Kész fordítás", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    companion object {
        const val CH_REC = "rec"
        const val CH_DONE = "done"
    }
}
