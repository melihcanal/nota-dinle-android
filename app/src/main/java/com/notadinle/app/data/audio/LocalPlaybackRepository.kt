package com.notadinle.app.data.audio

import android.content.Context
import com.notadinle.omr.DetectedNote
import com.notadinle.app.domain.model.Instrument
import com.notadinle.app.domain.repository.PlaybackRepository

class LocalPlaybackRepository(context: Context) : PlaybackRepository {
    private val appContext = context.applicationContext

    override suspend fun play(notes: List<DetectedNote>, instrument: Instrument, tempo: Int) {
        TonePlayer.playNotes(appContext, notes, instrument, tempo)
    }

    override suspend fun playSample(instrument: Instrument, tempo: Int) {
        TonePlayer.playSample(appContext, instrument, tempo)
    }
}
