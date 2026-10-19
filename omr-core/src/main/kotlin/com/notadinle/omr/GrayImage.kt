package com.notadinle.omr

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Immutable, 8-bit grayscale pixels. This type deliberately has no Android dependency. */
class GrayImage(
    val width: Int,
    val height: Int,
    luminance: ByteArray,
) {
    val luminance: ByteArray = luminance.copyOf()

    init {
        require(width > 0 && height > 0) { "Görsel boyutları geçersiz." }
        require(width.toLong() * height <= MAX_PIXELS) { "Görsel işleme sınırını aşıyor." }
        require(this.luminance.size == width * height) { "Piksel verisi boyutla uyuşmuyor." }
    }

    /** Rotates around the image center, using white for newly exposed border pixels. */
    fun rotate(degrees: Float): GrayImage {
        if (abs(degrees) < 0.01f) return this

        val radians = Math.toRadians(degrees.toDouble())
        val cosine = cos(radians)
        val sine = sin(radians)
        val outputWidth = ceil(abs(width * cosine) + abs(height * sine)).toInt().coerceAtLeast(1)
        val outputHeight = ceil(abs(height * cosine) + abs(width * sine)).toInt().coerceAtLeast(1)
        val output = ByteArray(outputWidth * outputHeight) { 0xff.toByte() }
        val sourceCenterX = (width - 1) / 2.0
        val sourceCenterY = (height - 1) / 2.0
        val outputCenterX = (outputWidth - 1) / 2.0
        val outputCenterY = (outputHeight - 1) / 2.0

        for (y in 0 until outputHeight) {
            val dy = y - outputCenterY
            for (x in 0 until outputWidth) {
                val dx = x - outputCenterX
                val sourceX = dx * cosine + dy * sine + sourceCenterX
                val sourceY = -dx * sine + dy * cosine + sourceCenterY
                if (sourceX < 0.0 || sourceY < 0.0 || sourceX > width - 1 || sourceY > height - 1) continue

                val x0 = sourceX.toInt()
                val y0 = sourceY.toInt()
                val x1 = (x0 + 1).coerceAtMost(width - 1)
                val y1 = (y0 + 1).coerceAtMost(height - 1)
                val fx = sourceX - x0
                val fy = sourceY - y0
                val topLeft = luminance[y0 * width + x0].toInt() and 0xff
                val topRight = luminance[y0 * width + x1].toInt() and 0xff
                val bottomLeft = luminance[y1 * width + x0].toInt() and 0xff
                val bottomRight = luminance[y1 * width + x1].toInt() and 0xff
                val top = topLeft + (topRight - topLeft) * fx
                val bottom = bottomLeft + (bottomRight - bottomLeft) * fx
                output[y * outputWidth + x] = (top + (bottom - top) * fy).roundToInt().coerceIn(0, 255).toByte()
            }
        }
        return GrayImage(outputWidth, outputHeight, output)
    }

    private companion object {
        const val MAX_PIXELS = 8_000_000L
    }
}
