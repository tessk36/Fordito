package hu.orajegyzet.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecretStorageException(msg: String, cause: Throwable) : Exception(msg, cause)

object SecretCipher {
    private const val ALIAS = "orajegyzet_secrets"
    @Volatile var secretsLost = false

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
            "enc1:" + Base64.encodeToString(out, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e("SecretCipher", "Titkosítás sikertelen", e)
            throw SecretStorageException("A titkos adat biztonságos mentése nem sikerült.", e)
        }
    }

    fun decrypt(stored: String): String {
        if (!stored.startsWith("enc1:")) return stored // régi, titkosítatlan érték
        if (stored.length <= 5) return ""
        return try {
            val raw = Base64.decode(stored.removePrefix("enc1:"), Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
            String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w("SecretCipher", "Visszafejtés sikertelen", e)
            secretsLost = true
            ""
        }
    }
}

enum class Mode { OFFLINE, ONLINE, AUTO }

data class AppSettings(
    val mode: Mode = Mode.AUTO,
    val privacyLock: Boolean = false,      // ha igaz: a hang SOHA nem hagyja el a készüléket
    val wifiOnly: Boolean = true,
    val useGemma: Boolean = true,           // offline összefoglaló a Gemmával (kikapcsolható)
    // --- Teljesítmény / haladó ---
    val whisperThreads: Int = 6,            // Whisper szálszám (SD8Gen3: 6 nagy mag)
    val keepModelWarm: Boolean = true,      // a Whisper modellt ne töltse újra minden felvételnél
    val warmMinutes: Int = 5,               // meddig maradjon "melegen" a modell (perc)
    val stagedDisplay: Boolean = true,      // előbb a leirat, az összefoglaló utólag
    val whisperChunkSec: Int = 30,          // felvétel-darab hossza (mp) – hosszú felvételhez
    val whisperOverlapSec: Int = 2,         // átfedés a darabok közt (mp)
    val summaryBlockChars: Int = 2000,      // Gemma összefoglaló-blokk mérete (karakter)
    val audioRetentionMin: Int = 30,        // a nyers hang megtartása (perc), majd törlés
    val geminiApiKey: String = "",
    val geminiModel: String = "gemini-3.1-flash-lite",
    val email: String = "",
    val smtpHost: String = "smtp.gmail.com",
    val smtpPort: Int = 587,
    val smtpUser: String = "",
    val smtpPass: String = "",              // Gmail esetén alkalmazásjelszó!
    val digestHour: Int = 18,
    val secretsLost: Boolean = false,
    val gemmaModelName: String = "",
    val schoolMode: Boolean = false,        // iskolai mód: órarend, csengetés, widget (1.0.4)
    val preferGemini: Boolean = false,      // alap elemző: false = Gemma 4 helyben, true = Gemini (1.1)
    val whisperGlossary: String = "",       // szótár: nevek, szakszavak a Whispernek (1.1.4)
    val whisperAccurate: Boolean = false,   // pontosabb átírás: beam search (1.1.4)
    val whisperModelName: String = "",      // az importált Whisper-fájl eredeti neve (1.1.4)
    val geminiModelsCache: String = "",     // a kulccsal lekért Gemini-modellek, vesszővel (1.1.6)
    val trStyle: String = "NATURAL",        // Fordító: utoljára választott átírási mód
    val liveModel: String = "gemini-3.5-live-translate-preview", // Fordító 0.2: élő tolmács modell
    val liveSpeak: Boolean = false          // Fordító 0.2: élő tolmács magyar hangja hangosan
)

private val Context.ds by preferencesDataStore("settings")

object Settings {
    private val MODE = stringPreferencesKey("mode")
    private val PRIVACY = booleanPreferencesKey("privacy")
    private val WIFI = booleanPreferencesKey("wifi")
    private val USE_GEMMA = booleanPreferencesKey("use_gemma")
    private val THREADS = intPreferencesKey("threads")
    private val WARM = booleanPreferencesKey("keep_warm")
    private val WARM_MIN = intPreferencesKey("warm_min")
    private val STAGED = booleanPreferencesKey("staged")
    private val CHUNK = intPreferencesKey("chunk_sec")
    private val OVERLAP = intPreferencesKey("overlap_sec")
    private val SUMBLOCK = intPreferencesKey("sum_block")
    private val AUDIORET = intPreferencesKey("audio_ret")
    private val GKEY = stringPreferencesKey("gkey")
    private val GMODEL = stringPreferencesKey("gmodel")
    private val EMAIL = stringPreferencesKey("email")
    private val SHOST = stringPreferencesKey("shost")
    private val SPORT = intPreferencesKey("sport")
    private val SUSER = stringPreferencesKey("suser")
    private val SPASS = stringPreferencesKey("spass")
    private val DHOUR = intPreferencesKey("dhour")
    private val GEMMA_MODEL_NAME = stringPreferencesKey("gemma_model_name")
    private val SCHOOL_MODE = booleanPreferencesKey("school_mode")
    private val PREFER_GEMINI = booleanPreferencesKey("prefer_gemini")
    private val PROC_V113 = booleanPreferencesKey("processing_settings_v113")
    private val WHISPER_GLOSSARY = stringPreferencesKey("whisper_glossary")
    private val WHISPER_ACCURATE = booleanPreferencesKey("whisper_accurate")
    private val WHISPER_MODEL_NAME = stringPreferencesKey("whisper_model_name")
    private val GEMINI_MODELS_CACHE = stringPreferencesKey("gemini_models_cache")
    private val TR_STYLE = stringPreferencesKey("tr_style")
    private val LIVE_MODEL = stringPreferencesKey("live_model")
    private val LIVE_SPEAK = booleanPreferencesKey("live_speak")

    suspend fun migrateSecretsIfNeeded(ctx: Context) {
        try {
            ctx.ds.edit { p ->
                for (key in listOf(GKEY, SPASS)) {
                    val v = p[key] ?: continue
                    if (v.isNotEmpty() && !v.startsWith("enc1:")) {
                        try {
                            val enc = SecretCipher.encrypt(v)
                            if (enc.startsWith("enc1:")) p[key] = enc
                        } catch (_: SecretStorageException) {
                            // Hagyjuk érintetlenül az eredeti értéket, ha nem sikerült a titkosítás
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    fun flow(ctx: Context): Flow<AppSettings> = ctx.ds.data.map { p ->
        val rawModel = p[GMODEL] ?: "gemini-3.1-flash-lite"
        val model = if (rawModel.contains("1.5") || rawModel.contains("2.0") || rawModel.contains("2.5-flash-lite") || rawModel.isBlank()) "gemini-3.1-flash-lite" else rawModel
        val rawGkey = p[GKEY] ?: ""
        val rawSpass = p[SPASS] ?: ""
        val gkey = SecretCipher.decrypt(rawGkey)
        val spass = SecretCipher.decrypt(rawSpass)

        AppSettings(
            mode = p[MODE]?.let { Mode.valueOf(it) } ?: Mode.AUTO,
            privacyLock = p[PRIVACY] ?: false,
            wifiOnly = p[WIFI] ?: true,
            useGemma = p[USE_GEMMA] ?: true,
            whisperThreads = p[THREADS] ?: 6,
            keepModelWarm = p[WARM] ?: true,
            warmMinutes = p[WARM_MIN] ?: 5,
            stagedDisplay = p[STAGED] ?: true,
            whisperChunkSec = p[CHUNK] ?: 30,
            whisperOverlapSec = p[OVERLAP] ?: 2,
            summaryBlockChars = p[SUMBLOCK] ?: 2000,
            audioRetentionMin = p[AUDIORET] ?: 30,
            geminiApiKey = gkey,
            geminiModel = model,
            email = p[EMAIL] ?: "",
            smtpHost = p[SHOST] ?: "smtp.gmail.com",
            smtpPort = p[SPORT] ?: 587,
            smtpUser = p[SUSER] ?: "",
            smtpPass = spass,
            digestHour = p[DHOUR] ?: 18,
            secretsLost = SecretCipher.secretsLost,
            gemmaModelName = p[GEMMA_MODEL_NAME] ?: "",
            schoolMode = p[SCHOOL_MODE] ?: false,
            preferGemini = p[PREFER_GEMINI] ?: false,
            whisperGlossary = p[WHISPER_GLOSSARY] ?: "",
            whisperAccurate = p[WHISPER_ACCURATE] ?: false,
            whisperModelName = p[WHISPER_MODEL_NAME] ?: "",
            geminiModelsCache = p[GEMINI_MODELS_CACHE] ?: "",
            trStyle = p[TR_STYLE] ?: "NATURAL",
            liveModel = p[LIVE_MODEL]?.takeIf { it.isNotBlank() } ?: "gemini-3.5-live-translate-preview",
            liveSpeak = p[LIVE_SPEAK] ?: false
        )
    }

    suspend fun get(ctx: Context): AppSettings = flow(ctx).first()

    /** Csak a Gemma-modell eredeti nevét írja (a titkos mezőkhöz nem nyúl). */
    suspend fun setGemmaModelName(ctx: Context, name: String) {
        ctx.ds.edit { p -> p[GEMMA_MODEL_NAME] = name }
    }

    /** A lekért Gemini-modellek listája (a többi beállításhoz nem nyúl). */
    suspend fun setGeminiModelsCache(ctx: Context, value: String) {
        ctx.ds.edit { p -> p[GEMINI_MODELS_CACHE] = value }
    }

    /** Fordító: az utoljára választott átírási mód (a többi beállításhoz nem nyúl). */
    suspend fun setTrStyle(ctx: Context, style: String) {
        ctx.ds.edit { p -> p[TR_STYLE] = style }
    }

    /** Élő tolmács: a magyar hang hangosan (a többi beállításhoz nem nyúl). */
    suspend fun setLiveSpeak(ctx: Context, value: Boolean) {
        ctx.ds.edit { p -> p[LIVE_SPEAK] = value }
    }

    /** Csak a Whisper-modell eredeti nevét írja (importkor). */
    suspend fun setWhisperModelName(ctx: Context, name: String) {
        ctx.ds.edit { p -> p[WHISPER_MODEL_NAME] = name }
    }

    /**
     * Az iskolai mód kezdőértéke frissítéskor: ha még nincs beállítva, akkor bekapcsolt,
     * ha van felvitt óra, különben kikapcsolt. Visszaadja az érvényes értéket.
     */
    suspend fun initSchoolModeIfMissing(ctx: Context, hasLessons: Boolean): Boolean {
        var result = hasLessons
        ctx.ds.edit { p ->
            val cur = p[SCHOOL_MODE]
            if (cur == null) p[SCHOOL_MODE] = hasLessons else result = cur
        }
        return result
    }

    /**
     * 1.1.3: a „Mód” és a „Gemma 4 használata” kapcsoló megszűnt. Egyszeri átállás:
     *  - Offline mód → alap elemző: Gemma 4 (semmi nem kerül váratlanul felhőbe);
     *  - Online mód → alap elemző: Gemini;
     *  - a mód ezután mindig Automatikus, a Gemma (ha telepítve van) mindig használható.
     */
    suspend fun migrateProcessingSettingsIfNeeded(ctx: Context) {
        ctx.ds.edit { p ->
            if (p[PROC_V113] == true) return@edit
            when (p[MODE]) {
                Mode.OFFLINE.name -> p[PREFER_GEMINI] = false
                Mode.ONLINE.name -> p[PREFER_GEMINI] = true
            }
            p[MODE] = Mode.AUTO.name
            p[USE_GEMMA] = true
            p[PROC_V113] = true
        }
    }

    suspend fun save(ctx: Context, s: AppSettings) {
        var secretError: SecretStorageException? = null
        val encGkey = try { SecretCipher.encrypt(s.geminiApiKey) } catch (e: SecretStorageException) { secretError = e; null }
        val encSpass = try { SecretCipher.encrypt(s.smtpPass) } catch (e: SecretStorageException) { secretError = e; null }

        ctx.ds.edit { p ->
            p[MODE] = s.mode.name; p[PRIVACY] = s.privacyLock; p[WIFI] = s.wifiOnly
            p[USE_GEMMA] = s.useGemma
            p[THREADS] = s.whisperThreads; p[WARM] = s.keepModelWarm; p[WARM_MIN] = s.warmMinutes
            p[STAGED] = s.stagedDisplay; p[CHUNK] = s.whisperChunkSec; p[OVERLAP] = s.whisperOverlapSec
            p[SUMBLOCK] = s.summaryBlockChars; p[AUDIORET] = s.audioRetentionMin
            encGkey?.let { p[GKEY] = it }
            p[GMODEL] = s.geminiModel
            p[EMAIL] = s.email; p[SHOST] = s.smtpHost; p[SPORT] = s.smtpPort
            p[SUSER] = s.smtpUser
            encSpass?.let { p[SPASS] = it }
            p[DHOUR] = s.digestHour
            p[GEMMA_MODEL_NAME] = s.gemmaModelName
            p[SCHOOL_MODE] = s.schoolMode
            p[PREFER_GEMINI] = s.preferGemini
            p[WHISPER_GLOSSARY] = s.whisperGlossary
            p[WHISPER_ACCURATE] = s.whisperAccurate
            p[LIVE_MODEL] = s.liveModel
        }
        if (secretError == null) SecretCipher.secretsLost = false
        secretError?.let { throw it }
    }
}
