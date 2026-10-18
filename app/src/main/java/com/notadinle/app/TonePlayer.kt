package com.notadinle.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

internal object TonePlayer {
    private val demoNotes = intArrayOf(60, 64, 67, 72) // C4, E4, G4, C5
    private const val SAMPLE_RATE = 44_100
    private const val NOTE_GAP_SECONDS = 0.018

    @OptIn(InternalCoroutinesApi::class)
    suspend fun playSample(instrument: Instrument, tempo: Int) {
        val pcm = withContext(Dispatchers.Default) {
            val beatSamples = (SAMPLE_RATE * 60.0 / tempo.coerceIn(60, 140)).roundToInt()
            val gapSamples = (SAMPLE_RATE * NOTE_GAP_SECONDS).roundToInt()
            val noteSamples = (beatSamples - gapSamples).coerceAtLeast(1)
            val buffer = ShortArray((noteSamples + gapSamples) * demoNotes.size)
            var cursor = 0

            demoNotes.forEach { midi ->
                val frequency = 440.0 * 2.0.pow((midi - 69) / 12.0)
                val note = when (instrument) {
                    Instrument.Piano -> renderPianoNote(frequency, noteSamples)
                    Instrument.Guitar -> renderGuitarNote(midi, frequency, noteSamples)
                }
                writeNoteWithFades(note, buffer, cursor)
                cursor += noteSamples + gapSamples
            }
            buffer
        }
        val track = withContext(Dispatchers.IO) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(pcm.size * Short.SIZE_BYTES)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
                .also { audioTrack ->
                    try {
                        var written = 0
                        while (written < pcm.size) {
                            val count = audioTrack.write(pcm, written, pcm.size - written, AudioTrack.WRITE_BLOCKING)
                            check(count > 0) { "Ses örneği yazılamadı (AudioTrack=$count)" }
                            written += count
                        }
                    } catch (error: Throwable) {
                        audioTrack.release()
                        throw error
                    }
                }
        }

        try {
            suspendCancellableCoroutine { continuation ->
                track.setNotificationMarkerPosition(pcm.size)
                track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(audioTrack: AudioTrack) {
                        continuation.tryResume(Unit)?.let(continuation::completeResume)
                    }

                    override fun onPeriodicNotification(audioTrack: AudioTrack) = Unit
                }, Handler(Looper.getMainLooper()))
                continuation.invokeOnCancellation { runCatching { track.stop() } }
                track.play()
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { track.stop() }
                track.release()
            }
        }
    }

    /** Bright, percussive piano partials with slightly inharmonic upper strings. */
    private fun renderPianoNote(frequency: Double, sampleCount: Int): FloatArray {
        val partialLevels = doubleArrayOf(1.0, 0.54, 0.30, 0.17, 0.095, 0.052)
        val inharmonicity = 0.00018
        val samples = FloatArray(sampleCount)

        for (sample in samples.indices) {
            val t = sample.toDouble() / SAMPLE_RATE
            var value = 0.0
            partialLevels.forEachIndexed { index, level ->
                val partial = index + 1
                val stiffStringRatio = partial * sqrt(1.0 + inharmonicity * partial * partial)
                val partialDecay = 1.15 + partial * 0.72
                value += level * exp(-partialDecay * t) *
                    sin(2.0 * PI * frequency * stiffStringRatio * t)
            }
            val hammerAttack = 1.0 - exp(-t * 185.0)
            samples[sample] = (value * hammerAttack).toFloat()
        }
        return samples
    }

    /** Karplus–Strong plucked-string model for a recognizably different guitar timbre. */
    private fun renderGuitarNote(midi: Int, frequency: Double, sampleCount: Int): FloatArray {
        val delayLength = (SAMPLE_RATE / frequency).roundToInt().coerceAtLeast(2)
        val string = FloatArray(delayLength)
        var seed = midi * 0x45D9F3B
        fun noise(): Double {
            seed = seed xor (seed shl 13)
            seed = seed xor (seed ushr 17)
            seed = seed xor (seed shl 5)
            return (seed ushr 1).toDouble() / Int.MAX_VALUE * 2.0 - 1.0
        }
        string.indices.forEach { string[it] = (noise() * 0.72).toFloat() }

        val samples = FloatArray(sampleCount)
        var index = 0
        for (sample in samples.indices) {
            val nextIndex = if (index + 1 == string.size) 0 else index + 1
            val current = string[index].toDouble()
            val next = string[nextIndex].toDouble()
            string[index] = ((current + next) * 0.5 * 0.999985).toFloat()
            samples[sample] = (current * exp(-(sample.toDouble() / SAMPLE_RATE) * 0.42)).toFloat()
            index = nextIndex
        }
        return samples
    }

    private fun writeNoteWithFades(note: FloatArray, output: ShortArray, offset: Int) {
        val peak = note.maxOfOrNull { abs(it.toDouble()) } ?: 0.0
        val gain = if (peak > 0.000001) 0.70 / peak else 0.0
        val attackSamples = (SAMPLE_RATE * 0.004).roundToInt().coerceAtLeast(1)
        val releaseSamples = (SAMPLE_RATE * 0.024).roundToInt().coerceAtMost(note.size / 3)

        note.indices.forEach { sample ->
            val attack = (sample.toDouble() / attackSamples).coerceAtMost(1.0)
            val release = if (sample >= note.size - releaseSamples) {
                (note.size - sample).toDouble() / releaseSamples
            } else {
                1.0
            }
            val value = (note[sample] * gain * attack * release * Short.MAX_VALUE)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            output[offset + sample] = value.toShort()
        }
    }
}
