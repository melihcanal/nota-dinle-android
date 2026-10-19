package com.notadinle.app.domain.usecase

import com.notadinle.omr.DetectedNote
import com.notadinle.omr.GrayImage
import com.notadinle.app.domain.repository.OmrRepository

class RecognizeScoreUseCase(private val repository: OmrRepository) {
    suspend operator fun invoke(image: GrayImage): List<DetectedNote> = repository.recognize(image)
}
