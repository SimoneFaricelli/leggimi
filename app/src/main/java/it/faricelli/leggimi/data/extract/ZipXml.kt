package it.faricelli.leggimi.data.extract

import it.faricelli.leggimi.data.text.UserFacingException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.io.File
import java.util.zip.ZipException
import java.util.zip.ZipFile

internal fun <T> openZip(file: File, what: String, block: (ZipFile) -> T): T {
    val zip = try {
        ZipFile(file)
    } catch (e: ZipException) {
        throw UserFacingException("Il file non è un $what valido o è danneggiato.", e)
    }
    return zip.use(block)
}

internal fun ZipFile.readXml(path: String): Document? {
    val entry = getEntry(path) ?: return null
    return getInputStream(entry).use { Jsoup.parse(it, "UTF-8", "", Parser.xmlParser()) }
}

internal fun ZipFile.readHtml(path: String): Document? {
    val entry = getEntry(path) ?: return null
    return getInputStream(entry).use { Jsoup.parse(it, null, "") }
}
