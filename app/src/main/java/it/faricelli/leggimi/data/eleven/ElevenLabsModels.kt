package it.faricelli.leggimi.data.eleven

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TtsModel(
    @SerialName("model_id") val id: String,
    val name: String = "",
    val description: String = "",
    @SerialName("can_do_text_to_speech") val canDoTts: Boolean = true,
    val languages: List<ModelLanguage> = emptyList(),
    @SerialName("maximum_text_length_per_request") val maxTextLength: Int? = null,
) {
    val supportsItalian: Boolean get() = languages.any { it.id.equals("it", true) || it.name.contains("ital", true) }
}

@Serializable
data class ModelLanguage(@SerialName("language_id") val id: String = "", val name: String = "")

@Serializable
data class Voice(
    @SerialName("voice_id") val id: String,
    val name: String = "",
    val category: String? = null,
    val description: String? = null,
    val labels: Map<String, String?> = emptyMap(),
    @SerialName("preview_url") val previewUrl: String? = null,
) {
    /** Etichette leggibili (genere, età, accento, uso). */
    val tags: List<String>
        get() = listOf("gender", "age", "accent", "language", "use_case", "descriptive", "description")
            .mapNotNull { labels[it]?.replace('_', ' ')?.takeIf(String::isNotBlank) }
            .distinct()
}

@Serializable
internal data class VoicesPage(
    val voices: List<Voice> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("next_page_token") val nextPageToken: String? = null,
)

@Serializable
data class Subscription(
    val tier: String = "",
    @SerialName("character_count") val characterCount: Long = 0,
    @SerialName("character_limit") val characterLimit: Long = 0,
    @SerialName("next_character_count_reset_unix") val nextResetUnix: Long? = null,
)

@Serializable
data class VoiceSettings(
    val stability: Float = 0.5f,
    @SerialName("similarity_boost") val similarityBoost: Float = 0.75f,
    val style: Float = 0f,
    @SerialName("use_speaker_boost") val useSpeakerBoost: Boolean = true,
    val speed: Float = 1f,
)

@Serializable
internal data class TtsRequest(
    val text: String,
    @SerialName("model_id") val modelId: String,
    @SerialName("voice_settings") val voiceSettings: VoiceSettings? = null,
    @SerialName("language_code") val languageCode: String? = null,
    @SerialName("previous_text") val previousText: String? = null,
    @SerialName("next_text") val nextText: String? = null,
)
