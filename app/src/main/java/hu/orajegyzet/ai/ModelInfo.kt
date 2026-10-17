package hu.orajegyzet.ai

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Telepített modellek azonosítása és ajánlott letöltések (1.1.4) – tiszta logika, tesztelhető.
 */
object ModelInfo {

    /** A Whisper ggml-fájl fejlécéből kiolvasott adatok. */
    data class Whisper(val size: String, val quant: String, val englishOnly: Boolean) {
        val label: String get() = "$size · $quant" + if (englishOnly) " · CSAK ANGOL!" else ""
    }

    /** A fejléchez szükséges bájtok száma (magic + 11 egész). */
    const val WHISPER_HEADER_BYTES = 48

    /**
     * A whisper.cpp ggml fejléce: magic ("lmgg"), majd n_vocab, n_audio_ctx, n_audio_state, n_audio_head,
     * n_audio_layer, n_text_ctx, n_text_state, n_text_head, n_text_layer, n_mels, ftype (int32, little-endian).
     */
    fun parseWhisperHeader(header: ByteArray): Whisper? {
        if (header.size < WHISPER_HEADER_BYTES) return null
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        if (b.int != 0x67676d6c) return null
        val nVocab = b.int
        b.int // n_audio_ctx
        val audioState = b.int
        b.int // n_audio_head
        b.int // n_audio_layer
        b.int // n_text_ctx
        b.int // n_text_state
        b.int // n_text_head
        val textLayer = b.int
        val mels = b.int
        val ftype = b.int % 1000
        val base = when (audioState) {
            384 -> "tiny"; 512 -> "base"; 768 -> "small"; 1024 -> "medium"; 1280 -> "large"
            else -> "ismeretlen méret"
        }
        val size = when {
            base == "large" && textLayer == 4 -> "large-v3-turbo"
            base == "large" && mels == 128 -> "large-v3"
            base == "large" -> "large-v2"
            else -> base
        }
        val quant = when (ftype) {
            0 -> "f32"; 1 -> "f16"; 2 -> "q4_0"; 3 -> "q4_1"; 7 -> "q8_0"; 8 -> "q5_0"; 9 -> "q5_1"
            else -> "típus $ftype"
        }
        return Whisper(size, quant, englishOnly = nVocab == 51864)
    }

    /** Gemma-változat a mentett eredeti név, ennek hiányában a fájlméret alapján. */
    fun gemmaVariant(originalName: String, sizeBytes: Long): String {
        val n = originalName.lowercase()
        return when {
            "e4b" in n -> "Gemma 4 E4B"
            "e2b" in n -> "Gemma 4 E2B"
            "gemma3" in n || "gemma-3" in n -> "Gemma 3"
            sizeBytes > 3_300_000_000L -> "Gemma 4 E4B (méret alapján)"
            sizeBytes in 2_300_000_000L..2_800_000_000L -> "Gemma 4 E2B (méret alapján)"
            else -> "Gemma"
        }
    }

    fun mb(bytes: Long): String = if (bytes >= 1_000_000_000L) "%.2f GB".format(bytes / 1e9) else "%.0f MB".format(bytes / 1e6)

    data class Download(val title: String, val note: String, val url: String)

    /** Közvetlen letöltési linkek (a telefon böngészője a Letöltések mappába menti). */
    val DOWNLOADS = listOf(
        Download("Whisper large-v3-turbo q5_0", "574 MB · ajánlott",
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin?download=true"),
        Download("Whisper large-v3-turbo q8_0", "874 MB · kicsit pontosabb",
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q8_0.bin?download=true"),
        Download("Gemma 4 E2B", "2,59 GB · gyorsabb",
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm?download=true"),
        Download("Gemma 4 E4B", "3,66 GB · jobb szöveg, lassabb",
            "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm?download=true")
    )
}

/** A Whisper „szótára” (initial prompt): téma + nevek, szakszavak. */
object WhisperPrompt {
    const val MAX_CHARS = 400

    fun build(glossary: String, subject: String = ""): String {
        val terms = glossary.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val parts = mutableListOf<String>()
        if (subject.isNotBlank()) parts += "Téma: ${subject.trim()}."
        if (terms.isNotEmpty()) parts += "Nevek és szakszavak: ${terms.joinToString(", ")}."
        if (parts.isEmpty()) return ""
        return ("Magyar nyelvű megbeszélés. " + parts.joinToString(" ")).take(MAX_CHARS)
    }
}
