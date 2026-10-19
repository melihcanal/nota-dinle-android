package com.notadinle.app.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.notadinle.omr.adjustNoteDuration
import com.notadinle.omr.midiNoteName
import com.notadinle.app.data.score.AndroidScoreImageRepository
import com.notadinle.app.domain.model.Instrument
import com.notadinle.app.domain.model.ScoreDocument
import com.notadinle.app.domain.repository.PlaybackRepository
import com.notadinle.app.domain.usecase.RecognizeScoreUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HomeViewModel(
    private val imageRepository: AndroidScoreImageRepository,
    private val recognizeScore: RecognizeScoreUseCase,
    private val playbackRepository: PlaybackRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var loadedImage: com.notadinle.omr.GrayImage? = null
    private var loadJob: Job? = null
    private var recognitionJob: Job? = null
    private var playbackJob: Job? = null
    private var documentRequest = 0
    private var recognitionRequest = 0
    private var playbackRequest = 0

    fun selectDocument(document: ScoreDocument) {
        stopPlayback()
        loadPage(document, 0, resetDocument = true)
    }

    fun selectPage(pageIndex: Int) {
        val document = _uiState.value.document ?: return
        if (_uiState.value.pageCount <= 1 || _uiState.value.isLoading) return
        stopPlayback()
        loadPage(document, pageIndex.coerceIn(0, _uiState.value.pageCount - 1), resetDocument = false)
    }

    private fun loadPage(document: ScoreDocument, pageIndex: Int, resetDocument: Boolean) {
        val request = ++documentRequest
        loadJob?.cancel()
        recognitionRequest++
        recognitionJob?.cancel()
        loadedImage = null
        if (resetDocument) _uiState.value = HomeUiState(document = document, isLoading = true)
        else _uiState.update { it.copy(preview = null, notes = emptyList(), isLoading = true, isRecognizing = false, errorMessage = null) }

        loadJob = viewModelScope.launch {
            try {
                val loaded = imageRepository.load(document, pageIndex)
                if (request != documentRequest) {
                    loaded.preview.recycle()
                    return@launch
                }
                loadedImage = loaded.image
                _uiState.update {
                    it.copy(
                        preview = loaded.preview,
                        pageIndex = loaded.pageIndex,
                        pageCount = loaded.pageCount,
                        isLoading = false,
                        errorMessage = null,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (request == documentRequest) {
                    _uiState.update { it.copy(isLoading = false, errorMessage = error.localizedMessage ?: "Dosya açılamadı.") }
                }
            }
        }
    }

    fun recognize() {
        val image = loadedImage ?: return
        val request = ++recognitionRequest
        recognitionJob?.cancel()
        _uiState.update { it.copy(isRecognizing = true, errorMessage = null) }
        recognitionJob = viewModelScope.launch {
            try {
                val notes = recognizeScore(image)
                if (request == recognitionRequest) _uiState.update { it.copy(notes = notes, isRecognizing = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (request == recognitionRequest) {
                    _uiState.update {
                        it.copy(isRecognizing = false, errorMessage = error.localizedMessage ?: "Notalar bu sayfadan okunamadı.")
                    }
                }
            }
        }
    }

    fun selectInstrument(instrument: Instrument) {
        stopPlayback()
        _uiState.update { it.copy(instrument = instrument) }
    }

    fun setTempo(tempo: Int) {
        _uiState.update { it.copy(tempo = tempo.coerceIn(50, 160)) }
    }

    fun reportError(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    fun adjustPitch(noteIndex: Int, semitones: Int) {
        stopPlayback()
        _uiState.update { state ->
            state.copy(notes = state.notes.mapIndexed { index, note ->
                if (index != noteIndex) note else {
                    val midi = (note.midi + semitones).coerceIn(36, 96)
                    note.copy(midi = midi, name = midiNoteName(midi))
                }
            })
        }
    }

    fun adjustDuration(noteIndex: Int, direction: Int) {
        stopPlayback()
        _uiState.update { state ->
            state.copy(notes = state.notes.mapIndexed { index, note ->
                if (index == noteIndex) note.copy(durationBeats = adjustNoteDuration(note.durationBeats, direction)) else note
            })
        }
    }

    fun playOrStop() {
        if (_uiState.value.isPlaying) {
            stopPlayback()
            return
        }
        val state = _uiState.value
        if (state.notes.isEmpty()) return
        launchPlayback { playbackRepository.play(state.notes, state.instrument, state.tempo) }
    }

    fun playSample() {
        val state = _uiState.value
        launchPlayback { playbackRepository.playSample(state.instrument, state.tempo) }
    }

    private fun launchPlayback(play: suspend () -> Unit) {
        stopPlayback()
        val request = ++playbackRequest
        _uiState.update { it.copy(isPlaying = true, errorMessage = null) }
        playbackJob = viewModelScope.launch {
            try {
                play()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (request == playbackRequest) {
                    _uiState.update { it.copy(errorMessage = error.localizedMessage ?: "Ses çalınamadı.") }
                }
            } finally {
                if (request == playbackRequest) _uiState.update { it.copy(isPlaying = false) }
            }
        }
    }

    private fun stopPlayback() {
        playbackRequest++
        playbackJob?.cancel()
        playbackJob = null
        _uiState.update { if (it.isPlaying) it.copy(isPlaying = false) else it }
    }

    fun clearDocument() {
        stopPlayback()
        documentRequest++
        recognitionRequest++
        loadJob?.cancel()
        recognitionJob?.cancel()
        loadedImage = null
        _uiState.value = HomeUiState()
    }

    override fun onCleared() {
        loadJob?.cancel()
        recognitionJob?.cancel()
        playbackJob?.cancel()
        super.onCleared()
    }
}
