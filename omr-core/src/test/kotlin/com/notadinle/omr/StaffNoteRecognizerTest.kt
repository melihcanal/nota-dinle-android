package com.notadinle.omr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.math.roundToInt
import javax.imageio.ImageIO

class StaffNoteRecognizerTest {
    @Test
    fun recognizes_treble_staff_pitches_and_basic_note_values() {
        val score = SyntheticScore()
        score.addStaff(top = 150)
        score.addWholeNote(x = 320, step = 0)
        score.addHalfNote(x = 440, step = 1)
        score.addQuarterNote(x = 560, step = 2)

        val notes = StaffNoteRecognizer.recognize(score.toGrayImage())

        assertEquals(listOf(64, 65, 67), notes.map { it.midi })
        assertEquals(listOf(4.0, 2.0, 1.0), notes.map { it.durationBeats })
    }

    @Test
    fun recognizes_eighth_and_sixteenth_note_flags() {
        val score = SyntheticScore()
        score.addStaff(top = 150)
        score.addQuarterNote(x = 320, step = 0)
        score.addFlaggedQuarterNote(x = 480, step = 1, flags = 1)
        score.addFlaggedQuarterNote(x = 640, step = 2, flags = 2)

        val notes = StaffNoteRecognizer.recognize(score.toGrayImage())

        assertEquals(listOf(1.0, 0.5, 0.25), notes.map { it.durationBeats })
    }

    @Test
    fun recognizes_staff_skewed_by_five_degrees_in_either_direction() {
        for (angle in listOf(-5f, 5f)) {
            val score = basicMelody()

            val notes = StaffNoteRecognizer.recognize(score.toGrayImage().rotate(angle))

            assertEquals(listOf(64, 65, 67), notes.map { it.midi }, "Skew angle: $angle")
        }
    }

    @Test
    fun recognizes_notes_under_a_gradual_page_shadow() {
        val score = basicMelody().withHorizontalShadow()

        val notes = StaffNoteRecognizer.recognize(score.toGrayImage())

        assertEquals(listOf(64, 65, 67), notes.map { it.midi })
    }

    @Test
    fun rejects_an_image_without_a_staff() {
        val white = GrayImage(640, 400, ByteArray(640 * 400) { 0xff.toByte() })

        assertFailsWith<NoteRecognitionException> { StaffNoteRecognizer.recognize(white) }
    }

    @Test
    fun recognizes_the_previously_failing_ode_to_joy_scan() {
        val bitmap = checkNotNull(javaClass.getResourceAsStream("/ode-to-joy.png")).use(ImageIO::read)
        val luminance = ByteArray(bitmap.width * bitmap.height)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val color = bitmap.getRGB(x, y)
            val red = color ushr 16 and 0xff
            val green = color ushr 8 and 0xff
            val blue = color and 0xff
            luminance[y * bitmap.width + x] = ((red * 299 + green * 587 + blue * 114) / 1000).toByte()
        }

        val notes = StaffNoteRecognizer.recognize(GrayImage(bitmap.width, bitmap.height, luminance))

        assertEquals(
            listOf(
                "E5", "E5", "F5", "G5", "G5", "F5", "E5", "D5", "C5", "C5", "D5", "E5", "E5", "D5", "D5",
                "E5", "E5", "F5", "G5", "G5", "F5", "E5", "D5", "C5", "C5", "D5", "E5", "D5", "C5", "C5",
                "D5", "D5", "E5", "C5", "D5", "E5", "F5", "E5", "C5", "D5", "E5", "F5", "E5", "D5", "C5", "D5", "G4", "E5",
                "E5", "E5", "F5", "G5", "G5", "F5", "E5", "D5", "C5", "C5", "D5", "E5", "D5", "C5", "C5",
            ),
            notes.map { it.name },
        )
    }

    private fun basicMelody() = SyntheticScore().apply {
        addStaff(top = 150)
        addWholeNote(x = 320, step = 0)
        addHalfNote(x = 440, step = 1)
        addQuarterNote(x = 560, step = 2)
    }
}

private class SyntheticScore(
    private val width: Int = 1400,
    private val height: Int = 500,
) {
    private val pixels = ByteArray(width * height) { 0xff.toByte() }
    private val spacing = 20
    private var staffTop = 150

    fun addStaff(top: Int, xStart: Int = 100, xEnd: Int = width - 100) {
        staffTop = top
        for (line in 0..4) {
            val y = top + line * spacing
            for (x in xStart until xEnd) for (thickness in -1..1) set(x, y + thickness)
        }
    }

    fun addWholeNote(x: Int, step: Int) = addNote(x, step, filled = false, stem = false)
    fun addHalfNote(x: Int, step: Int) = addNote(x, step, filled = false, stem = true)
    fun addQuarterNote(x: Int, step: Int) = addNote(x, step, filled = true, stem = true)
    fun addFlaggedQuarterNote(x: Int, step: Int, flags: Int) {
        addQuarterNote(x, step)
        val centerY = staffTop + 4 * spacing - step * spacing / 2
        val stemTip = centerY - 84
        for (flag in 0 until flags.coerceIn(1, 2)) {
            for (offset in 0..spacing) {
                val y = stemTip + flag * 10 + (offset * 0.45).roundToInt()
                set(x + 9 + offset, y)
            }
        }
    }

    fun withHorizontalShadow(): SyntheticScore {
        for (y in 0 until height) for (x in 0 until width) {
            val index = y * width + x
            val ink = pixels[index].toInt() == 0
            if (!ink) pixels[index] = (245 - x * 100 / width - y * 20 / height).toByte()
        }
        return this
    }

    private fun addNote(x: Int, step: Int, filled: Boolean, stem: Boolean) {
        val centerY = staffTop + 4 * spacing - step * spacing / 2
        val radiusX = 11.0
        val radiusY = 7.0
        for (y in (centerY - 8)..(centerY + 8)) for (pixelX in (x - 12)..(x + 12)) {
            val shape = ((pixelX - x) * (pixelX - x) / (radiusX * radiusX)) +
                ((y - centerY) * (y - centerY) / (radiusY * radiusY))
            if (shape <= 1.0 && (filled || shape >= 0.58)) set(pixelX, y)
        }
        if (stem) for (y in (centerY - 84)..centerY) set(x + 9, y)
    }

    fun toGrayImage(): GrayImage = GrayImage(width, height, pixels)

    private fun set(x: Int, y: Int) {
        if (x in 0 until width && y in 0 until height) pixels[y * width + x] = 0x00
    }
}
