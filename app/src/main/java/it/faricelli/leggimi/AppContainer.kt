package it.faricelli.leggimi

import android.app.Application
import androidx.room.Room
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import it.faricelli.leggimi.data.audio.AudioCache
import it.faricelli.leggimi.data.db.AppDatabase
import it.faricelli.leggimi.data.eleven.ElevenLabsClient
import it.faricelli.leggimi.data.library.LibraryRepository
import it.faricelli.leggimi.data.settings.SettingsRepository
import it.faricelli.leggimi.player.PlayerEngine
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class LeggimiApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(this)
        container = AppContainer(this)
    }
}

/** Dipendenze condivise dell'app (DI manuale). */
class AppContainer(app: Application) {
    private val db = Room.databaseBuilder(app, AppDatabase::class.java, "leggimi.db").build()
    val settings = SettingsRepository(app)
    val audioCache = AudioCache(File(app.filesDir, "audio"))
    val library = LibraryRepository(app, db.documents(), audioCache)

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    val elevenLabs = ElevenLabsClient(http) { settings.apiKey() }

    val engine: PlayerEngine by lazy { PlayerEngine(app, library, settings, elevenLabs, audioCache) }
}
