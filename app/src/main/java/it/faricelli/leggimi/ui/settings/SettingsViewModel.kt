package it.faricelli.leggimi.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.faricelli.leggimi.AppContainer
import it.faricelli.leggimi.data.eleven.Subscription
import it.faricelli.leggimi.data.eleven.VoiceSettings
import it.faricelli.leggimi.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface KeyCheck {
    data object Idle : KeyCheck
    data object Checking : KeyCheck
    data class Ok(val sub: Subscription) : KeyCheck
    data class Failed(val message: String) : KeyCheck
}

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings?> =
        container.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val keyCheck = MutableStateFlow<KeyCheck>(KeyCheck.Idle)
    val cacheBytes = MutableStateFlow(0L)

    init {
        refreshCacheSize()
        viewModelScope.launch {
            if (container.settings.current().hasApiKey) verify(null)
        }
    }

    suspend fun loadApiKey(): String? = container.settings.apiKey()

    fun saveAndVerify(key: String) {
        viewModelScope.launch {
            container.settings.setApiKey(key)
            if (key.isBlank()) keyCheck.value = KeyCheck.Idle else verify(key)
        }
    }

    private suspend fun verify(key: String?) {
        keyCheck.value = KeyCheck.Checking
        keyCheck.value = try {
            KeyCheck.Ok(container.elevenLabs.subscription(key))
        } catch (e: Exception) {
            KeyCheck.Failed(e.message ?: "Verifica non riuscita.")
        }
    }

    fun setVoiceSettings(v: VoiceSettings) {
        viewModelScope.launch {
            container.settings.setVoiceSettings(v)
            container.engine.invalidateAudio()
        }
    }

    fun setLanguage(code: String) {
        viewModelScope.launch {
            container.settings.setLanguageCode(code)
            container.engine.invalidateAudio()
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.audioCache.clear() }
            container.engine.invalidateAudio()
            refreshCacheSize()
        }
    }

    private fun refreshCacheSize() {
        viewModelScope.launch { cacheBytes.value = withContext(Dispatchers.IO) { container.audioCache.sizeBytes() } }
    }
}
