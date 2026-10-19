package com.notadinle.app

import android.app.Application
import com.notadinle.app.data.audio.LocalPlaybackRepository
import com.notadinle.app.data.omr.LocalOmrRepository
import com.notadinle.app.data.score.AndroidScoreImageRepository
import com.notadinle.app.data.di.HomeViewModelFactory
import com.notadinle.app.domain.usecase.RecognizeScoreUseCase

class NotaDinleApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

class AppContainer(application: Application) {
    private val omrRepository = LocalOmrRepository()
    private val imageRepository = AndroidScoreImageRepository(application)
    private val playbackRepository = LocalPlaybackRepository(application)

    val homeViewModelFactory = HomeViewModelFactory(
        imageRepository = imageRepository,
        recognizeScore = RecognizeScoreUseCase(omrRepository),
        playbackRepository = playbackRepository,
    )
}
