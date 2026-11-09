package it.faricelli.leggimi.data.extract

import it.faricelli.leggimi.data.text.ParsedDocument
import java.io.File

interface DocumentExtractor {
    /** Estrae il testo da [file]; [displayName] è il nome originale, usato come titolo di riserva. */
    fun extract(file: File, displayName: String): ParsedDocument

    /**
     * Versione dell'estrattore. Va aumentata quando cambia il testo prodotto:
     * i documenti estratti con una versione precedente vengono rielaborati all'apertura.
     */
    val version: Int get() = 1
}

enum class DocumentFormat(val label: String, val extensions: List<String>, val mimeTypes: List<String>) {
    PDF("PDF", listOf("pdf"), listOf("application/pdf")),
    DOCX("Word", listOf("docx", "docm"), listOf(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-word.document.macroEnabled.12",
    )),
    ODT("ODT", listOf("odt"), listOf("application/vnd.oasis.opendocument.text")),
    EPUB("EPUB", listOf("epub"), listOf("application/epub+zip")),
    RTF("RTF", listOf("rtf"), listOf("application/rtf", "text/rtf")),
    HTML("HTML", listOf("html", "htm", "xhtml"), listOf("text/html", "application/xhtml+xml")),
    MARKDOWN("Markdown", listOf("md", "markdown"), listOf("text/markdown", "text/x-markdown")),
    TXT("Testo", listOf("txt", "text"), listOf("text/plain"));

    val extension: String get() = extensions.first()

    fun extractor(): DocumentExtractor = when (this) {
        PDF -> PdfExtractor
        DOCX -> DocxExtractor
        ODT -> OdtExtractor
        EPUB -> EpubExtractor
        RTF -> RtfExtractor
        HTML -> HtmlExtractor
        MARKDOWN -> MarkdownExtractor
        TXT -> TxtExtractor
    }

    companion object {
        /** Tipi MIME da proporre nel selettore file di sistema. */
        val pickerMimeTypes: Array<String> = (entries.flatMap { it.mimeTypes } + listOf(
            "application/octet-stream", "application/zip", "text/*",
        )).distinct().toTypedArray()

        /** Riconosce il formato: prima dall'estensione del nome, poi dal tipo MIME. */
        fun detect(fileName: String?, mimeType: String?): DocumentFormat? {
            val ext = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
            entries.firstOrNull { ext in it.extensions }?.let { return it }
            val mime = mimeType?.lowercase()?.substringBefore(';')?.trim() ?: return null
            return entries.firstOrNull { mime in it.mimeTypes }
        }
    }
}

internal fun titleFromFileName(name: String): String =
    name.substringAfterLast('/').substringBeforeLast('.').replace('_', ' ').trim().ifEmpty { "Documento" }
