package com.notadinle.app.data.score

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.core.graphics.createBitmap
import androidx.exifinterface.media.ExifInterface
import kotlin.math.roundToInt

internal data class LoadedBitmap(
    val bitmap: Bitmap,
    val pageIndex: Int,
    val pageCount: Int,
)

internal object ScoreImageLoader {
    private const val MAX_IMAGE_SIDE = 1800

    fun load(context: Context, uri: Uri, mimeType: String?, pageIndex: Int): LoadedBitmap {
        val displayName = queryDisplayName(context, uri).orEmpty()
        val isPdf = mimeType == "application/pdf" ||
            context.contentResolver.getType(uri) == "application/pdf" ||
            displayName.endsWith(".pdf", ignoreCase = true)

        return if (isPdf) renderPdfPage(context, uri, pageIndex) else {
            LoadedBitmap(decodeImage(context, uri), pageIndex = 0, pageCount = 1)
        }
    }

    private fun decodeImage(context: Context, uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalArgumentException("Bu görsel açılamadı. JPG veya PNG dosyası seçmeyi dene.")
        }

        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_IMAGE_SIDE) sampleSize *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: throw IllegalArgumentException("Görsel dosyası okunamadı.")
        return applyExifOrientation(context, uri, bitmap)
    }

    private fun applyExifOrientation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val rotation = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotation == 0f) return bitmap
        return Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            Matrix().apply { postRotate(rotation) },
            true,
        ).also { if (it !== bitmap) bitmap.recycle() }
    }

    private fun renderPdfPage(context: Context, uri: Uri, requestedPage: Int): LoadedBitmap {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalArgumentException("PDF dosyası açılamadı.")
        descriptor.use { fileDescriptor ->
            PdfRenderer(fileDescriptor).use { renderer ->
                if (renderer.pageCount == 0) throw IllegalArgumentException("PDF dosyasında sayfa bulunamadı.")
                val pageIndex = requestedPage.coerceIn(0, renderer.pageCount - 1)
                renderer.openPage(pageIndex).use { page ->
                    val scale = minOf(1.0, MAX_IMAGE_SIDE.toDouble() / maxOf(page.width, page.height))
                    val width = (page.width * scale).roundToInt().coerceAtLeast(1)
                    val height = (page.height * scale).roundToInt().coerceAtLeast(1)
                    val bitmap = createBitmap(width, height)
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return LoadedBitmap(bitmap, pageIndex, renderer.pageCount)
                }
            }
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else null
            }
    }.getOrNull()
}
