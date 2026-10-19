package com.notadinle.app.data.score

import android.content.Context
import android.graphics.Bitmap
import androidx.core.net.toUri
import com.notadinle.app.domain.model.ScoreDocument
import com.notadinle.omr.GrayImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LoadedScore(
    val preview: Bitmap,
    val image: GrayImage,
    val pageIndex: Int,
    val pageCount: Int,
)

class AndroidScoreImageRepository(context: Context) {
    private val appContext = context.applicationContext

    suspend fun load(document: ScoreDocument, pageIndex: Int = 0): LoadedScore = withContext(Dispatchers.IO) {
        val loaded = ScoreImageLoader.load(
            context = appContext,
            uri = document.uri.toUri(),
            mimeType = document.mimeType,
            pageIndex = pageIndex,
        )
        LoadedScore(
            preview = loaded.bitmap,
            image = loaded.bitmap.toGrayImage(),
            pageIndex = loaded.pageIndex,
            pageCount = loaded.pageCount,
        )
    }

    private fun Bitmap.toGrayImage(): GrayImage {
        val argb = IntArray(width * height)
        getPixels(argb, 0, width, 0, 0, width, height)
        val gray = ByteArray(argb.size) { index ->
            val color = argb[index]
            val alpha = (color ushr 24) and 0xff
            val luminance = (((color shr 16) and 0xff) * 299 + ((color shr 8) and 0xff) * 587 + (color and 0xff) * 114) / 1000
            ((luminance * alpha + 255 * (255 - alpha)) / 255).toByte()
        }
        return GrayImage(width, height, gray)
    }
}
