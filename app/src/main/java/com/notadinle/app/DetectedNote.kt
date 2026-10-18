package com.notadinle.app

internal data class DetectedNote(
    val midi: Int,
    val name: String,
    val durationBeats: Double = 1.0,
)

internal fun midiNoteName(midi: Int): String {
    val names = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
    val safeMidi = midi.coerceIn(24, 96)
    return "${names[Math.floorMod(safeMidi, 12)]}${Math.floorDiv(safeMidi, 12) - 1}"
}
