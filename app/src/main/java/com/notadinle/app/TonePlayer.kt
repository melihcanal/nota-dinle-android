package com.notadinle.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

internal object TonePlayer {
    private val demoNotes = intArrayOf(60, 64, 67, 72) // C4, E4, G4, C5

    suspend fun playSample(instrument: Instrument, tempo: Int) {
        val sampleRate = 22_050
        val beatSeconds = 60.0 / tempo.coerceIn(60, 140)
        val noteSamples = (sampleRate * beatSeconds * 0.76).roundToInt()
        val gapSamples = (sampleRate * beatSeconds * 0.24).roundToInt()
        val pcm = ShortArray((noteSamples + gapSamples) * demoNotes.size)
        var cursor = 0

        demoNotes.forEach { midi ->
            val frequency = 440.0 * Math.pow(2.0, (midi - 69) / 12.0)
            for (sample in 0 until noteSamples) {
                val t = sample.toDouble() / sampleRate
                val envelope = (1.0 - exp(-t * 110.0)) * exp(-t * instrument.decay)
                val wave = instrument.harmonics.indices.sumOf { index ->
                    val harmonic = index + 1
                    instrument.harmonics[index] * sin(2.0 * PI * frequency * harmonic * t)
                }
                pcm[cursor++] = (wave * envelope * 17_500.0)
                    .toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }
            repeat(gapSamples) { pcm[cursor++] = 0 }
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
                        .setSampleRate(sampleRate)
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

    private val Instrument.decay: Double
        get() = when (this) {
            Instrument.Piano -> 3.5
            Instrument.Guitar -> 2.4
        }

    private val Instrument.harmonics: DoubleArray
        get() = when (this) {
            Instrument.Piano -> doubleArrayOf(0.56, 0.25, 0.12, 0.06, 0.03)
            Instrument.Guitar -> doubleArrayOf(0.62, 0.25, 0.09, 0.04)
        }
}
