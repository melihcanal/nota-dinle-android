package com.notadinle.app.domain.repository

import com.notadinle.omr.DetectedNote
import com.notadinle.app.domain.model.Instrument

interface PlaybackRepository {
    suspend fun play(notes: List<DetectedNote>, instrument: Instrument, tempo: Int)
    suspend fun playSample(instrument: Instrument, tempo: Int)
}
