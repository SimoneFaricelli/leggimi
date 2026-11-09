package it.faricelli.leggimi.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import it.faricelli.leggimi.data.eleven.VoiceSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.dataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val hasApiKey: Boolean = false,
    val modelId: String = DEFAULT_MODEL_ID,
    val modelName: String = DEFAULT_MODEL_NAME,
    val voiceId: String? = null,
    val voiceName: String? = null,
    val voiceSettings: VoiceSettings = VoiceSettings(),
    val languageCode: String = "",
    val playbackSpeed: Float = 1f,
) {
    companion object {
        const val DEFAULT_MODEL_ID = "eleven_multilingual_v2"
        const val DEFAULT_MODEL_NAME = "Eleven Multilingual v2"
    }
}

class SettingsRepository(private val context: Context) {
    private object K {
        val apiKey = stringPreferencesKey("api_key_enc")
        val modelId = stringPreferencesKey("model_id")
        val modelName = stringPreferencesKey("model_name")
        val voiceId = stringPreferencesKey("voice_id")
        val voiceName = stringPreferencesKey("voice_name")
        val stability = floatPreferencesKey("stability")
        val similarity = floatPreferencesKey("similarity")
        val style = floatPreferencesKey("style")
        val speakerBoost = booleanPreferencesKey("speaker_boost")
        val voiceSpeed = floatPreferencesKey("voice_speed")
        val language = stringPreferencesKey("language")
        val playbackSpeed = floatPreferencesKey("playback_speed")
    }

    private var cachedKey: Pair<String, String?>? = null

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    private fun Preferences.toSettings(): AppSettings {
        val d = VoiceSettings()
        return AppSettings(
            hasApiKey = !this[K.apiKey].isNullOrEmpty(),
            modelId = this[K.modelId] ?: AppSettings.DEFAULT_MODEL_ID,
            modelName = this[K.modelName] ?: AppSettings.DEFAULT_MODEL_NAME,
            voiceId = this[K.voiceId],
            voiceName = this[K.voiceName],
            voiceSettings = VoiceSettings(
                stability = this[K.stability] ?: d.stability,
                similarityBoost = this[K.similarity] ?: d.similarityBoost,
                style = this[K.style] ?: d.style,
                useSpeakerBoost = this[K.speakerBoost] ?: d.useSpeakerBoost,
                speed = this[K.voiceSpeed] ?: d.speed,
            ),
            languageCode = this[K.language].orEmpty(),
            playbackSpeed = this[K.playbackSpeed] ?: 1f,
        )
    }

    /** La chiave API in chiaro, decifrata al volo (con una piccola cache in memoria). */
    suspend fun apiKey(): String? = withContext(Dispatchers.IO) {
        val enc = context.dataStore.data.first()[K.apiKey] ?: return@withContext null
        cachedKey?.takeIf { it.first == enc }?.let { return@withContext it.second }
        SecretStore.decrypt(enc).also { cachedKey = enc to it }
    }

    suspend fun setApiKey(key: String) {
        val trimmed = key.trim()
        val enc = if (trimmed.isEmpty()) null else withContext(Dispatchers.IO) { SecretStore.encrypt(trimmed) }
        context.dataStore.edit { if (enc == null) it.remove(K.apiKey) else it[K.apiKey] = enc }
    }

    suspend fun setModel(id: String, name: String) = context.dataStore.edit {
        it[K.modelId] = id
        it[K.modelName] = name
    }

    suspend fun setVoice(id: String, name: String) = context.dataStore.edit {
        it[K.voiceId] = id
        it[K.voiceName] = name
    }

    suspend fun setVoiceSettings(v: VoiceSettings) = context.dataStore.edit {
        it[K.stability] = v.stability
        it[K.similarity] = v.similarityBoost
        it[K.style] = v.style
        it[K.speakerBoost] = v.useSpeakerBoost
        it[K.voiceSpeed] = v.speed
    }

    suspend fun setLanguageCode(code: String) = context.dataStore.edit { it[K.language] = code.trim().lowercase() }

    suspend fun setPlaybackSpeed(speed: Float) = context.dataStore.edit { it[K.playbackSpeed] = speed }
}
