package it.faricelli.leggimi.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.faricelli.leggimi.AppContainer
import it.faricelli.leggimi.data.db.DocumentEntity
import it.faricelli.leggimi.data.settings.AppSettings
import it.faricelli.leggimi.player.PlayerState
import it.faricelli.leggimi.player.SleepTimer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ReaderViewModel(private val container: AppContainer, val docId: String) : ViewModel() {
    private val engine = container.engine

    val player: StateFlow<PlayerState> = engine.state
    val entity: StateFlow<DocumentEntity?> =
        container.library.observe(docId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val settings: StateFlow<AppSettings?> =
        container.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** null = in caricamento, false = documento non trovato. */
    val found = MutableStateFlow<Boolean?>(null)

    init {
        viewModelScope.launch { found.value = engine.open(docId) != null }
    }

    fun togglePlay() = engine.togglePlay()
    fun next() = engine.next()
    fun previous() = engine.previous()
    fun moveToBlock(block: Int) = engine.moveToBlock(block)
    fun setSpeed(speed: Float) = engine.setSpeed(speed)
    fun retry() = engine.retry()

    fun setSleepTimer(minutes: Int?) = engine.setSleepTimer(
        when (minutes) {
            null -> SleepTimer.Off
            -1 -> SleepTimer.EndOfChapter
            else -> SleepTimer.At(minutes, System.currentTimeMillis() + minutes * 60_000L)
        }
    )

    fun resetVoiceToDefault() {
        viewModelScope.launch {
            container.library.setDocumentVoice(docId, null, null)
            container.library.setDocumentModel(docId, null, null)
            engine.invalidateAudio()
        }
    }
}
