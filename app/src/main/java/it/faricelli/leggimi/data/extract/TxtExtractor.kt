package it.faricelli.leggimi.data.extract

import it.faricelli.leggimi.data.text.Block
import it.faricelli.leggimi.data.text.ParsedDocument
import it.faricelli.leggimi.data.text.paragraphsFromPlainText
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

object TxtExtractor : DocumentExtractor {
    override fun extract(file: File, displayName: String): ParsedDocument {
        val text = decodeText(file.readBytes())
        return ParsedDocument(titleFromFileName(displayName), paragraphsFromPlainText(text).map { Block(it) })
    }

    /** Decodifica rispettando il BOM; senza BOM prova UTF-8 rigoroso e ripiega su Windows-1252. */
    fun decodeText(bytes: ByteArray): String {
        fun startsWith(vararg b: Int) = bytes.size >= b.size && b.indices.all { bytes[it] == b[it].toByte() }
        return when {
            startsWith(0xEF, 0xBB, 0xBF) -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            startsWith(0xFF, 0xFE) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            startsWith(0xFE, 0xFF) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            else -> try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: CharacterCodingException) {
                String(bytes, Charset.forName("windows-1252"))
            }
        }
    }
}
