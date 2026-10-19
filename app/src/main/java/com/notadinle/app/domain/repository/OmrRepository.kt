package com.notadinle.app.domain.repository

import com.notadinle.omr.DetectedNote
import com.notadinle.omr.GrayImage

interface OmrRepository {
    suspend fun recognize(image: GrayImage): List<DetectedNote>
}
