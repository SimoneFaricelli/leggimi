package it.faricelli.leggimi.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import it.faricelli.leggimi.data.audio.AudioCache
import it.faricelli.leggimi.data.eleven.ElevenLabsClient
import it.faricelli.leggimi.data.eleven.ElevenLabsException
import it.faricelli.leggimi.data.library.LibraryRepository
import it.faricelli.leggimi.data.library.LoadedDocument
import it.faricelli.leggimi.data.settings.SettingsRepository
import it.faricelli.leggimi.data.text.Segment
import it.faricelli.leggimi.data.text.UserFacingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface SleepTimer {
    data object Off : SleepTimer
    data class At(val minutes: Int, val endAtMillis: Long) : SleepTimer
    data object EndOfChapter : SleepTimer
}

data class PlayerState(
    val document: LoadedDocument? = null,
    val currentSegment: Int = 0,
    val isPlaying: Boolean = false,
    /** Sta generando l'audio che serve subito (non il prefetch). */
    val isLoading: Boolean = false,
    val error: String? = null,
    /** L'errore si risolve dalle impostazioni (chiave mancante o non valida, nessuna voce). */
    val errorNeedsSettings: Boolean = false,
    val speed: Float = 1f,
    val sleepTimer: SleepTimer = SleepTimer.Off,
    val sleepRemainingMs: Long = 0,
)

/**
 * Cuore della lettura: trasforma i segmenti del documento in una playlist ExoPlayer,
 * generando con ElevenLabs il segmento corrente e i successivi in anticipo ([prefetchTarget]).
 * Tutti i metodi pubblici vanno chiamati dal main thread.
 */
@OptIn(UnstableApi::class)
class PlayerEngine(
    context: Context,
    private val library: LibraryRepository,
    private val settings: SettingsRepository,
    private val client: ElevenLabsClient,
    private val cache: AudioCache,
) {
    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
            true,
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** Un solo thread per i salvataggi, così arrivano al database nell'ordine in cui sono chiesti. */
    private val persistDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var doc: LoadedDocument? = null
    private val current = MutableStateFlow(0)
    private var queuedUpTo = -1
    private var pendingSeekMs = 0L
    private var wantPlay = false
    private var fillJob: Job? = null
    private var sleepJob: Job? = null
    private var tickJob: Job? = null

    private val lastIndex: Int get() = (doc?.segmented?.segments?.size ?: 1) - 1

    init {
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val seg = mediaItem?.mediaId?.toIntOrNull() ?: return
                val previous = current.value
                current.value = seg
                _state.update { it.copy(currentSegment = seg) }
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                    _state.value.sleepTimer == SleepTimer.EndOfChapter && chapterOf(seg) != chapterOf(previous)
                ) {
                    pause()
                    setSleepTimer(SleepTimer.Off)
                }
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) persist(seg, 0)
                scope.launch { trimPlaylist() }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.update { it.copy(isPlaying = isPlaying) }
                if (isPlaying) startTicker() else {
                    tickJob?.cancel()
                    persist(current.value, player.currentPosition)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState != Player.STATE_ENDED) return
                if (current.value >= lastIndex && queuedUpTo >= lastIndex) {
                    wantPlay = false
                    _state.update { it.copy(isLoading = false) }
                } else if (wantPlay) {
                    // Il segmento successivo è ancora in generazione: il fill loop riprenderà.
                    _state.update { it.copy(isLoading = true) }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                _state.update { it.copy(error = "Errore di riproduzione: ${error.errorCodeName}", isLoading = false) }
            }
        })
        scope.launch {
            val speed = settings.current().playbackSpeed
            player.playbackParameters = PlaybackParameters(speed)
            _state.update { it.copy(speed = speed) }
        }
    }

    /** Player esposto alla MediaSession: i comandi di notifica/cuffie passano dall'engine. */
    val sessionPlayer: Player by lazy {
        object : ForwardingPlayer(player) {
            private val extra = listOf(
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_PLAY_PAUSE,
            )

            override fun getAvailableCommands(): Player.Commands =
                super.getAvailableCommands().buildUpon().addAll(*extra.toIntArray()).build()

            override fun isCommandAvailable(command: Int): Boolean = command in extra || super.isCommandAvailable(command)
            override fun play() = this@PlayerEngine.play()
            override fun pause() = this@PlayerEngine.pause()
            override fun setPlayWhenReady(playWhenReady: Boolean) = if (playWhenReady) play() else pause()
            override fun prepare() { if (player.mediaItemCount > 0) super.prepare() }
            override fun seekToDefaultPosition() = Unit
            override fun seekToNext() = next()
            override fun seekToNextMediaItem() = next()
            override fun seekToPrevious() = previous()
            override fun seekToPreviousMediaItem() = previous()
            override fun stop() = pause()
        }
    }

    /** Carica il documento (se non è già quello attivo) senza generare audio. */
    suspend fun open(docId: String): LoadedDocument? {
        doc?.takeIf { it.entity.id == docId }?.let { return it }
        val loaded = library.load(docId) ?: return null
        stopInternal()
        doc = loaded
        val seg = loaded.entity.positionSegment.coerceIn(0, lastIndex)
        current.value = seg
        queuedUpTo = seg - 1
        pendingSeekMs = loaded.entity.positionMs
        _state.update {
            PlayerState(document = loaded, currentSegment = seg, speed = it.speed)
        }
        return loaded
    }

    /** Chiude il documento se è quello attivo (es. quando viene eliminato). */
    fun close(docId: String) {
        if (doc?.entity?.id != docId) return
        stopInternal()
        doc = null
        _state.update { PlayerState(speed = it.speed) }
    }

    fun play() {
        doc ?: return
        val st = _state.value
        when {
            player.mediaItemCount == 0 -> startAt(current.value, pendingSeekMs, play = true)
            player.playbackState == Player.STATE_ENDED && current.value >= lastIndex && queuedUpTo >= lastIndex ->
                startAt(0, 0, play = true)
            else -> {
                wantPlay = true
                if (st.error != null && fillJob?.isActive != true) retry()
                if (player.playbackState == Player.STATE_ENDED) _state.update { it.copy(isLoading = true) }
                else player.play()
            }
        }
    }

    fun pause() {
        wantPlay = false
        player.pause()
        _state.update { it.copy(isLoading = false) }
    }

    fun togglePlay() = if (wantPlay || player.isPlaying) pause() else play()

    fun next() {
        if (current.value < lastIndex) moveTo(current.value + 1)
    }

    fun previous() {
        if (player.isPlaying && player.currentPosition > 3000) player.seekTo(0) else if (current.value > 0) moveTo(current.value - 1)
    }

    /** Sposta la lettura al segmento [segment]; se si sta ascoltando continua da lì. */
    fun moveTo(segment: Int) {
        val target = segment.coerceIn(0, lastIndex)
        val itemIndex = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == target.toString() }
        when {
            itemIndex != null -> player.seekTo(itemIndex, 0)
            wantPlay || player.isPlaying -> startAt(target, 0, play = true)
            else -> {
                stopInternal()
                current.value = target
                queuedUpTo = target - 1
                pendingSeekMs = 0
                _state.update { it.copy(currentSegment = target, error = null) }
                persist(target, 0)
            }
        }
    }

    fun moveToBlock(block: Int) {
        val d = doc ?: return
        moveTo(d.segmented.segmentForBlock(block))
    }

    /** Voce, modello o impostazioni voce cambiati: l'audio in coda non è più valido. */
    fun invalidateAudio() {
        doc ?: return
        if (wantPlay) startAt(current.value, 0, play = true) else {
            val seg = current.value
            stopInternal()
            queuedUpTo = seg - 1
            pendingSeekMs = 0
        }
    }

    fun retry() {
        val d = doc ?: return
        _state.update { it.copy(error = null, errorNeedsSettings = false, isLoading = wantPlay) }
        if (player.mediaItemCount == 0) {
            startAt(current.value, pendingSeekMs, play = true)
            return
        }
        fillJob?.cancel()
        fillJob = scope.launch { fillLoop(d) }
    }

    fun setSpeed(speed: Float) {
        player.playbackParameters = PlaybackParameters(speed)
        _state.update { it.copy(speed = speed) }
        scope.launch { settings.setPlaybackSpeed(speed) }
    }

    fun setSleepTimer(timer: SleepTimer) {
        sleepJob?.cancel()
        _state.update { it.copy(sleepTimer = timer, sleepRemainingMs = 0) }
        if (timer is SleepTimer.At) {
            sleepJob = scope.launch {
                while (isActive) {
                    val remaining = timer.endAtMillis - System.currentTimeMillis()
                    if (remaining <= 0) break
                    _state.update { it.copy(sleepRemainingMs = remaining) }
                    delay(minOf(1000L, remaining))
                }
                pause()
                _state.update { it.copy(sleepTimer = SleepTimer.Off, sleepRemainingMs = 0) }
            }
        }
    }

    private fun startAt(segment: Int, positionMs: Long, play: Boolean) {
        val d = doc ?: return
        stopInternal()
        queuedUpTo = segment - 1
        current.value = segment
        pendingSeekMs = positionMs
        wantPlay = play
        _state.update { it.copy(currentSegment = segment, error = null, errorNeedsSettings = false, isLoading = play) }
        persist(segment, positionMs)
        fillJob = scope.launch { fillLoop(d) }
    }

    private fun stopInternal() {
        if (doc != null && player.mediaItemCount > 0) persist(current.value, player.currentPosition)
        fillJob?.cancel()
        fillJob = null
        wantPlay = false
        player.stop()
        player.clearMediaItems()
        _state.update { it.copy(isLoading = false, isPlaying = false) }
    }

    /** Genera e accoda i segmenti finché la coda copre quanto indicato da [prefetchTarget]. */
    private suspend fun fillLoop(d: LoadedDocument) {
        val segments = d.segmented.segments
        while (true) {
            current.first { prefetchTarget(segments, it) > queuedUpTo }
            val next = queuedUpTo + 1
            val file = try {
                audioFor(d, next)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onGenerationError(e)
                return
            }
            player.addMediaItem(mediaItem(d, next, file))
            queuedUpTo = next
            if (player.mediaItemCount == 1) {
                player.prepare()
                player.seekTo(0, pendingSeekMs)
                pendingSeekMs = 0
                player.playWhenReady = wantPlay
                _state.update { it.copy(isLoading = false) }
            } else if (player.playbackState == Player.STATE_ENDED && wantPlay) {
                player.seekTo(player.mediaItemCount - 1, 0)
                player.play()
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    private fun onGenerationError(e: Exception) {
        val needsSettings = (e is ElevenLabsException && e.kind in setOf(
            ElevenLabsException.Kind.NO_KEY, ElevenLabsException.Kind.UNAUTHORIZED,
        )) || e is NoVoiceException
        val message = when (e) {
            is ElevenLabsException, is UserFacingException -> e.message
            else -> "Errore durante la generazione dell'audio: ${e.message ?: e.javaClass.simpleName}"
        }
        _state.update { it.copy(error = message, errorNeedsSettings = needsSettings, isLoading = false) }
    }

    private suspend fun audioFor(d: LoadedDocument, index: Int): File {
        val s = settings.current()
        val entity = library.get(d.entity.id) ?: d.entity
        val voiceId = entity.voiceId ?: s.voiceId ?: throw NoVoiceException()
        val modelId = entity.modelId ?: s.modelId
        val segments = d.segmented.segments
        val seg = segments[index]
        val key = AudioCache.key(seg.text, voiceId, modelId, s.voiceSettings.toString(), s.languageCode, FORMAT)
        val file = cache.fileFor(d.entity.id, key)
        if (file.length() > 0) return file
        withContext(Dispatchers.IO) {
            client.synthesize(
                voiceId = voiceId,
                modelId = modelId,
                text = seg.text,
                settings = s.voiceSettings,
                languageCode = s.languageCode.ifBlank { null },
                previousText = contextBefore(segments, index),
                nextText = contextAfter(segments, index),
                out = file,
            )
        }
        return file
    }

    private fun mediaItem(d: LoadedDocument, index: Int, file: File): MediaItem {
        val chapterTitle = d.segmented.chapters.lastOrNull { it.index == chapterOf(index) }?.title
        return MediaItem.Builder()
            .setMediaId(index.toString())
            .setUri(Uri.fromFile(file))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(d.parsed.title)
                    .setArtist(chapterTitle ?: "Leggimi")
                    .setAlbumTitle(d.parsed.title)
                    .setTrackNumber(index + 1)
                    .setTotalTrackCount(d.segmented.segments.size)
                    .build()
            )
            .build()
    }

    private fun chapterOf(segment: Int): Int = doc?.segmented?.segments?.getOrNull(segment)?.chapter ?: 0

    /** Toglie dalla playlist gli elementi già ascoltati, tenendone uno per il "precedente". */
    private fun trimPlaylist() {
        val idx = player.currentMediaItemIndex
        if (idx > 2) player.removeMediaItems(0, idx - 1)
    }

    private fun startTicker() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                delay(5000)
                persist(current.value, player.currentPosition)
            }
        }
    }

    private fun persist(segment: Int, ms: Long) {
        val id = doc?.entity?.id ?: return
        scope.launch(persistDispatcher + NonCancellable) { library.savePosition(id, segment, ms) }
    }

    class NoVoiceException : UserFacingException("Scegli una voce ElevenLabs nelle impostazioni.")

    private companion object {
        const val FORMAT = "mp3_44100_128"
    }
}

/** Segmenti da generare in anticipo, come minimo. */
internal const val PREFETCH_SEGMENTS = 2

/** Caratteri da generare in anticipo, come minimo (circa un minuto e mezzo di parlato). */
internal const val PREFETCH_CHARS = 1500

/** Caratteri di contesto passati a ElevenLabs prima e dopo il segmento, per un'intonazione continua. */
internal const val CONTEXT_CHARS = 500

/**
 * Ultimo segmento da avere in coda mentre si ascolta [current]: almeno [PREFETCH_SEGMENTS] segmenti
 * e almeno [PREFETCH_CHARS] caratteri dopo quello corrente, senza superare la fine del documento.
 */
internal fun prefetchTarget(segments: List<Segment>, current: Int): Int {
    val last = segments.lastIndex
    var target = current
    var chars = 0
    while (target < last && (target - current < PREFETCH_SEGMENTS || chars < PREFETCH_CHARS)) {
        target++
        chars += segments[target].text.length
    }
    return target
}

/** Testo dei segmenti che precedono [index], fino a [CONTEXT_CHARS] caratteri; null se non ce n'è. */
internal fun contextBefore(segments: List<Segment>, index: Int): String? {
    val parts = ArrayDeque<String>()
    var length = 0
    var i = index - 1
    while (i >= 0 && length < CONTEXT_CHARS) {
        parts.addFirst(segments[i].text)
        length += segments[i].text.length + 1
        i--
    }
    return parts.joinToString("\n").takeLast(CONTEXT_CHARS).ifEmpty { null }
}

/** Testo dei segmenti che seguono [index], fino a [CONTEXT_CHARS] caratteri; null se non ce n'è. */
internal fun contextAfter(segments: List<Segment>, index: Int): String? {
    val parts = mutableListOf<String>()
    var length = 0
    var i = index + 1
    while (i < segments.size && length < CONTEXT_CHARS) {
        parts += segments[i].text
        length += segments[i].text.length + 1
        i++
    }
    return parts.joinToString("\n").take(CONTEXT_CHARS).ifEmpty { null }
}
