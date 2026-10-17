package hu.orajegyzet

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import hu.orajegyzet.data.Db
import hu.orajegyzet.data.ProjectSeed
import hu.orajegyzet.data.Settings
import hu.orajegyzet.work.DigestWorker
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
            // 1.1.3: Mód + Gemma-kapcsoló megszűnt → egyszeri átállás az alap elemzőre
            runCatching { Settings.migrateProcessingSettingsIfNeeded(this@App) }
            ProjectSeed.seedIfNeeded(this@App)
            // 1.0.4: iskolai mód kezdőértéke (frissítéskor: be, ha van felvitt óra), majd alkalmazása
            runCatching {
                val hasLessons = Db.get(this@App).lessonDao().allOnce().isNotEmpty()
                Settings.initSchoolModeIfMissing(this@App, hasLessons)
            }
            runCatching { hu.orajegyzet.bell.SchoolMode.apply(this@App) }
            // 1.1: a régi néven (gemma.task / eredeti név) mentett, de valójában .litertlm Gemma
            // átnevezése a rögzített gemma.litertlm névre (pl. a korábban betöltött Gemma 4 fájl)
            runCatching {
                hu.orajegyzet.ai.GemmaModel.migrateLegacyName(this@App)?.let { orig ->
                    val shown = if (orig in hu.orajegyzet.ai.GemmaModel.LEGACY_NAMES) "Gemma (.litertlm)" else orig
                    Settings.setGemmaModelName(this@App, shown)
                }
            }
            // 1.0.4: a Vosk kikerült – a régi modell törlése (helyfelszabadítás)
            runCatching {
                if (hu.orajegyzet.ui.ModelImporter.deleteLegacyVosk(this@App))
                    android.util.Log.i("FuleloAI", "régi Vosk-modell törölve")
            }
            DigestWorker.schedule(this@App)
            // korábbi futásból "feldolgozás alatt" ragadt jegyzetek feloldása
            runCatching { Db.get(this@App).noteDao().failStuckProcessing() }
            // régi nyers hangfájlok törlése a beállított megtartási idő szerint
            runCatching {
                val min = hu.orajegyzet.data.Settings.get(this@App).audioRetentionMin
                hu.orajegyzet.work.AudioCleanupWorker.sweep(this@App, min)
            }
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_BELL, "Becsengetés", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Értesítés óra kezdetekor a felvétel indításához"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_REC, "Felvétel", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Folyamatban lévő felvétel és feldolgozás"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, "Kész jegyzet", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    companion object {
        const val CH_BELL = "bell"
        const val CH_REC = "rec"
        const val CH_DONE = "done"
    }
}
