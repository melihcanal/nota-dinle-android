package com.notadinle.app.presentation.home

import android.graphics.Bitmap
import com.notadinle.omr.DetectedNote
import com.notadinle.app.domain.model.Instrument
import com.notadinle.app.domain.model.ScoreDocument

data class HomeUiState(
    val document: ScoreDocument? = null,
    val preview: Bitmap? = null,
    val pageIndex: Int = 0,
    val pageCount: Int = 1,
    val notes: List<DetectedNote> = emptyList(),
    val instrument: Instrument = Instrument.Piano,
    val tempo: Int = 92,
    val isLoading: Boolean = false,
    val isRecognizing: Boolean = false,
    val isPlaying: Boolean = false,
    val errorMessage: String? = null,
)
