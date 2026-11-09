package it.faricelli.leggimi.data.library

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import it.faricelli.leggimi.data.audio.AudioCache
import it.faricelli.leggimi.data.db.DocumentDao
import it.faricelli.leggimi.data.db.DocumentEntity
import it.faricelli.leggimi.data.extract.DocumentFormat
import it.faricelli.leggimi.data.text.ParsedDocument
import it.faricelli.leggimi.data.text.SegmentedDocument
import it.faricelli.leggimi.data.text.Segmenter
import it.faricelli.leggimi.data.text.UserFacingException
import it.faricelli.leggimi.data.text.hasReadableText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

class LoadedDocument(val entity: DocumentEntity, val parsed: ParsedDocument, val segmented: SegmentedDocument)

class LibraryRepository(
    private val context: Context,
    private val dao: DocumentDao,
    private val audioCache: AudioCache,
) {
    private val docsDir = File(context.filesDir, "docs")
    private val json = Json { ignoreUnknownKeys = true }
    private val parsedCache = LruCache<String, Pair<ParsedDocument, SegmentedDocument>>(3)

    fun observeAll(): Flow<List<DocumentEntity>> = dao.observeAll()
    fun observe(id: String): Flow<DocumentEntity?> = dao.observe(id)
    suspend fun get(id: String): DocumentEntity? = dao.get(id)

    /** Copia il file nello spazio dell'app, ne estrae il testo e lo aggiunge alla libreria. */
    suspend fun import(uri: Uri): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "documento"
        val format = DocumentFormat.detect(name, resolver.getType(uri))
            ?: throw UserFacingException("Formato non supportato: $name")

        val id = UUID.randomUUID().toString()
        val dir = File(docsDir, id).apply { mkdirs() }
        try {
            val source = File(dir, "source.${format.extension}")
            (resolver.openInputStream(uri) ?: throw UserFacingException("Impossibile aprire il file."))
                .use { input -> source.outputStream().use { input.copyTo(it) } }

            val extractor = format.extractor()
            val parsed = extractor.extract(source, name).copy(extractorVersion = extractor.version)
            if (parsed.blocks.none { hasReadableText(it.text) }) {
                throw UserFacingException(
                    if (format == DocumentFormat.PDF) "Il PDF non contiene testo selezionabile (forse è una scansione)."
                    else "Il documento non contiene testo da leggere."
                )
            }
            File(dir, "text.json").writeText(json.encodeToString(ParsedDocument.serializer(), parsed))
            val segmented = Segmenter.segment(parsed.blocks)
            val now = System.currentTimeMillis()
            dao.insert(
                DocumentEntity(
                    id = id, title = parsed.title, format = format.label, fileName = name,
                    addedAt = now, lastOpenedAt = now, segmentCount = segmented.segments.size,
                    charCount = segmented.segments.sumOf { it.text.length },
                )
            )
            parsedCache.put(id, parsed to segmented)
            id
        } catch (e: Throwable) {
            dir.deleteRecursively()
            when (e) {
                is UserFacingException -> throw e
                is OutOfMemoryError -> throw UserFacingException("Il documento è troppo grande.", e)
                else -> throw UserFacingException("Impossibile leggere il documento: ${e.message ?: e.javaClass.simpleName}", e)
            }
        }
    }

    suspend fun load(id: String): LoadedDocument? = withContext(Dispatchers.IO) {
        var entity = dao.get(id) ?: return@withContext null
        val (parsed, segmented) = parsedCache.get(id) ?: run {
            val file = File(File(docsDir, id), "text.json")
            if (!file.exists()) return@withContext null
            var p = json.decodeFromString(ParsedDocument.serializer(), file.readText())
            var s = Segmenter.segment(p.blocks)
            // Prima si converte la posizione alla divisione attuale, poi si rielabora il testo se serve:
            // reextractIfOutdated sposta la posizione in proporzione tra due divisioni dello stesso tipo.
            migrateFromPackedSegments(entity, s)?.let { entity = it }
            reextractIfOutdated(entity, p, s)?.let { (newEntity, newParsed, newSegmented) ->
                entity = newEntity
                p = newParsed
                s = newSegmented
            }
            (p to s).also { parsedCache.put(id, it) }
        }
        dao.touch(id, System.currentTimeMillis())
        LoadedDocument(entity, parsed, segmented)
    }

    /**
     * Fino alla versione 1.0.1 un segmento poteva contenere più paragrafi. Se la posizione salvata
     * si riferisce a quella divisione, la converte nel segmento che inizia allo stesso punto del
     * testo ed elimina l'audio del documento, generato per segmenti che non esistono più.
     */
    private suspend fun migrateFromPackedSegments(entity: DocumentEntity, segmented: SegmentedDocument): DocumentEntity? {
        val count = segmented.segments.size
        if (entity.segmentCount == count) return null
        val packed = Segmenter.segment(segmented.blocks, packParagraphs = true)
        if (packed.segments.size != entity.segmentCount) return null
        val position = packed.segments.getOrNull(entity.positionSegment)
            ?.let { segmented.segmentAt(it.startBlock, it.startOffset) } ?: 0
        val charCount = segmented.segments.sumOf { it.text.length }
        dao.updateContent(entity.id, count, charCount, position)
        audioCache.clearDocument(entity.id)
        return entity.copy(segmentCount = count, charCount = charCount, positionSegment = position, positionMs = 0)
    }

    /**
     * Se il testo salvato è stato estratto con una versione precedente dell'estrattore, lo rielabora
     * dal file originale. La posizione di ascolto viene spostata in proporzione e l'audio già
     * generato per il documento viene eliminato (i segmenti non coincidono più).
     * Restituisce null se non serve o se la rielaborazione fallisce (si tiene il testo vecchio).
     */
    private suspend fun reextractIfOutdated(
        entity: DocumentEntity,
        old: ParsedDocument,
        oldSegmented: SegmentedDocument,
    ): Triple<DocumentEntity, ParsedDocument, SegmentedDocument>? {
        val dir = File(docsDir, entity.id)
        val source = dir.listFiles()?.firstOrNull { it.name.startsWith("source.") } ?: return null
        val extractor = DocumentFormat.detect(source.name, null)?.extractor() ?: return null
        if (old.extractorVersion >= extractor.version) return null
        val parsed = try {
            extractor.extract(source, entity.fileName).copy(extractorVersion = extractor.version)
        } catch (_: Exception) {
            return null
        }
        if (parsed.blocks.none { hasReadableText(it.text) }) return null
        val segmented = Segmenter.segment(parsed.blocks)
        File(dir, "text.json").writeText(json.encodeToString(ParsedDocument.serializer(), parsed))
        val newCount = segmented.segments.size
        val oldCount = oldSegmented.segments.size.coerceAtLeast(1)
        val position = (entity.positionSegment.toLong() * newCount / oldCount).toInt().coerceIn(0, (newCount - 1).coerceAtLeast(0))
        val charCount = segmented.segments.sumOf { it.text.length }
        dao.updateContent(entity.id, newCount, charCount, position)
        audioCache.clearDocument(entity.id)
        return Triple(
            entity.copy(segmentCount = newCount, charCount = charCount, positionSegment = position, positionMs = 0),
            parsed,
            segmented,
        )
    }

    suspend fun savePosition(id: String, segment: Int, ms: Long) = dao.updatePosition(id, segment, ms)

    suspend fun setDocumentVoice(id: String, voiceId: String?, voiceName: String?) = dao.setVoice(id, voiceId, voiceName)

    suspend fun setDocumentModel(id: String, modelId: String?, modelName: String?) = dao.setModel(id, modelId, modelName)

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        dao.delete(id)
        parsedCache.remove(id)
        File(docsDir, id).deleteRecursively()
        audioCache.clearDocument(id)
    }
}
