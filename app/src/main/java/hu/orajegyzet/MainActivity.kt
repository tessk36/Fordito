package hu.orajegyzet

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
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
import hu.orajegyzet.ui.LiveScreen
import hu.orajegyzet.ui.SettingsScreen
import hu.orajegyzet.ui.SharedInput
import hu.orajegyzet.ui.TranslationDetailScreen
import hu.orajegyzet.ui.TranslationHistoryScreen
import hu.orajegyzet.ui.TranslatorScreen

class MainActivity : ComponentActivity() {

    /** Más appból megosztott szöveg / hangfájl, amíg a Fordító képernyő át nem veszi. */
    private var shared by mutableStateOf<SharedInput?>(null)
    /** Értesítésből megnyitandó fordítás. */
    private var openId by mutableStateOf<Long?>(null)

    private var pendingStyle: String? = null

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) {
            pendingStyle?.let { RecordingService.start(this, it) }
        }
        pendingStyle = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermission()
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            val settings by hu.orajegyzet.data.Settings.flow(this)
                .collectAsState(initial = hu.orajegyzet.data.AppSettings())
            // a téma a motor szerint: helyben = meleg borostyán, Gemini = hűvös zöld
            val online = settings.preferGemini && !settings.privacyLock

            hu.orajegyzet.ui.OraJegyzetTheme(online = online) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    val nav = rememberNavController()
                    LaunchedEffect(openId) {
                        openId?.let { nav.navigate("tr/$it"); openId = null }
                    }
                    NavHost(navController = nav, startDestination = "translator") {
                        composable("translator") {
                            TranslatorScreen(
                                shared = shared,
                                onSharedConsumed = { shared = null },
                                onOpenHistory = { nav.navigate("history") },
                                onOpenSettings = { nav.navigate("settings") },
                                onOpenLive = { nav.navigate("live") },
                                onStartRecording = { style -> startRecordingChecked(style) },
                                onStopRecording = { RecordingService.stop(this@MainActivity) }
                            )
                        }
                        composable("history") {
                            TranslationHistoryScreen(
                                onOpen = { id -> nav.navigate("tr/$id") },
                                onBack = { nav.popBackStack() }
                            )
                        }
                        composable("tr/{id}") { back ->
                            val id = back.arguments?.getString("id")?.toLongOrNull() ?: return@composable
                            TranslationDetailScreen(
                                id = id,
                                onBack = { nav.popBackStack() },
                                onOpen = { newId ->
                                    nav.navigate("tr/$newId") { popUpTo("tr/{id}") { inclusive = true } }
                                },
                                onEditOriginal = { text ->
                                    shared = SharedInput(text = text)
                                    nav.popBackStack("translator", inclusive = false)
                                }
                            )
                        }
                        composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
                        composable("live") {
                            LiveScreen(
                                onBack = { nav.popBackStack() },
                                onOpenTranslation = { id -> nav.navigate("tr/$id") }
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

    /** Megosztás (szöveg / hangfájl) és értesítésből érkező megnyitás. */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val type = intent.type.orEmpty()
                if (type.startsWith("text/")) {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                        ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                    if (!text.isNullOrBlank()) shared = SharedInput(text = text)
                } else if (type.startsWith("audio/") || type.startsWith("video/")) {
                    streamUri(intent)?.let { shared = SharedInput(audio = it) }
                }
            }
            Intent.ACTION_PROCESS_TEXT -> {
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
                    ?.takeIf { it.isNotBlank() }?.let { shared = SharedInput(text = it) }
            }
            ACTION_OPEN_TRANSLATION -> {
                intent.getLongExtra(EXTRA_ID, -1).takeIf { it >= 0 }?.let { openId = it }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun streamUri(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)

    private fun startRecordingChecked(style: String) {
        val hasMic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (hasMic) {
            RecordingService.start(this, style)
        } else {
            pendingStyle = style
            permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
    }

    companion object {
        const val ACTION_OPEN_TRANSLATION = "hu.assisten.fordito.OPEN_TRANSLATION"
        const val EXTRA_ID = "trId"
    }
}
