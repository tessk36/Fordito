package hu.orajegyzet

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import hu.orajegyzet.rec.RecordingService
import hu.orajegyzet.ui.HistoryScreen
import hu.orajegyzet.ui.NoteDetailScreen
import hu.orajegyzet.ui.ProjectAssistantScreen
import hu.orajegyzet.ui.ScheduleScreen
import hu.orajegyzet.ui.SettingsScreen
import hu.orajegyzet.ui.TodayScreen
import hu.orajegyzet.ui.WelcomeScreen

class MainActivity : ComponentActivity() {

    private var pendingLessonId: Long? = null
    private var pendingTitle: String? = null

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) {
            pendingLessonId.let { RecordingService.start(this, it, pendingTitle); pendingLessonId = null; pendingTitle = null }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestBasePermissions()
        handleIntent(intent)

        setContent {
            val settings by hu.orajegyzet.data.Settings.flow(this)
                .collectAsState(initial = hu.orajegyzet.data.AppSettings())
            // A téma az alap elemző szerint vált (1.1.3): Gemma 4 vagy adatvédelmi zár = meleg
            // borostyán (helyben); Gemini = hűvös zöld (felhő).
            val online = settings.preferGemini && !settings.privacyLock

            hu.orajegyzet.ui.OraJegyzetTheme(online = online) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    val nav = rememberNavController()
                    NavHost(navController = nav, startDestination = "welcome") {
                        composable("welcome") {
                            WelcomeScreen(
                                onEnterApp = {
                                    nav.navigate("today") {
                                        popUpTo("welcome") { inclusive = true }
                                    }
                                },
                                onExitApp = { finishAffinity() }
                            )
                        }
                        composable("today") {
                            TodayScreen(
                                onOpenNote = { id -> nav.navigate("note/$id") },
                                onOpenAssistant = { nav.navigate("assistant") },
                                onOpenSchedule = { nav.navigate("schedule") },
                                onOpenSettings = { nav.navigate("settings") },
                                onOpenHistory = { nav.navigate("history") },
                                onStartRecording = { lessonId, title -> startRecordingChecked(lessonId, title) },
                                onStopRecording = { RecordingService.stop(this@MainActivity) }
                            )
                        }
                        composable("assistant") {
                            ProjectAssistantScreen(
                                onBack = { nav.popBackStack() },
                                onOpenNote = { id -> nav.navigate("note/$id") }
                            )
                        }
                        composable("note/{id}") { back ->
                            val id = back.arguments?.getString("id")?.toLongOrNull() ?: return@composable
                            NoteDetailScreen(noteId = id, onBack = { nav.popBackStack() })
                        }
                        composable("schedule") { ScheduleScreen(onBack = { nav.popBackStack() }) }
                        composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
                        composable("history") {
                            HistoryScreen(
                                onOpenNote = { id -> nav.navigate("note/$id") },
                                onBack = { nav.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Becsengetési értesítésből / widgetből érkező indítás. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_START_REC) {
            val lessonId = intent.getLongExtra("lessonId", -1).takeIf { it >= 0 }
            startRecordingChecked(lessonId)
        }
    }

    private fun startRecordingChecked(lessonId: Long?, title: String? = null) {
        val hasMic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (hasMic) {
            RecordingService.start(this, lessonId, title)
        } else {
            pendingLessonId = lessonId
            pendingTitle = title
            permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        }
    }

    private fun requestBasePermissions() {
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = wanted.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
    }

    companion object {
        const val ACTION_START_REC = "hu.orajegyzet.START_REC"
    }
}
