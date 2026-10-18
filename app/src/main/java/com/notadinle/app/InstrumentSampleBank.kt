package com.notadinle.app

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.LinkedHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

internal data class DecodedInstrumentSample(
    val sourceMidi: Int,
    val sampleRate: Int,
    val channels: Int,
    val pcm: ShortArray,
) {
    val frameCount: Int get() = pcm.size / channels

    fun monoFrame(frame: Int): Double {
        val first = frame * channels
        var sum = 0.0
        for (channel in 0 until channels) sum += pcm[first + channel].toDouble() / Short.MAX_VALUE
        return sum / channels
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DecodedInstrumentSample

        if (sourceMidi != other.sourceMidi) return false
        if (sampleRate != other.sampleRate) return false
        if (channels != other.channels) return false
        if (!pcm.contentEquals(other.pcm)) return false
        if (frameCount != other.frameCount) return false

        return true
    }

    override fun hashCode(): Int {
        var result = sourceMidi
        result = 31 * result + sampleRate
        result = 31 * result + channels
        result = 31 * result + pcm.contentHashCode()
        result = 31 * result + frameCount
        return result
    }
}

internal object InstrumentSampleBank {
    private data class CacheKey(val instrument: Instrument, val midi: Int)

    private const val MAX_CACHED_SAMPLES = 18
    private const val CODEC_TIMEOUT_US = 10_000L
    private val cache = object : LinkedHashMap<CacheKey, DecodedInstrumentSample>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, DecodedInstrumentSample>?): Boolean =
            size > MAX_CACHED_SAMPLES
    }

    suspend fun preload(
        context: Context,
        instrument: Instrument,
        notes: List<DetectedNote>,
    ): Map<Int, DecodedInstrumentSample> = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val uniqueMidis = notes.map { it.midi }.distinct()
        val decoderSlots = Semaphore(4)
        coroutineScope {
            uniqueMidis.map { targetMidi ->
                async(Dispatchers.IO) {
                    targetMidi to decoderSlots.withPermit {
                        val sourceMidi = sourceMidiFor(instrument, targetMidi)
                        val key = CacheKey(instrument, sourceMidi)
                        synchronized(cache) { cache[key] } ?: run {
                            val decoded = decodeAsset(appContext, instrument, sourceMidi)
                            synchronized(cache) {
                                cache[key] ?: decoded.also { cache[key] = it }
                            }
                        }
                    }
                }
            }.awaitAll().toMap()
        }
    }

    private fun sourceMidiFor(instrument: Instrument, midi: Int): Int = when (instrument) {
        Instrument.Piano -> midi.coerceIn(36, 96)
        Instrument.Guitar -> midi.coerceIn(40, 84)
    }

    private fun assetPath(instrument: Instrument, midi: Int): String = when (instrument) {
        Instrument.Piano -> "audio/piano/piano_$midi.ogg"
        Instrument.Guitar -> "audio/guitar/guitar_$midi.ogg"
    }

    private fun decodeAsset(context: Context, instrument: Instrument, midi: Int): DecodedInstrumentSample {
        val path = assetPath(instrument, midi)
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            context.assets.openFd(path).use { asset ->
                extractor.setDataSource(asset.fileDescriptor, asset.startOffset, asset.length)
            }

            val audioTrack = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("Ses örneği içinde ses kanalı bulunamadı: $path")
            extractor.selectTrack(audioTrack)
            val inputFormat = extractor.getTrackFormat(audioTrack)
            val mimeType = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: error("Ses örneğinin biçimi okunamadı: $path")
            decoder = MediaCodec.createDecoderByType(mimeType).apply {
                configure(inputFormat, null, null, 0)
                start()
            }

            val encodedPcm = ByteArrayOutputStream()
            val bufferInfo = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var outputFormat: MediaFormat? = null

            while (!outputEnded) {
                if (!inputEnded) {
                    val inputIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)
                            ?: error("Ses çözücü giriş belleği açılamadı: $path")
                        inputBuffer.clear()
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                sampleSize,
                                extractor.sampleTime.coerceAtLeast(0L),
                                0,
                            )
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = decoder.outputFormat
                    else -> if (outputIndex >= 0) {
                        if (bufferInfo.size > 0) {
                            val outputBuffer = decoder.getOutputBuffer(outputIndex)
                                ?: error("Ses çözücü çıkış belleği açılamadı: $path")
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val bytes = ByteArray(bufferInfo.size)
                            outputBuffer.get(bytes)
                            encodedPcm.write(bytes)
                        }
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEnded = true
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }

            val format = outputFormat ?: decoder.outputFormat
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            val pcmEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }
            val pcm = toShortPcm(encodedPcm.toByteArray(), pcmEncoding)
            check(pcm.isNotEmpty() && pcm.size >= channels) { "Ses örneği boş: $path" }
            return DecodedInstrumentSample(midi, sampleRate, channels, pcm)
        } catch (error: Exception) {
            throw IllegalStateException("Akustik enstrüman sesi yüklenemedi ($path): ${error.message}", error)
        } finally {
            runCatching { decoder?.stop() }
            decoder?.release()
            extractor.release()
        }
    }

    private fun toShortPcm(bytes: ByteArray, encoding: Int): ShortArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> ShortArray(bytes.size / 2).also { pcm ->
                buffer.asShortBuffer().get(pcm)
            }
            AudioFormat.ENCODING_PCM_FLOAT -> ShortArray(bytes.size / 4) { index ->
                (buffer.getFloat(index * 4).coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt().toShort()
            }
            AudioFormat.ENCODING_PCM_8BIT -> ShortArray(bytes.size) { index ->
                (((bytes[index].toInt() and 0xff) - 128) shl 8).toShort()
            }
            else -> error("Desteklenmeyen PCM biçimi: $encoding")
        }
    }
}