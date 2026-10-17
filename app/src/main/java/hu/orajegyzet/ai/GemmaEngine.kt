package hu.orajegyzet.ai

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * A Gemma-modellfájl egyetlen, közös kezelése (1.1: LiteRT-LM).
 *
 * A modell MINDIG rögzített néven van: files/models/gemma.litertlm
 * (fejléc: „LITERTLM"). Az eredeti fájlnév csak a beállításokban (gemmaModelName) tárolódik.
 * Ajánlott: gemma-4-E2B-it.litertlm (2,59 GB) a Hugging Face litert-community oldaláról.
 */
object GemmaModel {

    enum class Format { LITERTLM, TASK, TFLITE, GGML, UNKNOWN }

    const val MIN_SIZE_BYTES = 1_000_000L
    const val MODEL_NAME = "gemma.litertlm"
    /** Régi (1.0.x, MediaPipe) rögzített nevek – migrációhoz és takarításhoz. */
    val LEGACY_NAMES = setOf("gemma.task", "gemma.bin")

    fun modelsDir(ctx: Context): File = File(ctx.filesDir, "models").apply { mkdirs() }

    /** A telepített, LiteRT-LM által futtatható Gemma-modell, vagy null. */
    fun file(ctx: Context): File? =
        File(modelsDir(ctx), MODEL_NAME).takeIf { it.isFile && it.length() > MIN_SIZE_BYTES && isRunnable(it) }

    /**
     * Ott lévő, de NEM futtatható Gemma-fájl (pl. régi MediaPipe .task, vagy sérült fájl), vagy null.
     * Csak akkor jelez, ha nincs futtatható modell.
     */
    fun invalidFile(ctx: Context): File? {
        if (file(ctx) != null) return null
        val dir = modelsDir(ctx)
        return (listOf(MODEL_NAME) + LEGACY_NAMES).map { File(dir, it) }
            .firstOrNull { it.isFile && it.length() > MIN_SIZE_BYTES }
    }

    private fun isRunnable(f: File): Boolean =
        runCatching { detectFormat(readHeader(f)) }.getOrDefault(Format.UNKNOWN) == Format.LITERTLM

    /** Fejléc (első 8 bájt) + opcionálisan a fájlnév alapján felismert formátum. Tiszta függvény (unit teszt). */
    fun detectFormat(header: ByteArray, fileName: String? = null): Format {
        if (header.size >= 8 && String(header, 0, 8, Charsets.ISO_8859_1) == "LITERTLM") return Format.LITERTLM
        if (header.size >= 4) {
            if (header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
                header[2] == 0x03.toByte() && header[3] == 0x04.toByte()) return Format.TASK
            val magic = String(header, 0, 4, Charsets.ISO_8859_1)
            if (magic in setOf("lmgg", "ggml", "ggjt", "ggmf")) return Format.GGML
        }
        if (header.size >= 8 && header[4] == 'T'.code.toByte() && header[5] == 'F'.code.toByte() &&
            header[6] == 'L'.code.toByte() && header[7] == '3'.code.toByte()) return Format.TFLITE
        // Csak a fejléc dönt; a fájlnév a hibaüzenet pontosításához kell.
        return Format.UNKNOWN
    }

    fun readHeader(f: File): ByteArray {
        val b = ByteArray(8)
        FileInputStream(f).use { it.read(b) }
        return b
    }

    /** Felhasználónak szóló hibaüzenet a nem elfogadható formátumokra (null = elfogadható). */
    fun rejectionMessage(format: Format, fileName: String? = null): String? = when (format) {
        Format.LITERTLM -> null
        Format.TASK, Format.TFLITE -> "Ez régi MediaPipe-formátumú (.task / .bin) Gemma-modell. Az AssiSten-s 1.1-től " +
            "a LiteRT-LM motor fut, ehhez .litertlm fájl kell – ajánlott: gemma-4-E2B-it.litertlm " +
            "(kisebb telefonra: gemma3-1b-it-int4.litertlm)."
        Format.GGML -> "Ez Whisper beszédfelismerő modell, nem Gemma. A Whisper importálásánál töltsd be."
        Format.UNKNOWN ->
            if (fileName?.lowercase()?.endsWith(".litertlm") == true)
                "A fájl neve .litertlm, de a tartalma nem az (hiányzik a „LITERTLM” fejléc). Valószínűleg " +
                    "félbeszakadt a letöltés – töltsd le újra a telefonra, és a Letöltések mappából válaszd ki."
            else "Ez nem Gemma-modell. Ide .litertlm fájl kell (pl. gemma-4-E2B-it.litertlm)."
    }

    /**
     * Régi helyen/néven lévő, de valójában LiteRT-LM tartalmú fájl átnevezése a rögzített névre.
     * Pl. az 1.0.0–1.0.2 a Gemma 4 .litertlm fájlt „gemma.task" néven mentette, az 1.0.3 az eredeti néven.
     * Visszaadja az átnevezett fájl korábbi nevét, ha volt ilyen.
     */
    fun migrateLegacyName(ctx: Context): String? {
        if (file(ctx) != null) return null
        val dir = modelsDir(ctx)
        val candidates = dir.listFiles()?.filter {
            it.isFile && it.length() > MIN_SIZE_BYTES && !it.name.endsWith(".part") &&
                it.name != "whisper.bin" && it.name != MODEL_NAME
        } ?: return null
        for (f in candidates) {
            val fmt = runCatching { detectFormat(readHeader(f)) }.getOrDefault(Format.UNKNOWN)
            if (fmt != Format.LITERTLM) continue
            val target = File(dir, MODEL_NAME)
            target.delete()
            if (f.renameTo(target)) {
                Log.i("FuleloAI", "Gemma-modell átnevezve: ${f.name} → ${target.name}")
                return f.name
            }
        }
        return null
    }
}

class GemmaTimeoutException(msg: String) : RuntimeException(msg)

/**
 * Egy betöltött Gemma-példány (LiteRT-LM Engine). Egyszer töltődik be, és több generálásra is
 * használható (pl. blokkos összefoglalónál). Minden natív hívás egy saját szálon fut, így
 * időkorlát alkalmazható; időtúllépés után a példány „elhagyott", és a lezárás csak a futó
 * generálás végén történik meg (nem zárjuk le alóla a natív erőforrást).
 */
class GemmaSession private constructor(
    private val engine: Engine,
    private val executor: ExecutorService,
    val loadMillis: Long,
    /** A ténylegesen használt gyorsító: „GPU" vagy „CPU". */
    val backendName: String
) : Closeable {

    @Volatile private var abandoned = false

    fun generate(instruction: String, timeoutMs: Long = 120_000L): String {
        if (abandoned) throw GemmaTimeoutException("A Gemma egy korábbi időtúllépés miatt nem használható, próbáld újra.")
        val future = executor.submit(Callable { generateBlocking(instruction) })
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            abandoned = true
            throw GemmaTimeoutException("A Gemma ${timeoutMs / 1000} mp alatt nem válaszolt (időtúllépés).")
        } catch (e: ExecutionException) {
            val c = e.cause
            throw if (c is Exception) c else RuntimeException(c?.message ?: c?.javaClass?.simpleName, c)
        }
    }

    private fun generateBlocking(instruction: String): String {
        val config = ConversationConfig(
            systemInstruction = Contents.of(SYSTEM_INSTRUCTION),
            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.3)
        )
        return engine.createConversation(config).use { conversation ->
            conversation.sendMessage(instruction).toString().trim()
        }
    }

    override fun close() {
        // A sorba állított lezárás csak a (esetleg még futó) generálás után fut le.
        runCatching { executor.execute { runCatching { engine.close() } } }
        executor.shutdown()
    }

    companion object {
        /**
         * Tokenkeret (bemenet + válasz együtt), az EngineConfig.maxNumTokens értéke.
         * A Google AI Edge Gallery is 4000-et használ a Gemma 4 E2B-hez.
         */
        const val MAX_TOKENS = 4000

        const val SYSTEM_INSTRUCTION =
            "Magyar nyelvű, tárgyilagos hivatali asszisztens vagy. Kizárólag a kapott szövegben szereplő " +
                "tényeket használod, nem találsz ki nevet, dátumot vagy számot. Bevezető és megjegyzés nélkül válaszolsz."

        /** Durva tokenbecslés magyar szövegre: kb. 4 karakter / token. */
        fun estimateTokens(text: String): Int = (text.length + 3) / 4

        /**
         * Betöltés: először GPU-n, hiba esetén CPU-n. Az initialize() akár 10–30 mp is lehet,
         * ezért háttérszálon, időkorláttal fut.
         */
        fun open(ctx: Context, file: File, timeoutMs: Long = 180_000L, preferGpu: Boolean = true): GemmaSession {
            val order = if (preferGpu) listOf("GPU", "CPU") else listOf("CPU")
            var lastError: Throwable? = null
            for (backendName in order) {
                val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "gemma").apply { isDaemon = true } }
                val started = System.currentTimeMillis()
                val future = executor.submit(Callable {
                    val backend: Backend = if (backendName == "GPU") Backend.GPU() else Backend.CPU()
                    val engine = Engine(
                        EngineConfig(
                            modelPath = file.absolutePath,
                            backend = backend,
                            maxNumTokens = MAX_TOKENS,
                            cacheDir = ctx.cacheDir.absolutePath
                        )
                    )
                    try {
                        engine.initialize()
                        engine
                    } catch (t: Throwable) {
                        runCatching { engine.close() }
                        throw t
                    }
                })
                try {
                    val engine = future.get(timeoutMs, TimeUnit.MILLISECONDS)
                    Log.i("FuleloAI", "Gemma betöltve: backend=$backendName, ${System.currentTimeMillis() - started} ms")
                    return GemmaSession(engine, executor, System.currentTimeMillis() - started, backendName)
                } catch (e: TimeoutException) {
                    executor.shutdown()
                    throw GemmaTimeoutException("A Gemma-modell betöltése ${timeoutMs / 1000} mp alatt nem fejeződött be.")
                } catch (e: ExecutionException) {
                    executor.shutdown()
                    lastError = e.cause ?: e
                    Log.w("FuleloAI", "Gemma betöltés sikertelen ($backendName): ${lastError.message}")
                }
            }
            throw RuntimeException("A Gemma-modell nem tölthető be: " +
                (lastError?.message ?: lastError?.javaClass?.simpleName ?: "ismeretlen hiba"), lastError)
        }
    }
}
