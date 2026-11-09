package it.faricelli.leggimi.data.eleven

import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import kotlin.coroutines.resumeWithException

class ElevenLabsException(val kind: Kind, message: String, val httpCode: Int = 0) : IOException(message) {
    enum class Kind { NO_KEY, UNAUTHORIZED, QUOTA, RATE_LIMIT, BAD_REQUEST, SERVER, NETWORK }
}

class ElevenLabsClient(
    private val http: OkHttpClient,
    private val baseUrl: HttpUrl = "https://api.elevenlabs.io".toHttpUrl(),
    private val retryDelayMs: Long = 1000,
    private val apiKey: suspend () -> String?,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    suspend fun models(key: String? = null): List<TtsModel> {
        val body = get(url("v1/models"), key)
        return json.decodeFromString<List<TtsModel>>(body).filter { it.canDoTts }
    }

    /** Tutte le voci dell'account (premade, clonate, aggiunte dalla Voice Library), pagina dopo pagina. */
    suspend fun voices(key: String? = null): List<Voice> {
        val all = mutableListOf<Voice>()
        var token: String? = null
        do {
            val u = url("v2/voices").newBuilder()
                .addQueryParameter("page_size", "100")
                .apply { token?.let { addQueryParameter("next_page_token", it) } }
                .build()
            val page = json.decodeFromString<VoicesPage>(get(u, key))
            all += page.voices
            token = page.nextPageToken
        } while (page.hasMore && !token.isNullOrEmpty() && all.size < 5000)
        return all.distinctBy { it.id }
    }

    suspend fun subscription(key: String? = null): Subscription =
        json.decodeFromString(get(url("v1/user/subscription"), key))

    /**
     * Sintetizza [text] e scrive l'mp3 in [out] (prima su un file temporaneo, poi rinomina).
     * Se il modello rifiuta qualche parametro opzionale, riprova togliendolo.
     */
    suspend fun synthesize(
        voiceId: String,
        modelId: String,
        text: String,
        settings: VoiceSettings?,
        languageCode: String?,
        previousText: String?,
        nextText: String?,
        out: File,
    ) {
        val attempts = listOf(
            TtsRequest(text, modelId, settings, languageCode, previousText, nextText),
            TtsRequest(text, modelId, settings, null, null, null),
            TtsRequest(text, modelId, settings?.copy(speed = 1f, style = 0f), null, null, null),
            TtsRequest(text, modelId, null, null, null, null),
        ).distinct()
        val u = url("v1/text-to-speech/$voiceId").newBuilder()
            .addQueryParameter("output_format", "mp3_44100_128").build()
        var lastError: ElevenLabsException? = null
        for (req in attempts) {
            try {
                val body = json.encodeToString(TtsRequest.serializer(), req).toRequestBody(JSON)
                execute(Request.Builder().url(u).post(body).header("Accept", "audio/mpeg"), null) { resp ->
                    out.parentFile?.mkdirs()
                    val tmp = File(out.parentFile, out.name + ".part")
                    resp.body.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    if (tmp.length() == 0L) throw ElevenLabsException(ElevenLabsException.Kind.SERVER, "ElevenLabs ha restituito un audio vuoto.")
                    if (!tmp.renameTo(out)) {
                        tmp.delete()
                        throw IOException("Impossibile salvare l'audio.")
                    }
                }
                return
            } catch (e: ElevenLabsException) {
                if (e.kind != ElevenLabsException.Kind.BAD_REQUEST) throw e
                lastError = e
            }
        }
        throw lastError!!
    }

    private fun url(path: String): HttpUrl = baseUrl.newBuilder().addPathSegments(path).build()

    private suspend fun get(u: HttpUrl, key: String?): String =
        execute(Request.Builder().url(u).get(), key) { it.body.string() }

    /** Esegue con la chiave API e ritenta su 429/5xx con attesa crescente. */
    private suspend fun <T> execute(builder: Request.Builder, key: String?, onSuccess: (Response) -> T): T {
        val apiKey = key?.takeIf { it.isNotBlank() } ?: apiKey()?.takeIf { it.isNotBlank() }
            ?: throw ElevenLabsException(ElevenLabsException.Kind.NO_KEY, "Inserisci la chiave API di ElevenLabs nelle impostazioni.")
        val request = builder.header("xi-api-key", apiKey.trim()).build()
        var attempt = 0
        while (true) {
            val response = try {
                http.newCall(request).await()
            } catch (e: IOException) {
                if (attempt < MAX_RETRIES) { delay(retryDelayMs shl attempt); attempt++; continue }
                throw ElevenLabsException(ElevenLabsException.Kind.NETWORK, "Connessione a ElevenLabs non riuscita. Controlla la rete.")
            }
            response.use { resp ->
                if (resp.isSuccessful) return onSuccess(resp)
                val error = toError(resp.code, resp.body.string())
                val retryable = error.kind == ElevenLabsException.Kind.RATE_LIMIT || error.kind == ElevenLabsException.Kind.SERVER
                if (!retryable || attempt >= MAX_RETRIES) throw error
            }
            delay(retryDelayMs shl attempt)
            attempt++
        }
    }

    internal fun toError(code: Int, body: String): ElevenLabsException {
        val detail = runCatching { json.parseToJsonElement(body) }.getOrNull()
            ?.let { (it as? JsonObject)?.get("detail") }
        val status = (detail as? JsonObject)?.get("status")?.primitive()
            ?: (detail as? JsonObject)?.get("code")?.primitive()
        val message = when (detail) {
            is JsonObject -> detail["message"]?.primitive()
            is JsonPrimitive -> detail.contentOrNull
            is JsonArray -> detail.mapNotNull { (it as? JsonObject)?.get("msg")?.primitive() }.joinToString("; ")
            else -> null
        }.orEmpty()
        val kind = when {
            status == "quota_exceeded" || message.contains("quota", true) -> ElevenLabsException.Kind.QUOTA
            code == 401 || code == 403 -> ElevenLabsException.Kind.UNAUTHORIZED
            code == 429 -> ElevenLabsException.Kind.RATE_LIMIT
            code >= 500 -> ElevenLabsException.Kind.SERVER
            else -> ElevenLabsException.Kind.BAD_REQUEST
        }
        val text = when (kind) {
            ElevenLabsException.Kind.QUOTA -> "Crediti ElevenLabs esauriti per questo mese."
            ElevenLabsException.Kind.UNAUTHORIZED ->
                if (status == "missing_permissions") "La chiave API non ha i permessi necessari ($message)."
                else "Chiave API di ElevenLabs non valida."
            ElevenLabsException.Kind.RATE_LIMIT -> "Troppe richieste a ElevenLabs. Riprova tra poco."
            ElevenLabsException.Kind.SERVER -> "ElevenLabs non risponde (errore $code). Riprova più tardi."
            else -> "Richiesta rifiutata da ElevenLabs" + if (message.isNotEmpty()) ": $message" else " (errore $code)."
        }
        return ElevenLabsException(kind, text, code)
    }

    private fun JsonElement.primitive(): String? = (this as? JsonPrimitive)?.contentOrNull ?: runCatching { jsonPrimitive.contentOrNull }.getOrNull()

    private companion object {
        const val MAX_RETRIES = 3
        val JSON = "application/json".toMediaType()
    }
}

/** Esegue la chiamata in modo asincrono e la annulla se la coroutine viene cancellata. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, r, _ -> r.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
