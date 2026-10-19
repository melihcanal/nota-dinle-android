package com.notadinle.app.data.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.notadinle.omr.DetectedNote
import com.notadinle.omr.midiNoteName
import com.notadinle.app.domain.model.Instrument
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

internal object TonePlayer {
    private val demoNotes = intArrayOf(60, 64, 67, 72) // C4, E4, G4, C5
    private const val SAMPLE_RATE = 44_100
    private const val NOTE_GAP_SECONDS = 0.008
    private const val NOTES_PER_AUDIO_CHUNK = 8

    suspend fun playSample(context: Context, instrument: Instrument, tempo: Int) {
        val notes = demoNotes.map { midi -> DetectedNote(midi, midiNoteName(midi)) }
        playNotes(context, notes, instrument, tempo)
    }

    suspend fun playNotes(context: Context, notes: List<DetectedNote>, instrument: Instrument, tempo: Int) {
        if (notes.isEmpty()) return
        val samples = InstrumentSampleBank.preload(context, instrument, notes)
        val safeTempo = tempo.coerceIn(60, 140)
        val gapSamples = (SAMPLE_RATE * NOTE_GAP_SECONDS).roundToInt()
        val beatIntervals = notes.map { note ->
            (SAMPLE_RATE * 60.0 / safeTempo * note.durationBeats.coerceIn(0.25, 6.0))
                .roundToInt()
                .coerceAtLeast(1)
        }

        val minimumBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(0)
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
                .setBufferSizeInBytes(maxOf(minimumBuffer * 2, 8_192))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }

        try {
            withContext(Dispatchers.IO) {
                track.play()
                var queuedSamples = 0L
                notes.indices.chunked(NOTES_PER_AUDIO_CHUNK).forEach { noteIndices ->
                    val chunk = withContext(Dispatchers.Default) {
                        val chunkSamples = noteIndices.sumOf { beatIntervals[it] }
                        val output = ShortArray(chunkSamples)
                        var cursor = 0
                        noteIndices.forEach { index ->
                            val note = notes[index]
                            val noteLength = (beatIntervals[index] - gapSamples).coerceAtLeast(1)
                            val source = samples.getValue(note.midi)
                            val samplesForNote = renderSample(source, note.midi, noteLength)
                            writeNoteWithFades(samplesForNote, output, cursor)
                            cursor += beatIntervals[index]
                        }
                        output
                    }

                    var written = 0
                    while (written < chunk.size) {
                        val count = track.write(chunk, written, minOf(4_096, chunk.size - written), AudioTrack.WRITE_BLOCKING)
                        check(count > 0) { "Ses örneği yazılamadı (AudioTrack=$count)" }
                        written += count
                    }
                    queuedSamples += chunk.size
                }
                while (track.playbackHeadPosition.toLong() < queuedSamples) delay(12)
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { track.stop() }
                track.release()
            }
        }
    }

    private fun renderSample(sample: DecodedInstrumentSample, targetMidi: Int, outputLength: Int): FloatArray {
        val output = FloatArray(outputLength)
        if (sample.frameCount < 2) return output
        val pitchRatio = 2.0.pow((targetMidi - sample.sourceMidi) / 12.0)
        val sourceFramesPerOutputSample = pitchRatio * sample.sampleRate / SAMPLE_RATE

        output.indices.forEach { outputIndex ->
            val sourcePosition = outputIndex * sourceFramesPerOutputSample
            val firstFrame = sourcePosition.toInt()
            if (firstFrame >= sample.frameCount - 1) return@forEach
            val secondFrame = firstFrame + 1
            val fraction = sourcePosition - firstFrame
            val first = sample.monoFrame(firstFrame)
            val second = sample.monoFrame(secondFrame)
            output[outputIndex] = (first + (second - first) * fraction).toFloat()
        }
        return output
    }

    private fun writeNoteWithFades(note: FloatArray, output: ShortArray, offset: Int) {
        val peak = note.maxOfOrNull { abs(it.toDouble()) } ?: 0.0
        val gain = if (peak > 0.000001) 0.70 / peak else 0.0
        val attackSamples = (SAMPLE_RATE * 0.002).roundToInt().coerceAtLeast(1)
        val releaseSamples = (SAMPLE_RATE * 0.035).roundToInt().coerceAtMost(note.size / 3)

        note.indices.forEach { sample ->
            val attack = (sample.toDouble() / attackSamples).coerceAtMost(1.0)
            val release = if (releaseSamples > 0 && sample >= note.size - releaseSamples) {
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
