package com.notadinle.app.data.di

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.notadinle.app.data.score.AndroidScoreImageRepository
import com.notadinle.app.domain.repository.PlaybackRepository
import com.notadinle.app.domain.usecase.RecognizeScoreUseCase
import com.notadinle.app.presentation.home.HomeViewModel

class HomeViewModelFactory(
    private val imageRepository: AndroidScoreImageRepository,
    private val recognizeScore: RecognizeScoreUseCase,
    private val playbackRepository: PlaybackRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(HomeViewModel::class.java)) {
            "Desteklenmeyen ViewModel: ${modelClass.name}"
        }
        return HomeViewModel(imageRepository, recognizeScore, playbackRepository) as T
    }
}
