package hu.orajegyzet.rec

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log

/**
 * Élő mikrofon (Fordító 0.2): 16 kHz mono 16 bites PCM, 100 ms-os darabokban (3200 bájt).
 * Saját szálon olvas; a darabokat az [onChunk] kapja. Hangos visszajátszásnál a
 * VOICE_COMMUNICATION forrás + visszhangszűrő kell, hogy a hangszóró ne kerüljön vissza a mikrofonba.
 */
class MicStream(
    private val echoCancel: Boolean,
    private val onChunk: (ByteArray) -> Unit,
    private val onError: (String) -> Unit
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission") // a hívó ellenőrzi a RECORD_AUDIO engedélyt
    fun start() {
        if (running) return
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val source = if (echoCancel) MediaRecorder.AudioSource.VOICE_COMMUNICATION
            else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val rec = try {
            AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, CHUNK_BYTES * 4))
        } catch (e: Exception) {
            onError("A mikrofon nem indítható: ${e.message}"); return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release(); onError("A mikrofon nem érhető el (lehet, hogy másik app használja)."); return
        }
        val aec = if (echoCancel && AcousticEchoCanceler.isAvailable())
            runCatching { AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
        val ns = if (NoiseSuppressor.isAvailable())
            runCatching { NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true } }.getOrNull() else null

        running = true
        thread = Thread({
            try {
                rec.startRecording()
                val buf = ByteArray(CHUNK_BYTES)
                while (running) {
                    var off = 0
                    while (running && off < buf.size) {
                        val n = rec.read(buf, off, buf.size - off)
                        if (n < 0) { onError("Mikrofon olvasási hiba ($n)."); running = false; break }
                        off += n
                    }
                    if (off == buf.size) onChunk(buf.copyOf())
                }
            } catch (e: Exception) {
                Log.w("Fordito", "mic", e)
                onError("Mikrofon hiba: ${e.message}")
            } finally {
                runCatching { rec.stop() }
                rec.release()
                runCatching { aec?.release() }
                runCatching { ns?.release() }
            }
        }, "fordito-mic").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    companion object {
        const val RATE = 16_000
        /** 100 ms = 1600 minta = 3200 bájt. */
        const val CHUNK_BYTES = 3200

        /** Hangerő (RMS) a kijelzőhöz. */
        fun levelOf(pcm: ByteArray): Double = hu.orajegyzet.ai.SpeechSegmenter.rms(pcm)
    }
}
