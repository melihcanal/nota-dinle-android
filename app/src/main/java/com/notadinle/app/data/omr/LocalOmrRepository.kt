package com.notadinle.app.data.omr

import com.notadinle.omr.DetectedNote
import com.notadinle.omr.GrayImage
import com.notadinle.omr.StaffNoteRecognizer
import com.notadinle.app.domain.repository.OmrRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalOmrRepository : OmrRepository {
    override suspend fun recognize(image: GrayImage): List<DetectedNote> =
        withContext(Dispatchers.Default) { StaffNoteRecognizer.recognize(image) }
}
