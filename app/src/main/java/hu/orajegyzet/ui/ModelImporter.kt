package hu.orajegyzet.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import hu.orajegyzet.ai.GemmaModel
import hu.orajegyzet.ai.GemmaSession
import hu.orajegyzet.data.Settings
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Offline modellek telepítése a rendszer fájlválasztójával (Storage Access
 * Framework) — adb nélkül. A felhasználó a telefon böngészőjével letölti a
 * fájlt a Letöltések mappába, majd itt importálja.
 *
 *  - Whisper: a kiválasztott ggml .bin bemásolása files/models/whisper.bin néven.
 *  - Gemma:   a kiválasztott .litertlm bemásolása RÖGZÍTETT néven
 *             (files/models/gemma.litertlm), az eredeti név a beállításokba kerül.
 */
object ModelImporter {

    data class Status(
        val gemmaInstalled: Boolean,
        val whisperInstalled: Boolean,
        val gemmaFileName: String? = null,
        val gemmaSizeBytes: Long = 0,
        /** Van modellfájl, de a LiteRT-LM nem tudja futtatni (pl. régi MediaPipe .task vagy sérült fájl). */
        val gemmaInvalidSizeBytes: Long = 0,
        val whisperSizeBytes: Long = 0,
        /** A Whisper-fájl fejlécéből kiolvasott típus (pl. „large-v3-turbo · q5_0”). */
        val whisperInfo: hu.orajegyzet.ai.ModelInfo.Whisper? = null
    )

    data class GemmaTestResult(val answer: String, val loadSeconds: Double, val answerSeconds: Double, val backend: String)

    data class GemmaImportResult(val originalName: String, val sizeBytes: Long, val loadSeconds: Double, val backend: String)

    private fun modelsDir(ctx: Context) = GemmaModel.modelsDir(ctx)

    fun status(ctx: Context): Status {
        val gemma = GemmaModel.file(ctx)
        val whisper = File(modelsDir(ctx), "whisper.bin").takeIf { it.isFile && it.length() > 0 }
        return Status(
            gemmaInstalled = gemma != null,
            whisperInstalled = whisper != null,
            whisperSizeBytes = whisper?.length() ?: 0,
            whisperInfo = whisper?.let { readWhisperInfo(it) },
            gemmaFileName = gemma?.name,
            gemmaSizeBytes = gemma?.length() ?: 0,
            gemmaInvalidSizeBytes = if (gemma == null) GemmaModel.invalidFile(ctx)?.length() ?: 0 else 0
        )
    }

    /** A nem futtatható (érvénytelen) Gemma-fájl törlése. */
    fun deleteInvalidGemma(ctx: Context): Boolean {
        var deleted = false
        while (true) {
            val f = GemmaModel.invalidFile(ctx) ?: break
            if (!f.delete()) break
            deleted = true
        }
        return deleted
    }

    /** Modell teszt: betöltés + egy rövid magyar kérdés, időmérással. Hibánál érthető kivételt dob. */
    fun testGemma(ctx: Context): GemmaTestResult {
        val f = GemmaModel.file(ctx) ?: error("Nincs futtatható Gemma-modell telepítve.")
        try {
            return GemmaSession.open(ctx, f).use { s ->
                val t0 = System.currentTimeMillis()
                val a = s.generate("Foglald össze egy mondatban: A projekt határideje péntek, a felelős Kovács Anna.", 120_000L)
                GemmaTestResult(a, s.loadMillis / 1000.0, (System.currentTimeMillis() - t0) / 1000.0, s.backendName)
            }
        } catch (t: Throwable) {
            throw IllegalStateException("A Gemma nem futott: ${t.message ?: t.javaClass.simpleName}", t)
        }
    }

    private fun readWhisperInfo(f: File): hu.orajegyzet.ai.ModelInfo.Whisper? = runCatching {
        val buf = ByteArray(hu.orajegyzet.ai.ModelInfo.WHISPER_HEADER_BYTES)
        val n = f.inputStream().use { it.read(buf) }
        if (n < buf.size) null else hu.orajegyzet.ai.ModelInfo.parseWhisperHeader(buf)
    }.getOrNull()

    fun importWhisper(ctx: Context, uri: Uri) {
        var originalName = "whisper.bin"
        ctx.contentResolver.query(uri, null, null, null, null)?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) it.getString(idx)?.let { n -> originalName = n }
            }
        }
        val head = ByteArray(hu.orajegyzet.ai.ModelInfo.WHISPER_HEADER_BYTES)
        val got = ctx.contentResolver.openInputStream(uri)?.use { it.read(head) } ?: -1
        check(got == head.size && hu.orajegyzet.ai.ModelInfo.parseWhisperHeader(head) != null) {
            "Ez nem Whisper ggml modell (.bin). A whisper.cpp oldaláról töltsd le, pl. ggml-large-v3-turbo-q5_0.bin."
        }
        val target = File(modelsDir(ctx), "whisper.bin")
        val tmp = File(modelsDir(ctx), "whisper.bin.part")
        ctx.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Nem olvasható a kiválasztott fájl" }
            tmp.outputStream().use { out -> input.copyTo(out, 1 shl 20) }
        }
        check(tmp.length() > 10_000_000) { tmp.delete(); "A fájl gyanúsan kicsi — biztosan Whisper .bin modell?" }
        target.delete(); check(tmp.renameTo(target)) { "Nem sikerült véglegesíteni" }
        runBlocking { Settings.setWhisperModelName(ctx, originalName) }
    }

    /**
     * Gemma importálása. Hibánál kivételt dob, magyar, felhasználónak szóló üzenettel.
     * Siker esetén próbabetöltést is végez (GPU, hiba esetén CPU; rövid prompt).
     */
    fun importGemma(ctx: Context, uri: Uri): GemmaImportResult {
        var originalName = "gemma"
        var declaredSize = -1L
        ctx.contentResolver.query(uri, null, null, null, null)?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) it.getString(idx)?.let { n -> originalName = n }
                val sIdx = it.getColumnIndex(OpenableColumns.SIZE)
                if (sIdx >= 0 && !it.isNull(sIdx)) declaredSize = it.getLong(sIdx)
            }
        }

        val header = ByteArray(8)
        val read = ctx.contentResolver.openInputStream(uri)?.use { it.read(header) } ?: -1
        check(read > 0) { "Nem olvasható a kiválasztott fájl." }
        val format = GemmaModel.detectFormat(header, originalName)
        GemmaModel.rejectionMessage(format, originalName)?.let { error(it) }

        // Szabad hely ellenőrzése (a modell + 200 MB tartalék)
        if (declaredSize > 0) {
            val free = modelsDir(ctx).usableSpace
            check(free > declaredSize + 200_000_000L) {
                "Nincs elég szabad hely: kell kb. %.1f GB, szabad %.1f GB.".format(
                    (declaredSize + 200_000_000L) / 1e9, free / 1e9)
            }
        }

        val target = File(modelsDir(ctx), GemmaModel.MODEL_NAME)
        val tmp = File(modelsDir(ctx), "${GemmaModel.MODEL_NAME}.part")
        tmp.delete()

        try {
            ctx.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Nem olvasható a kiválasztott fájl." }
                tmp.outputStream().use { out -> input.copyTo(out, 1 shl 20) }
            }
        } catch (t: Throwable) {
            tmp.delete()
            throw IllegalStateException("A fájl másolása megszakadt (${t.message ?: t.javaClass.simpleName}). " +
                "Ha a fájl felhőben vagy egy másik appban van, előbb töltsd le a telefonra, " +
                "és a Fájlok → Letöltések mappából válaszd ki.", t)
        }
        if (tmp.length() <= GemmaModel.MIN_SIZE_BYTES) {
            tmp.delete()
            error("A fájl gyanúsan kicsi — biztosan Gemma .litertlm modell?")
        }
        if (declaredSize > 0 && tmp.length() != declaredSize) {
            tmp.delete()
            error("A fájl csak részben másolódott át (${tmp.length()} / $declaredSize bájt). Próbáld újra.")
        }

        // A korábbi Gemma-modellek (rögzített, régi MediaPipe és eredeti nevű) eltávolítása
        modelsDir(ctx).listFiles()?.filter {
            it.isFile && it != tmp && (it.name == GemmaModel.MODEL_NAME || it.name in GemmaModel.LEGACY_NAMES ||
                it.name.lowercase().startsWith("gemma"))
        }?.forEach { it.delete() }

        check(tmp.renameTo(target)) { tmp.delete(); "Nem sikerült véglegesíteni a modellfájlt." }

        // Próbabetöltés: ha nem sikerül, a fájl törlődik, és a hiba megjelenik.
        val probe = try {
            GemmaSession.open(ctx, target).use { s ->
                s.generate("Válaszolj egy szóval: OK", timeoutMs = 120_000L)
                Pair(s.loadMillis / 1000.0, s.backendName)
            }
        } catch (t: Throwable) {
            target.delete()
            throw IllegalStateException("A modell bemásolódott, de a LiteRT-LM motor nem tudja betölteni, ezért töröltem. " +
                "Ok: ${t.message ?: t.javaClass.simpleName}", t)
        }

        runBlocking { Settings.setGemmaModelName(ctx, originalName) }
        return GemmaImportResult(originalName, target.length(), probe.first, probe.second)
    }

    /** Régi (1.0.3 előtti) Vosk-modell törlése: a Vosk 1.0.4-től nincs az appban. */
    fun deleteLegacyVosk(ctx: Context): Boolean {
        val dirs = listOf(File(modelsDir(ctx), "vosk-hu"), File(modelsDir(ctx), "vosk-hu.part"))
        var deleted = false
        dirs.filter { it.exists() }.forEach { deleted = it.deleteRecursively() || deleted }
        return deleted
    }
}
