package com.notadinle.app

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.roundToInt

internal class NoteRecognitionException(message: String) : Exception(message)

internal object StaffNoteRecognizer {
    private data class StaffLine(val y: Double, val strength: Double, val thickness: Int)
    private data class StaffCandidate(val lines: List<StaffLine>, val score: Double)
    private data class NoteHead(val x: Double, val step: Int, val score: Double)

    fun recognize(bitmap: Bitmap): List<DetectedNote> {
        if (bitmap.width < 280 || bitmap.height < 160) {
            throw NoteRecognitionException("Görsel nota okumak için küçük kaldı. Sayfayı daha yakından ve net çek.")
        }

        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val dark = BooleanArray(pixels.size) { index ->
            val color = pixels[index]
            val luminance = (((color shr 16) and 0xff) * 299 + ((color shr 8) and 0xff) * 587 + (color and 0xff) * 114) / 1000
            luminance < 168
        }

        val xStart = (bitmap.width * 0.06).roundToInt()
        val xEnd = (bitmap.width * 0.96).roundToInt().coerceAtMost(bitmap.width - 1)
        val staves = findStaves(dark, bitmap.width, bitmap.height, xStart, xEnd)
        val recognized = mutableListOf<DetectedNote>()

        staves.forEachIndexed { staffIndex, staff ->
            val spacing = staff.zipWithNext { first, second -> second.y - first.y }.average()
            if (spacing < 2.5) return@forEachIndexed
            // Skip the clef and time-signature area before reading the melody.
            val staffStartX = findStaffLeftEdge(dark, bitmap.width, bitmap.height, xStart, xEnd, staff)
            val leadingSymbolsWidth = if (staffIndex == 0) FIRST_SYSTEM_NOTE_START_SPACES else NEXT_SYSTEM_NOTE_START_SPACES
            val noteStartX = (staffStartX + spacing * leadingSymbolsWidth).roundToInt().coerceAtMost(xEnd)
            val heads = findNoteHeads(dark, bitmap.width, bitmap.height, noteStartX, xEnd, staff, spacing)
            if (heads.isEmpty()) return@forEachIndexed
            if (heads.zipWithNext().any { (first, second) ->
                    abs(first.x - second.x) < spacing * 0.45 && abs(first.step - second.step) > 1
                }) {
                throw NoteRecognitionException("Akor algılandı. Bu ilk sürüm tek sesli melodi okuyor; akor çalma henüz desteklenmiyor.")
            }

            heads.forEach { head ->
                val diatonicDegree = 4 * 7 + 2 + head.step // Bottom treble line is E4.
                val octave = Math.floorDiv(diatonicDegree, 7)
                val letter = Math.floorMod(diatonicDegree, 7)
                val pitchClass = intArrayOf(0, 2, 4, 5, 7, 9, 11)[letter]
                val midi = 12 * (octave + 1) + pitchClass
                recognized += DetectedNote(midi = midi, name = midiNoteName(midi), durationBeats = 1.0)
            }
        }

        if (recognized.isEmpty()) {
            throw NoteRecognitionException("Porte bulundu ama nota başları seçilemedi. Daha temiz, düz ve aydınlık bir görsel dene.")
        }
        if (recognized.size > MAX_NOTES) {
            throw NoteRecognitionException("Bu sayfada çok fazla nota var. İlk sürümde en fazla $MAX_NOTES nota okunabiliyor.")
        }
        return recognized
    }

    private fun findStaffLeftEdge(
        dark: BooleanArray,
        width: Int,
        height: Int,
        xStart: Int,
        xEnd: Int,
        staff: List<StaffLine>,
    ): Int {
        val lineStarts = staff.mapNotNull { line ->
            val centerY = line.y.roundToInt()
            val top = (centerY - line.thickness - 1).coerceAtLeast(0)
            val bottom = (centerY + line.thickness + 1).coerceAtMost(height - 1)
            (xStart..xEnd).firstOrNull { x ->
                (top..bottom).any { row -> dark[row * width + x] }
            }
        }
        return lineStarts.sorted().let { starts ->
            if (starts.isEmpty()) xStart else starts[starts.size / 2]
        }
    }

    private fun findNoteHeads(
        dark: BooleanArray,
        width: Int,
        height: Int,
        xStart: Int,
        xEnd: Int,
        staff: List<StaffLine>,
        spacing: Double,
    ): List<NoteHead> {
        val bottomLine = staff.last().y
        val averageThickness = staff.map { it.thickness }.average()
        val offsetFromStaffLine = maxOf(1.0, minOf(spacing * 0.30, averageThickness / 2.0 + 1.0))
        val minRunWidth = (spacing * 0.62).roundToInt().coerceAtLeast(2)
        val maxRunWidth = (spacing * 2.25).roundToInt().coerceAtLeast(minRunWidth + 1)
        val candidates = mutableListOf<NoteHead>()

        // Music-text labels often sit two staff spaces above the top line. Exclude that area.
        for (step in -4..10) {
            val targetY = bottomLine - step * spacing / 2.0
            if (targetY < 1 || targetY >= height - 1) continue
            val baseRow = targetY.roundToInt()
            val lineOffset = offsetFromStaffLine.roundToInt()
            val rowOffsets = if (step % 2 == 0) {
                intArrayOf(lineOffset, -lineOffset)
            } else {
                intArrayOf(0, lineOffset, -lineOffset)
            }
            rowOffsets.distinct().forEach { rowOffset ->
                val row = baseRow + rowOffset
                if (row !in 0 until height) return@forEach
                findRuns(dark, width, row, xStart, xEnd, minRunWidth, maxRunWidth, (spacing * 0.75).roundToInt())
                    .forEach { (start, end) ->
                        val runWidth = (end - start + 1).toDouble()
                        val centerX = (start + end) / 2.0
                        val widthScore = 1.0 - abs(runWidth / spacing - 1.25) * 0.28
                        val offsetScore = 1.0 - abs(rowOffset) / (spacing * 4.0)
                        candidates += NoteHead(centerX, step, widthScore + offsetScore)
                    }
            }
        }

        val sameStepDeduplicated = candidates
            .groupBy { it.step }
            .values
            .flatMap { sameStep ->
                sameStep.sortedByDescending { it.score }.fold(mutableListOf<NoteHead>()) { kept, candidate ->
                    if (kept.none { abs(it.x - candidate.x) < spacing * 0.55 }) kept += candidate
                    kept
                }
            }
            .sortedWith(compareBy<NoteHead> { it.x }.thenByDescending { it.step })

        val cleaned = mutableListOf<NoteHead>()
        sameStepDeduplicated.forEach { candidate ->
            val adjacentStepDuplicate = cleaned.indexOfFirst {
                abs(it.x - candidate.x) < spacing * 0.40 && abs(it.step - candidate.step) == 1
            }
            if (adjacentStepDuplicate < 0) {
                cleaned += candidate
            } else if (candidate.score > cleaned[adjacentStepDuplicate].score) {
                cleaned[adjacentStepDuplicate] = candidate
            }
        }
        return cleaned.sortedBy { it.x }
    }

    private fun findStaves(
        dark: BooleanArray,
        width: Int,
        height: Int,
        xStart: Int,
        xEnd: Int,
    ): List<List<StaffLine>> {
        val regionWidth = xEnd - xStart + 1
        val rowCounts = IntArray(height)
        for (y in 0 until height) {
            var count = 0
            val rowOffset = y * width
            for (x in xStart..xEnd) if (dark[rowOffset + x]) count++
            rowCounts[y] = count
        }

        val threshold = (regionWidth * 0.42).roundToInt()
        val lines = mutableListOf<StaffLine>()
        var y = 0
        while (y < height) {
            if (rowCounts[y] < threshold) {
                y++
                continue
            }
            val start = y
            var sumY = 0.0
            var sumWeight = 0.0
            var strongest = 0
            while (y < height && rowCounts[y] >= threshold) {
                val weight = rowCounts[y].toDouble()
                sumY += y * weight
                sumWeight += weight
                strongest = maxOf(strongest, rowCounts[y])
                y++
            }
            lines += StaffLine(
                y = sumY / sumWeight,
                strength = strongest.toDouble() / regionWidth,
                thickness = y - start,
            )
        }

        if (lines.size < 5) {
            throw NoteRecognitionException("Yatay beş çizgili porte bulunamadı. Görseli düz tutup sayfayı daha yakından çek.")
        }

        val candidates = mutableListOf<StaffCandidate>()
        for (start in 0..lines.size - 5) {
            val group = lines.subList(start, start + 5)
            val gaps = group.zipWithNext { first, second -> second.y - first.y }
            val averageGap = gaps.average()
            if (averageGap < 2.5 || averageGap > minOf(width, height) * 0.18) continue
            val maxDeviation = gaps.maxOf { abs(it - averageGap) } / averageGap
            if (maxDeviation > 0.24) continue
            candidates += StaffCandidate(group.toList(), group.map { it.strength }.average() - maxDeviation * 0.5)
        }
        if (candidates.isEmpty()) {
            throw NoteRecognitionException("Düz, yatay bir nota portesi bulunamadı. Fotoğrafı döndürüp yeniden dene.")
        }

        val selected = mutableListOf<StaffCandidate>()
        candidates.sortedBy { it.lines.first().y }.forEach { candidate ->
            val averageGap = candidate.lines.zipWithNext { first, second -> second.y - first.y }.average()
            val overlap = selected.indexOfFirst { existing ->
                val existingGap = existing.lines.zipWithNext { first, second -> second.y - first.y }.average()
                abs(existing.lines.first().y - candidate.lines.first().y) < maxOf(averageGap, existingGap) * 1.75
            }
            if (overlap < 0) {
                selected += candidate
            } else if (candidate.score > selected[overlap].score) {
                selected[overlap] = candidate
            }
        }
        return selected.sortedBy { it.lines.first().y }.map { it.lines }
    }

    private fun findRuns(
        dark: BooleanArray,
        width: Int,
        row: Int,
        xStart: Int,
        xEnd: Int,
        minWidth: Int,
        maxWidth: Int,
        mergeGap: Int,
    ): List<Pair<Int, Int>> {
        val offset = row * width
        val raw = mutableListOf<Pair<Int, Int>>()
        var x = xStart
        while (x <= xEnd) {
            if (!dark[offset + x]) {
                x++
                continue
            }
            val start = x
            while (x <= xEnd && dark[offset + x]) x++
            raw += start to x - 1
        }

        val merged = mutableListOf<Pair<Int, Int>>()
        raw.forEach { run ->
            val last = merged.lastOrNull()
            if (last != null && run.first - last.second - 1 <= mergeGap && run.second - last.first + 1 <= maxWidth) {
                merged[merged.lastIndex] = last.first to run.second
            } else {
                merged += run
            }
        }
        return merged.filter { (start, end) -> end - start + 1 in minWidth..maxWidth }
    }

    private const val FIRST_SYSTEM_NOTE_START_SPACES = 7.0
    private const val NEXT_SYSTEM_NOTE_START_SPACES = 4.8
    private const val MAX_NOTES = 200
}
