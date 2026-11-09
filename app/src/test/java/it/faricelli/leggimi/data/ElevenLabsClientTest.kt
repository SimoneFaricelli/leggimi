package it.faricelli.leggimi.data

import it.faricelli.leggimi.data.eleven.ElevenLabsClient
import it.faricelli.leggimi.data.eleven.ElevenLabsException
import it.faricelli.leggimi.data.eleven.VoiceSettings
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File

class ElevenLabsClientTest {
    private val server = MockWebServer()
    private lateinit var client: ElevenLabsClient
    private var key: String? = "chiave-test"

    @Before
    fun setUp() {
        server.start()
        client = ElevenLabsClient(OkHttpClient(), server.url("/"), retryDelayMs = 1) { key }
    }

    @After
    fun tearDown() = server.close()

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    @Test
    fun `modelli filtrati e chiave inviata`() = runTest {
        server.enqueue(json("""[
            {"model_id":"eleven_multilingual_v2","name":"Multilingual v2","can_do_text_to_speech":true,
             "languages":[{"language_id":"it","name":"Italian"}],"maximum_text_length_per_request":10000,"extra":1},
            {"model_id":"eleven_english_sts_v2","name":"STS","can_do_text_to_speech":false}
        ]"""))
        val models = client.models()
        assertEquals(listOf("eleven_multilingual_v2"), models.map { it.id })
        assertTrue(models[0].supportsItalian)
        val req = server.takeRequest()
        assertEquals("/v1/models", req.url.encodedPath)
        assertEquals("chiave-test", req.headers["xi-api-key"])
    }

    @Test
    fun `voci su piu pagine`() = runTest {
        server.enqueue(json("""{"voices":[{"voice_id":"a","name":"Anna","category":"premade","labels":{"gender":"female","accent":"italian"},"preview_url":"http://p/a.mp3"}],"has_more":true,"next_page_token":"T2"}"""))
        server.enqueue(json("""{"voices":[{"voice_id":"b","name":"Bruno","category":"cloned","labels":{}}],"has_more":false}"""))
        val voices = client.voices()
        assertEquals(listOf("Anna", "Bruno"), voices.map { it.name })
        assertEquals(listOf("female", "italian"), voices[0].tags)
        server.takeRequest()
        assertEquals("T2", server.takeRequest().url.queryParameter("next_page_token"))
    }

    @Test
    fun `sintesi scrive il file e invia i parametri`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "audio/mpeg").body("MP3DATA").build())
        val out = File.createTempFile("tts", ".mp3").also { it.delete() }
        client.synthesize("voce1", "eleven_multilingual_v2", "Ciao mondo.", VoiceSettings(), "it", "prima", "dopo", out)
        assertEquals("MP3DATA", out.readText())
        assertFalse(File(out.parentFile, out.name + ".part").exists())
        val req = server.takeRequest()
        assertEquals("/v1/text-to-speech/voce1", req.url.encodedPath)
        assertEquals("mp3_44100_128", req.url.queryParameter("output_format"))
        val body = req.body!!.utf8()
        assertTrue(body, body.contains("\"model_id\":\"eleven_multilingual_v2\""))
        assertTrue(body, body.contains("\"previous_text\":\"prima\""))
        assertTrue(body, body.contains("\"language_code\":\"it\""))
    }

    @Test
    fun `parametro rifiutato - riprova senza i campi opzionali`() = runTest {
        server.enqueue(json("""{"detail":{"status":"invalid_parameters","message":"language_code not supported"}}""", 400))
        server.enqueue(MockResponse.Builder().code(200).body("OK").build())
        val out = File.createTempFile("tts", ".mp3")
        client.synthesize("v", "m", "Testo", VoiceSettings(), "it", "p", "n", out)
        assertEquals("OK", out.readText())
        server.takeRequest()
        val retry = server.takeRequest().body!!.utf8()
        assertFalse(retry, retry.contains("language_code"))
        assertFalse(retry, retry.contains("previous_text"))
    }

    @Test
    fun `429 viene ritentato`() = runTest {
        server.enqueue(json("""{"detail":{"status":"too_many_concurrent_requests","message":"busy"}}""", 429))
        server.enqueue(json("""{"tier":"creator","character_count":10,"character_limit":100}"""))
        val sub = client.subscription()
        assertEquals("creator", sub.tier)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `errori tradotti`() = runTest {
        server.enqueue(json("""{"detail":{"status":"invalid_api_key","message":"Invalid API key"}}""", 401))
        expectKind(ElevenLabsException.Kind.UNAUTHORIZED) { client.subscription() }

        server.enqueue(json("""{"detail":{"status":"quota_exceeded","message":"This request exceeds your quota."}}""", 401))
        expectKind(ElevenLabsException.Kind.QUOTA) { client.models() }

        key = null
        expectKind(ElevenLabsException.Kind.NO_KEY) { client.models() }
    }

    private suspend fun expectKind(kind: ElevenLabsException.Kind, block: suspend () -> Unit) {
        try {
            block()
            fail("attesa eccezione $kind")
        } catch (e: ElevenLabsException) {
            assertEquals(kind, e.kind)
        }
    }
}
