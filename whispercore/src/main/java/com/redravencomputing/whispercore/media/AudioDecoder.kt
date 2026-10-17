package com.redravencomputing.whispercore.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun interface AudioDecoder {
	/**
	 * Decodes an audio file.
	 * @param file The audio file to decode.
	 * @return FloatArray of PCM audio data.
	 * @throws Exception if decoding fails.
	 */
	fun decode(file: File): FloatArray
}

internal class DefaultAudioDecoder : AudioDecoder {
	companion object {
		private const val TAG = "DefaultAudioDecoder"
	}

	override fun decode(file: File): FloatArray {
		Log.d(TAG, "Attempting to decode: ${file.absolutePath}")
		if (!file.exists() || file.length() == 0L) {
			throw IOException("Input file does not exist or is empty: ${file.name}")
		}

		// 1) Ha RIFF WAV fájl, beolvassuk közvetlenül a WAV fejléc utáni PCM adatot
		if (isWavFile(file)) {
			return decodeWavDirect(file)
		}

		// 2) Különben MediaExtractor + MediaCodec dekóderrel alakítjuk PCM-mé
		return decodeWithMediaCodec(file)
	}

	private fun isWavFile(file: File): Boolean {
		if (file.length() < 12) return false
		try {
			FileInputStream(file).use { fis ->
				val header = ByteArray(12)
				val read = fis.read(header)
				if (read == 12) {
					val isRiff = header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() &&
							header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte()
					val isWave = header[8] == 'W'.code.toByte() && header[9] == 'A'.code.toByte() &&
							header[10] == 'V'.code.toByte() && header[11] == 'E'.code.toByte()
					return isRiff && isWave
				}
			}
		} catch (_: Exception) {}
		return false
	}

	private fun decodeWavDirect(file: File): FloatArray {
		FileInputStream(file).use { fis ->
			val bytes = fis.readBytes()
			var dataOffset = 12
			while (dataOffset + 8 <= bytes.size) {
				val chunkId = String(bytes, dataOffset, 4, Charsets.US_ASCII)
				val chunkSize = ByteBuffer.wrap(bytes, dataOffset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
				if (chunkId == "data") {
					dataOffset += 8
					val dataLen = minOf(chunkSize, bytes.size - dataOffset)
					val sampleCount = dataLen / 2
					val out = FloatArray(sampleCount)
					val bb = ByteBuffer.wrap(bytes, dataOffset, dataLen).order(ByteOrder.LITTLE_ENDIAN)
					for (i in 0 until sampleCount) {
						out[i] = bb.short / 32768.0f
					}
					Log.d(TAG, "Direct WAV decoded: $sampleCount float samples.")
					return out
				}
				dataOffset += 8 + chunkSize
			}
			throw IOException("No 'data' chunk found in WAV file ${file.name}")
		}
	}

	private fun decodeWithMediaCodec(file: File): FloatArray {
		val extractor = MediaExtractor()
		var codec: MediaCodec? = null
		val pcmStream = ByteArrayOutputStream(1 shl 20)
		try {
			extractor.setDataSource(file.absolutePath)
			var trackIndex = -1
			var inputFormat: MediaFormat? = null

			for (i in 0 until extractor.trackCount) {
				val format = extractor.getTrackFormat(i)
				val mime = format.getString(MediaFormat.KEY_MIME)
				if (mime?.startsWith("audio/") == true) {
					trackIndex = i
					inputFormat = format
					break
				}
			}

			if (trackIndex == -1 || inputFormat == null) {
				throw IOException("No audio track found in ${file.name}")
			}

			extractor.selectTrack(trackIndex)
			val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
			codec = MediaCodec.createDecoderByType(mime)
			codec.configure(inputFormat, null, null, 0)
			codec.start()

			val info = MediaCodec.BufferInfo()
			var inputDone = false
			var outputDone = false

			while (!outputDone) {
				if (!inputDone) {
					val inIdx = codec.dequeueInputBuffer(10_000)
					if (inIdx >= 0) {
						val buf = codec.getInputBuffer(inIdx)!!
						val size = extractor.readSampleData(buf, 0)
						if (size < 0) {
							codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
							inputDone = true
						} else {
							codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
							extractor.advance()
						}
					}
				}

				val outIdx = codec.dequeueOutputBuffer(info, 10_000)
				if (outIdx >= 0) {
					val outBuf = codec.getOutputBuffer(outIdx)!!
					if (info.size > 0) {
						val bytes = ByteArray(info.size)
						outBuf.get(bytes)
						pcmStream.write(bytes)
					}
					codec.releaseOutputBuffer(outIdx, false)
					if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
						outputDone = true
					}
				}
			}
		} finally {
			runCatching { codec?.stop() }
			runCatching { codec?.release() }
			runCatching { extractor.release() }
		}

		val bytes = pcmStream.toByteArray()
		val sampleCount = bytes.size / 2
		if (sampleCount == 0) {
			throw IOException("No audio data decoded from ${file.name}")
		}
		val out = FloatArray(sampleCount)
		val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
		for (i in 0 until sampleCount) {
			out[i] = bb.short / 32768.0f
		}
		Log.d(TAG, "MediaCodec decoded $sampleCount float samples.")
		return out
	}
}