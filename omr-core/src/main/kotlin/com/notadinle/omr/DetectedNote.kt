package com.notadinle.omr

data class DetectedNote(
    val midi: Int,
    val name: String,
    val durationBeats: Double = 1.0,
)

fun midiNoteName(midi: Int): String {
    val names = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
    val safeMidi = midi.coerceIn(24, 96)
    return "${names[Math.floorMod(safeMidi, 12)]}${Math.floorDiv(safeMidi, 12) - 1}"
}

private val supportedBeatLengths = doubleArrayOf(0.25, 0.5, 0.75, 1.0, 1.5, 2.0, 3.0, 4.0, 6.0)

fun noteDurationLabel(beats: Double): String = when (beats) {
    0.25 -> "¼ vuruş"
    0.5 -> "½ vuruş"
    0.75 -> "¾ vuruş"
    1.0 -> "1 vuruş"
    1.5 -> "1½ vuruş"
    2.0 -> "2 vuruş"
    3.0 -> "3 vuruş"
    4.0 -> "4 vuruş"
    6.0 -> "6 vuruş"
    else -> "1 vuruş"
}

fun adjustNoteDuration(beats: Double, direction: Int): Double {
    val currentIndex = supportedBeatLengths.indices.minByOrNull {
        kotlin.math.abs(supportedBeatLengths[it] - beats)
    } ?: 3
    return supportedBeatLengths[(currentIndex + direction).coerceIn(supportedBeatLengths.indices)]
}
