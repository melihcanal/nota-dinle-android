package com.notadinle.omr

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.roundToInt

open class NoteRecognitionException(message: String) : Exception(message)
private class StaffLayoutException(message: String) : NoteRecognitionException(message)

object StaffNoteRecognizer {
    private data class StaffLine(val y: Double, val strength: Double, val thickness: Int)
    private data class StaffCandidate(val lines: List<StaffLine>, val score: Double)
    private data class SkewScore(val angle: Float, val score: Long)
    private data class NoteHead(val x: Double, val step: Int, val score: Double)
    private data class StemFeature(val x: Int, val direction: Int, val tipY: Int, val length: Int)
    private data class RhythmFeature(val head: NoteHead, val filled: Boolean, val stem: StemFeature?, val flags: Int, val dotted: Boolean, val baseBeats: Double)

    fun recognize(image: GrayImage): List<DetectedNote> {
        if (image.width < 280 || image.height < 160) {
            throw NoteRecognitionException("Görsel nota okumak için küçük kaldı. Sayfayı daha yakından ve net çek.")
        }

        val sourceDark = binarizeAdaptive(image)
        var lastLayoutError: StaffLayoutException? = null
        for (angle in rankDeskewAngles(sourceDark, image.width, image.height)) {
            try {
                return if (angle == 0f) {
                    recognizeHorizontal(image, sourceDark)
                } else {
                    recognizeHorizontal(image.rotate(angle))
                }
            } catch (error: StaffLayoutException) {
                lastLayoutError = error
            }
        }
        throw lastLayoutError ?: StaffLayoutException("Yatay nota portesi bulunamadı. Görseli düz tutup yeniden dene.")
    }

    private fun recognizeHorizontal(image: GrayImage, precomputedDark: BooleanArray? = null): List<DetectedNote> {
        if (image.width < 280 || image.height < 160) {
            throw NoteRecognitionException("Görsel nota okumak için küçük kaldı. Sayfayı daha yakından ve net çek.")
        }

        val dark = precomputedDark ?: binarizeAdaptive(image)

        val xStart = (image.width * 0.06).roundToInt()
        val xEnd = (image.width * 0.96).roundToInt().coerceAtMost(image.width - 1)
        val staves = findStaves(dark, image.width, image.height, xStart, xEnd)
        val recognized = mutableListOf<DetectedNote>()

        staves.forEachIndexed { staffIndex, staff ->
            val spacing = staff.zipWithNext { first, second -> second.y - first.y }.average()
            if (spacing < 2.5) return@forEachIndexed
            // Skip the clef and time-signature area before reading the melody.
            val staffStartX = findStaffLeftEdge(dark, image.width, image.height, xStart, xEnd, staff)
            val leadingSymbolsWidth = if (staffIndex == 0) FIRST_SYSTEM_NOTE_START_SPACES else NEXT_SYSTEM_NOTE_START_SPACES
            val noteStartX = (staffStartX + spacing * leadingSymbolsWidth).roundToInt().coerceAtMost(xEnd)
            val heads = findNoteHeads(dark, image.width, image.height, noteStartX, xEnd, staff, spacing)
            if (heads.isEmpty()) return@forEachIndexed
            if (heads.zipWithNext().any { (first, second) ->
                    abs(first.x - second.x) < spacing * 0.45 && abs(first.step - second.step) > 1
                }) {
                throw NoteRecognitionException("Akor algılandı. Bu ilk sürüm tek sesli melodi okuyor; akor çalma henüz desteklenmiyor.")
            }

            val noteDurations = classifyDurations(dark, image.width, image.height, heads, staff, spacing)
            heads.forEachIndexed { headIndex, head ->
                val diatonicDegree = 4 * 7 + 2 + head.step // Bottom treble line is E4.
                val octave = Math.floorDiv(diatonicDegree, 7)
                val letter = Math.floorMod(diatonicDegree, 7)
                val pitchClass = intArrayOf(0, 2, 4, 5, 7, 9, 11)[letter]
                val midi = 12 * (octave + 1) + pitchClass
                val durationBeats = noteDurations[headIndex]
                recognized += DetectedNote(
                    midi = midi,
                    name = midiNoteName(midi),
                    durationBeats = durationBeats,
                )
            }
        }

        if (recognized.isEmpty()) {
            throw StaffLayoutException("Porte bulundu ama nota başları seçilemedi. Daha temiz, düz ve aydınlık bir görsel dene.")
        }
        if (recognized.size > MAX_NOTES) {
            throw NoteRecognitionException("Bu sayfada çok fazla nota var. İlk sürümde en fazla $MAX_NOTES nota okunabiliyor.")
        }
        return recognized
    }

    private fun classifyDurations(
        dark: BooleanArray,
        width: Int,
        height: Int,
        heads: List<NoteHead>,
        staff: List<StaffLine>,
        spacing: Double,
    ): List<Double> {
        val features = heads.map { head ->
            val centerY = staff.last().y - head.step * spacing / 2.0
            val filled = isFilledNoteHead(dark, width, height, head.x, centerY, staff, spacing)
            val stem = findStem(dark, width, height, head.x, centerY, spacing)
            val flags = if (filled && stem != null) {
                detectFlagCount(dark, width, height, stem, staff, spacing)
            } else {
                0
            }
            val dotted = hasAugmentationDot(dark, width, height, head, heads, centerY, staff, spacing)
            val baseBeats = when {
                !filled && stem == null -> 4.0
                !filled -> 2.0
                flags >= 2 -> 0.25
                flags == 1 -> 0.5
                else -> 1.0
            }
            RhythmFeature(head, filled, stem, flags, dotted, baseBeats)
        }

        val durations = features.map { feature ->
            if (feature.dotted) feature.baseBeats * 1.5 else feature.baseBeats
        }.toMutableList()

        for (index in 0 until features.lastIndex) {
            val first = features[index]
            val second = features[index + 1]
            val firstStem = first.stem
            val secondStem = second.stem
            val closeBeamedPair = first.filled && second.filled &&
                firstStem != null && secondStem != null &&
                firstStem.direction == secondStem.direction &&
                second.head.x - first.head.x <= spacing * 4.25 &&
                first.flags > 0 && second.flags > 0
            if (closeBeamedPair) {
                // Local scans can split a single shared beam differently at its two stems.
                val beamCount = minOf(first.flags, second.flags).coerceIn(1, 2)
                val sharedBeats = if (beamCount == 1) 0.5 else 0.25
                durations[index] = if (first.dotted) sharedBeats * 1.5 else sharedBeats
                durations[index + 1] = if (second.dotted) sharedBeats * 1.5 else sharedBeats
            }
        }

        return durations
    }

    private fun isFilledNoteHead(
        dark: BooleanArray,
        width: Int,
        height: Int,
        centerX: Double,
        centerY: Double,
        staff: List<StaffLine>,
        spacing: Double,
    ): Boolean {
        val xRadius = (spacing * 0.17).roundToInt().coerceAtLeast(1)
        var ink = 0
        var samples = 0
        for (verticalStep in -3..3) {
            val y = (centerY + verticalStep * spacing * 0.085).roundToInt()
            if (y !in 0 until height || isStaffLine(y.toDouble(), staff)) continue
            for (horizontalStep in -xRadius..xRadius) {
                val x = centerX.roundToInt() + horizontalStep
                if (x !in 0 until width) continue
                samples++
                if (dark[y * width + x]) ink++
            }
        }
        return samples > 0 && ink.toDouble() / samples >= 0.30
    }

    private fun findStem(
        dark: BooleanArray,
        width: Int,
        height: Int,
        centerX: Double,
        centerY: Double,
        spacing: Double,
    ): StemFeature? {
        val left = (centerX - spacing * 0.82).roundToInt().coerceAtLeast(0)
        val right = (centerX + spacing * 0.82).roundToInt().coerceAtMost(width - 1)
        val startDistance = (spacing * 0.32).roundToInt().coerceAtLeast(1)
        val endDistance = (spacing * 4.35).roundToInt().coerceAtLeast(startDistance + 1)
        var best: StemFeature? = null

        for (x in left..right) {
            for (direction in intArrayOf(-1, 1)) {
                val (runLength, endDistanceForRun) = longestVerticalRun(
                    dark = dark,
                    width = width,
                    height = height,
                    x = x,
                    centerY = centerY,
                    direction = direction,
                    startDistance = startDistance,
                    endDistance = endDistance,
                )
                if (runLength < spacing * 1.25) continue
                if (best == null || runLength > best.length) {
                    best = StemFeature(
                        x = x,
                        direction = direction,
                        tipY = (centerY + direction * endDistanceForRun).roundToInt(),
                        length = runLength,
                    )
                }
            }
        }
        return best
    }

    private fun longestVerticalRun(
        dark: BooleanArray,
        width: Int,
        height: Int,
        x: Int,
        centerY: Double,
        direction: Int,
        startDistance: Int,
        endDistance: Int,
    ): Pair<Int, Int> {
        var runStart = -1
        var lastInk = -1
        var skippedPixels = 0
        var bestLength = 0
        var bestEnd = 0

        fun saveRun() {
            if (runStart >= 0) {
                val length = lastInk - runStart + 1
                if (length > bestLength) {
                    bestLength = length
                    bestEnd = lastInk
                }
            }
        }

        for (distance in startDistance..endDistance) {
            val y = (centerY + direction * distance).roundToInt()
            val isInk = y in 0 until height && dark[y * width + x]
            if (isInk) {
                if (runStart < 0) runStart = distance
                lastInk = distance
                skippedPixels = 0
            } else if (runStart >= 0 && skippedPixels == 0) {
                skippedPixels = 1
            } else {
                saveRun()
                runStart = -1
                lastInk = -1
                skippedPixels = 0
            }
        }
        saveRun()
        return bestLength to bestEnd
    }

    private fun detectFlagCount(
        dark: BooleanArray,
        width: Int,
        height: Int,
        stem: StemFeature,
        staff: List<StaffLine>,
        spacing: Double,
    ): Int {
        val offsets = doubleArrayOf(0.25, 0.4, 0.55, 0.7, 0.85, 1.0)
        val sideCounts = intArrayOf(-1, 1).map { side ->
            val columnCounts = offsets.map { offset ->
                val x = stem.x + side * (spacing * offset).roundToInt()
                countFlagStrokesAtColumn(dark, width, height, x, stem, staff, spacing)
            }.sorted()
            columnCounts[columnCounts.size / 2]
        }
        return (sideCounts.maxOrNull() ?: 0).coerceIn(0, 2)
    }

    private fun countFlagStrokesAtColumn(
        dark: BooleanArray,
        width: Int,
        height: Int,
        x: Int,
        stem: StemFeature,
        staff: List<StaffLine>,
        spacing: Double,
    ): Int {
        if (x !in 0 until width) return 0
        val towardsHead = -stem.direction
        val startDistance = -(spacing * 0.12).roundToInt()
        val endDistance = (spacing * 1.35).roundToInt()
        var strokes = 0
        var inStroke = false

        for (distance in startDistance..endDistance) {
            val y = stem.tipY + towardsHead * distance
            val isInk = y in 0 until height &&
                !isStaffLine(y.toDouble(), staff) &&
                dark[y * width + x]
            if (isInk && !inStroke) {
                strokes++
                inStroke = true
            } else if (!isInk) {
                inStroke = false
            }
        }
        return strokes.coerceAtMost(2)
    }

    private fun hasAugmentationDot(
        dark: BooleanArray,
        width: Int,
        height: Int,
        head: NoteHead,
        heads: List<NoteHead>,
        centerY: Double,
        staff: List<StaffLine>,
        spacing: Double,
    ): Boolean {
        val nextHead = heads.asSequence().filter { it.x > head.x }.minOfOrNull { it.x }
        if (nextHead != null && nextHead - head.x < spacing * 1.4) return false

        val dotX = (head.x + spacing * 1.35).roundToInt()
        val radiusX = (spacing * 0.25).roundToInt().coerceAtLeast(1)
        val radiusY = (spacing * 0.60).roundToInt().coerceAtLeast(1)
        val dotPixels = mutableListOf<Pair<Int, Int>>()
        for (y in (centerY.roundToInt() - radiusY)..(centerY.roundToInt() + radiusY)) {
            if (y !in 0 until height || isStaffLine(y.toDouble(), staff)) continue
            for (x in (dotX - radiusX)..(dotX + radiusX)) {
                if (x in 0 until width && dark[y * width + x]) dotPixels += x to y
            }
        }
        return dotPixels.size >= 4 && dotPixels.any { (centerX, y) ->
            dotPixels.count { (x, pixelY) -> abs(x - centerX) <= 1 && abs(pixelY - y) <= 1 } >= 4
        }
    }

    private fun isStaffLine(y: Double, staff: List<StaffLine>): Boolean =
        staff.any { abs(y - it.y) <= maxOf(1.4, it.thickness * 0.75) }

    /** Local mean/variance thresholding keeps ink visible when paper brightness changes across a photo. */
    private fun binarizeAdaptive(image: GrayImage): BooleanArray {
        val width = image.width
        val height = image.height
        val pixels = image.luminance
        val radius = (minOf(width, height) / 90).coerceIn(5, 18)
        val verticalSums = LongArray(width)
        val verticalSquares = LongArray(width)

        for (y in 0..minOf(height - 1, radius)) {
            val rowOffset = y * width
            for (x in 0 until width) {
                val value = pixels[rowOffset + x].toInt() and 0xff
                verticalSums[x] += value
                verticalSquares[x] += value.toLong() * value
            }
        }

        val dark = BooleanArray(width * height)
        for (y in 0 until height) {
            var windowSum = 0L
            var windowSquares = 0L
            val initialEnd = minOf(width - 1, radius)
            for (x in 0..initialEnd) {
                windowSum += verticalSums[x]
                windowSquares += verticalSquares[x]
            }
            val yStart = maxOf(0, y - radius)
            val yEnd = minOf(height - 1, y + radius)
            val windowHeight = yEnd - yStart + 1

            for (x in 0 until width) {
                if (x > 0) {
                    val addX = x + radius
                    val removeX = x - radius - 1
                    if (addX < width) {
                        windowSum += verticalSums[addX]
                        windowSquares += verticalSquares[addX]
                    }
                    if (removeX >= 0) {
                        windowSum -= verticalSums[removeX]
                        windowSquares -= verticalSquares[removeX]
                    }
                }

                val xStart = maxOf(0, x - radius)
                val xEnd = minOf(width - 1, x + radius)
                val sampleCount = (xEnd - xStart + 1) * windowHeight
                val mean = windowSum.toDouble() / sampleCount
                val variance = (windowSquares.toDouble() / sampleCount - mean * mean).coerceAtLeast(0.0)
                val threshold = (mean - maxOf(9.0, sqrt(variance) * 0.22)).coerceIn(24.0, 245.0)
                dark[y * width + x] = (pixels[y * width + x].toInt() and 0xff) < threshold
            }

            val addY = y + radius + 1
            val removeY = y - radius
            if (addY < height || removeY >= 0) {
                for (x in 0 until width) {
                    if (addY < height) {
                        val value = pixels[addY * width + x].toInt() and 0xff
                        verticalSums[x] += value
                        verticalSquares[x] += value.toLong() * value
                    }
                    if (removeY >= 0) {
                        val value = pixels[removeY * width + x].toInt() and 0xff
                        verticalSums[x] -= value
                        verticalSquares[x] -= value.toLong() * value
                    }
                }
            }
        }
        return dark
    }

    /** Scores horizontal projection concentration and rotates only the best few candidates. */
    private fun rankDeskewAngles(dark: BooleanArray, width: Int, height: Int): List<Float> {
        val stride = ceil(sqrt(width.toDouble() * height / MAX_PROJECTION_SAMPLES)).toInt().coerceAtLeast(1)
        val xStart = (width * 0.04).roundToInt()
        val xEnd = (width * 0.96).roundToInt().coerceAtMost(width - 1)
        val yStart = (height * 0.04).roundToInt()
        val yEnd = (height * 0.96).roundToInt().coerceAtMost(height - 1)
        val sampleCapacity = ((xEnd - xStart) / stride + 1) * ((yEnd - yStart) / stride + 1)
        val sampleXs = IntArray(sampleCapacity)
        val sampleYs = IntArray(sampleCapacity)
        var sampleCount = 0
        for (y in yStart..yEnd step stride) {
            val offset = y * width
            for (x in xStart..xEnd step stride) {
                if (dark[offset + x]) {
                    sampleXs[sampleCount] = x
                    sampleYs[sampleCount] = y
                    sampleCount++
                }
            }
        }
        if (sampleCount < MIN_SKEW_SAMPLES) return listOf(0f)

        val centerX = (width - 1) / 2.0
        val centerY = (height - 1) / 2.0
        val projectionPadding = ceil(width * sin(Math.toRadians(MAX_SKEW_DEGREES.toDouble()))).toInt() + 2
        val projectionSize = height + projectionPadding * 2
        val scores = ArrayList<SkewScore>(SKEW_STEPS * 2 + 1)
        for (step in -SKEW_STEPS..SKEW_STEPS) {
            val angle = step * SKEW_STEP_DEGREES
            val radians = Math.toRadians(angle.toDouble())
            val cosine = kotlin.math.cos(radians)
            val sine = sin(radians)
            val projection = IntArray(projectionSize)
            for (index in 0 until sampleCount) {
                val x = sampleXs[index]
                val y = sampleYs[index]
                val projectedY = ((y - centerY) * cosine + (x - centerX) * sine + centerY).roundToInt()
                val bin = projectedY + projectionPadding
                if (bin in projection.indices) projection[bin]++
            }
            var score = 0L
            projection.forEach { count -> score += count.toLong() * count }
            scores += SkewScore(angle, score)
        }

        val zeroScore = scores.first { it.angle == 0f }
        val ranked = scores.sortedByDescending { it.score }
        val best = ranked.first()
        if (abs(best.angle) < MIN_DESKEW_ANGLE || best.score <= zeroScore.score * MIN_DESKEW_IMPROVEMENT) {
            return listOf(0f)
        }

        val fallbacks = ranked
            .asSequence()
            .filter { abs(it.angle - best.angle) >= MIN_FALLBACK_ANGLE_DISTANCE }
            .filter { it.score >= best.score * MIN_FALLBACK_SCORE_RATIO }
            .take(2)
            .map { it.angle }
            .toList()
        return (listOf(best.angle) + fallbacks + 0f).distinct().take(MAX_DESKEW_ATTEMPTS)
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
            .mapNotNull { candidate -> refineNoteHead(candidate, dark, width, height, staff, spacing) }
            .groupBy { it.step }
            .values
            .flatMap { sameStep ->
                sameStep.sortedByDescending { it.score }.fold(mutableListOf<NoteHead>()) { kept, candidate ->
                    if (kept.none { abs(it.x - candidate.x) < spacing * 0.70 }) kept += candidate
                    kept
                }
            }
            .sortedWith(compareBy<NoteHead> { it.x }.thenByDescending { it.step })

        val cleaned = mutableListOf<NoteHead>()
        sameStepDeduplicated.forEach { candidate ->
            val nearbyHead = cleaned.indexOfFirst { abs(it.x - candidate.x) < spacing * 1.35 }
            if (nearbyHead < 0) {
                cleaned += candidate
            } else {
                val existing = cleaned[nearbyHead]
                if (candidate.score > existing.score) {
                    cleaned[nearbyHead] = candidate
                }
            }
        }
        val result = cleaned.sortedBy { it.x }.filterNot { head ->
            head.x >= xEnd - spacing * 3.0 &&
                isFullStaffVerticalLine(dark, width, height, head.x, staff, spacing)
        }
        return result
    }

    private fun isFullStaffVerticalLine(
        dark: BooleanArray,
        width: Int,
        height: Int,
        centerX: Double,
        staff: List<StaffLine>,
        spacing: Double,
    ): Boolean {
        val top = staff.first().y.roundToInt().coerceAtLeast(0)
        val bottom = staff.last().y.roundToInt().coerceAtMost(height - 1)
        val radius = (spacing * 0.12).roundToInt().coerceAtLeast(1)
        val minimumCoverage = (bottom - top + 1) * 0.72
        for (x in (centerX.roundToInt() - radius)..(centerX.roundToInt() + radius)) {
            if (x !in 0 until width) continue
            var inkRows = 0
            for (y in top..bottom) if (dark[y * width + x]) inkRows++
            if (inkRows >= minimumCoverage) return true
        }
        return false
    }

    private fun refineNoteHead(
        candidate: NoteHead,
        dark: BooleanArray,
        width: Int,
        height: Int,
        staff: List<StaffLine>,
        spacing: Double,
    ): NoteHead? {
        val maximumStep = 10
        val minimumStep = -4
        val xSearchRadius = (spacing * 0.12).roundToInt().coerceAtLeast(1)
        var bestScore = 0.0
        var bestX = candidate.x
        var bestStep = candidate.step
        for (step in (candidate.step - 1).coerceAtLeast(minimumStep)..(candidate.step + 1).coerceAtMost(maximumStep)) {
            val centerY = staff.last().y - step * spacing / 2.0
            for (xOffset in -xSearchRadius..xSearchRadius) {
                val centerX = candidate.x + xOffset
                val score = noteHeadShapeScore(dark, width, height, centerX, centerY, staff, spacing)
                if (score > bestScore) {
                    bestScore = score
                    bestX = centerX
                    bestStep = step
                }
            }
        }
        if (bestScore < MIN_NOTEHEAD_SHAPE_SCORE) return null
        return NoteHead(bestX, bestStep, candidate.score + bestScore)
    }

    /** Rejects text, ties and stem fragments by checking for an oval notehead contour. */
    private fun noteHeadShapeScore(
        dark: BooleanArray,
        width: Int,
        height: Int,
        centerX: Double,
        centerY: Double,
        staff: List<StaffLine>,
        spacing: Double,
    ): Double {
        val radiusX = spacing * 0.62
        val radiusY = spacing * 0.37
        val left = (centerX - radiusX).roundToInt().coerceAtLeast(0)
        val right = (centerX + radiusX).roundToInt().coerceAtMost(width - 1)
        val top = (centerY - radiusY).roundToInt().coerceAtLeast(0)
        val bottom = (centerY + radiusY).roundToInt().coerceAtMost(height - 1)
        var contourInk = 0
        var contourSamples = 0
        var supportedRows = 0
        val quadrantInk = IntArray(4)
        val quadrantSamples = IntArray(4)

        for (y in top..bottom) {
            if (isStaffLine(y.toDouble(), staff)) continue
            var rowInk = 0
            for (x in left..right) {
                val dx = (x - centerX) / radiusX
                val dy = (y - centerY) / radiusY
                val ellipse = dx * dx + dy * dy
                if (ellipse !in 0.48..1.18) continue
                contourSamples++
                val quadrant = when {
                    abs(dx) > abs(dy) && dx < 0 -> 0
                    abs(dx) > abs(dy) -> 1
                    dy < 0 -> 2
                    else -> 3
                }
                quadrantSamples[quadrant]++
                if (dark[y * width + x]) {
                    contourInk++
                    rowInk++
                    quadrantInk[quadrant]++
                }
            }
            if (rowInk >= 2) supportedRows++
        }

        if (contourSamples == 0) return 0.0
        val contourRatio = contourInk.toDouble() / contourSamples
        val minimumQuadrantRatio = quadrantInk.indices.minOf { index ->
            quadrantInk[index].toDouble() / quadrantSamples[index].coerceAtLeast(1)
        }
        val rowSupport = (supportedRows / MIN_NOTEHEAD_SUPPORTED_ROWS).coerceAtMost(1.0)
        return contourRatio * 0.55 + minimumQuadrantRatio * 0.35 + rowSupport * 0.10
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

        val strongestRowCount = rowCounts.maxOrNull() ?: 0
        val threshold = maxOf((regionWidth * 0.20).roundToInt(), (strongestRowCount * 0.40).roundToInt())
        val smoothedCounts = IntArray(height) { row ->
            val previous = if (row > 0) rowCounts[row - 1] else 0
            val next = if (row < height - 1) rowCounts[row + 1] else 0
            maxOf(rowCounts[row], previous, next)
        }
        val lines = mutableListOf<StaffLine>()
        var y = 0
        while (y < height) {
            if (smoothedCounts[y] < threshold) {
                y++
                continue
            }
            val start = y
            var sumY = 0.0
            var sumWeight = 0.0
            var strongest = 0
            while (y < height && smoothedCounts[y] >= threshold) {
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
            throw StaffLayoutException("Yatay beş çizgili porte bulunamadı. Görseli düz tutup sayfayı daha yakından çek.")
        }

        val candidates = mutableListOf<StaffCandidate>()
        for (start in 0..lines.size - 5) {
            val group = lines.subList(start, start + 5)
            val gaps = group.zipWithNext { first, second -> second.y - first.y }
            val averageGap = gaps.average()
            if (averageGap < 2.5 || averageGap > minOf(width, height) * 0.18) continue
            val maxDeviation = gaps.maxOf { abs(it - averageGap) } / averageGap
            if (maxDeviation > 0.42) continue
            candidates += StaffCandidate(group.toList(), group.map { it.strength }.average() - maxDeviation * 0.5)
        }
        if (candidates.isEmpty()) {
            throw StaffLayoutException("Düz, yatay bir nota portesi bulunamadı. Fotoğrafı döndürüp yeniden dene.")
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

    private const val MAX_PROJECTION_SAMPLES = 350_000.0
    private const val MIN_SKEW_SAMPLES = 200
    private const val MAX_SKEW_DEGREES = 8
    private const val SKEW_STEP_DEGREES = 0.25f
    private const val SKEW_STEPS = 32
    private const val MIN_DESKEW_ANGLE = 0.35f
    private const val MIN_DESKEW_IMPROVEMENT = 1.015
    private const val MIN_FALLBACK_ANGLE_DISTANCE = 0.75f
    private const val MIN_FALLBACK_SCORE_RATIO = 0.96
    private const val MAX_DESKEW_ATTEMPTS = 3
    private const val MIN_NOTEHEAD_SHAPE_SCORE = 0.16
    private const val MIN_NOTEHEAD_SUPPORTED_ROWS = 4.0
    private const val FIRST_SYSTEM_NOTE_START_SPACES = 7.0
    private const val NEXT_SYSTEM_NOTE_START_SPACES = 4.8
    private const val MAX_NOTES = 200
}
