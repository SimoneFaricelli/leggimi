package it.faricelli.leggimi.ui.picker

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.faricelli.leggimi.AppContainer
import it.faricelli.leggimi.data.eleven.TtsModel
import it.faricelli.leggimi.data.eleven.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>
    data class Loaded<T>(val value: T) : Loadable<T>
    data class Failed(val message: String) : Loadable<Nothing>
}

/** Id della voce/modello attualmente scelti per il bersaglio (predefinito o documento). */
private fun AppContainer.selection(docId: String?) = combine(
    settings.settings,
    if (docId == null) flow { emit(null) } else library.observe(docId),
) { s, doc ->
    Selection(
        voiceId = doc?.voiceId ?: s.voiceId,
        modelId = doc?.modelId ?: s.modelId,
        isOverride = doc?.voiceId != null || doc?.modelId != null,
    )
}

data class Selection(val voiceId: String?, val modelId: String, val isOverride: Boolean)

class VoicePickerViewModel(private val container: AppContainer, private val docId: String?) : ViewModel() {
    val voices = MutableStateFlow<Loadable<List<Voice>>>(Loadable.Loading)
    val selection: StateFlow<Selection?> = container.selection(docId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val playingPreview = MutableStateFlow<String?>(null)
    private var mediaPlayer: MediaPlayer? = null

    init { load() }

    fun load() {
        voices.value = Loadable.Loading
        viewModelScope.launch {
            voices.value = try {
                Loadable.Loaded(container.elevenLabs.voices().sortedWith(compareBy({ categoryOrder(it.category) }, { it.name.lowercase() })))
            } catch (e: Exception) {
                Loadable.Failed(e.message ?: "Impossibile caricare le voci.")
            }
        }
    }

    private fun categoryOrder(c: String?) = when (c) {
        "cloned", "generated", "professional" -> 0
        "premade" -> 2
        else -> 1
    }

    fun select(voice: Voice?, onDone: () -> Unit) {
        stopPreview()
        viewModelScope.launch {
            if (docId == null) {
                if (voice != null) container.settings.setVoice(voice.id, voice.name)
            } else {
                container.library.setDocumentVoice(docId, voice?.id, voice?.name)
            }
            container.engine.invalidateAudio()
            onDone()
        }
    }

    fun togglePreview(voice: Voice) {
        if (playingPreview.value == voice.id) { stopPreview(); return }
        stopPreview()
        val url = voice.previewUrl ?: return
        if (container.engine.player.isPlaying) container.engine.pause()
        playingPreview.value = voice.id
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            setOnPreparedListener { it.start() }
            setOnCompletionListener { stopPreview() }
            setOnErrorListener { _, _, _ -> stopPreview(); true }
            runCatching { setDataSource(url); prepareAsync() }.onFailure { stopPreview() }
        }
    }

    fun stopPreview() {
        mediaPlayer?.runCatching { release() }
        mediaPlayer = null
        playingPreview.value = null
    }

    override fun onCleared() = stopPreview()
}

class ModelPickerViewModel(private val container: AppContainer, private val docId: String?) : ViewModel() {
    val models = MutableStateFlow<Loadable<List<TtsModel>>>(Loadable.Loading)
    val selection: StateFlow<Selection?> = container.selection(docId).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init { load() }

    fun load() {
        models.value = Loadable.Loading
        viewModelScope.launch {
            models.value = try {
                Loadable.Loaded(container.elevenLabs.models())
            } catch (e: Exception) {
                Loadable.Failed(e.message ?: "Impossibile caricare i modelli.")
            }
        }
    }

    fun select(model: TtsModel?, onDone: () -> Unit) {
        viewModelScope.launch {
            if (docId == null) {
                if (model != null) container.settings.setModel(model.id, model.name)
            } else {
                container.library.setDocumentModel(docId, model?.id, model?.name)
            }
            container.engine.invalidateAudio()
            onDone()
        }
    }
}
