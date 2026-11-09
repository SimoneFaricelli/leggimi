package it.faricelli.leggimi.data.extract

import it.faricelli.leggimi.data.text.BlockListBuilder
import it.faricelli.leggimi.data.text.ParsedDocument
import it.faricelli.leggimi.data.text.UserFacingException
import java.io.File
import java.net.URLDecoder

object EpubExtractor : DocumentExtractor {
    override fun extract(file: File, displayName: String): ParsedDocument = openZip(file, "EPUB") { zip ->
        val container = zip.readXml("META-INF/container.xml")
            ?: throw UserFacingException("EPUB non valido: manca META-INF/container.xml.")
        val opfPath = container.getElementsByTag("rootfile").firstOrNull()?.attr("full-path")?.takeIf { it.isNotEmpty() }
            ?: throw UserFacingException("EPUB non valido: manca il file OPF.")
        val opf = zip.readXml(opfPath) ?: throw UserFacingException("EPUB non valido: OPF illeggibile.")
        val baseDir = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }

        val manifest = opf.getElementsByTag("item").associate { it.attr("id") to (it.attr("href") to it.attr("media-type")) }
        val title = (opf.getElementsByTag("dc:title").firstOrNull() ?: opf.getElementsByTag("title").firstOrNull())
            ?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: titleFromFileName(displayName)

        val out = BlockListBuilder()
        for (itemref in opf.getElementsByTag("itemref")) {
            if (itemref.attr("linear") == "no") continue
            val (href, type) = manifest[itemref.attr("idref")] ?: continue
            if (!type.contains("html")) continue
            val path = resolve(baseDir, URLDecoder.decode(href.substringBefore('#'), "UTF-8"))
            val doc = zip.readHtml(path) ?: continue
            out.addAll(HtmlBlocks.extract(doc.body()))
        }
        ParsedDocument(title, out.build())
    }

    private fun resolve(base: String, href: String): String {
        val parts = ArrayDeque<String>()
        for (p in (base + href).split('/')) {
            when (p) {
                "", "." -> Unit
                ".." -> parts.removeLastOrNull()
                else -> parts.addLast(p)
            }
        }
        return parts.joinToString("/")
    }
}
