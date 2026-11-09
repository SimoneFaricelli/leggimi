package it.faricelli.leggimi.data.extract

import it.faricelli.leggimi.data.text.Block
import it.faricelli.leggimi.data.text.ParsedDocument
import it.faricelli.leggimi.data.text.normalizeSpaces
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset

object RtfExtractor : DocumentExtractor {
    override fun extract(file: File, displayName: String): ParsedDocument {
        val text = rtfToText(file.readBytes())
        val paragraphs = text.split('\n').map { normalizeSpaces(it) }.filter { it.isNotEmpty() }
        return ParsedDocument(titleFromFileName(displayName), paragraphs.map { Block(it) })
    }

    private val SKIP_DESTINATIONS = setOf(
        "fonttbl", "colortbl", "stylesheet", "info", "pict", "object", "header", "headerl", "headerr",
        "headerf", "footer", "footerl", "footerr", "footerf", "footnote", "fldinst", "themedata",
        "colorschememapping", "latentstyles", "datastore", "xmlnstbl", "listtable", "listoverridetable",
        "rsidtbl", "generator", "mmathPr", "pgdsctbl", "revtbl", "filetbl", "listtext", "pntext",
        "pntxta", "pntxtb", "bkmkstart", "bkmkend", "xe", "tc", "txe", "atnid", "atnauthor", "annotation",
        "operator", "author", "title", "subject", "keywords", "comment", "doccomm", "nonshppict", "shppict",
        "blipuid", "userprops", "wgrffmtfilter", "protusertbl", "falt", "panose", "fname",
    )

    private class GroupState(var skip: Boolean, var uc: Int)

    /** Converte RTF in testo; ogni riga del risultato è un paragrafo. */
    fun rtfToText(data: ByteArray): String {
        val src = String(data, Charsets.ISO_8859_1)
        val out = StringBuilder()
        val pendingBytes = ByteArrayOutputStream()
        var charset: Charset = Charset.forName("windows-1252")
        val stack = ArrayDeque<GroupState>()
        var state = GroupState(skip = false, uc = 1)
        var skipFallback = 0 // caratteri ANSI da saltare dopo un \uN
        var i = 0

        fun flushBytes() {
            if (pendingBytes.size() > 0) {
                out.append(String(pendingBytes.toByteArray(), charset))
                pendingBytes.reset()
            }
        }

        fun emit(s: String) {
            if (state.skip) return
            flushBytes()
            out.append(s)
        }

        while (i < src.length) {
            val c = src[i]
            when (c) {
                '{' -> {
                    stack.addLast(state)
                    state = GroupState(state.skip, state.uc)
                    i++
                }
                '}' -> {
                    flushBytes()
                    state = stack.removeLastOrNull() ?: state
                    i++
                }
                '\\' -> {
                    i++
                    if (i >= src.length) break
                    val n = src[i]
                    when {
                        n.isLetter() -> {
                            val start = i
                            while (i < src.length && src[i].isLetter()) i++
                            val word = src.substring(start, i)
                            var param: Int? = null
                            if (i < src.length && (src[i] == '-' || src[i].isDigit())) {
                                val ps = i
                                i++
                                while (i < src.length && src[i].isDigit()) i++
                                param = src.substring(ps, i).toIntOrNull()
                            }
                            if (i < src.length && src[i] == ' ') i++
                            if (skipFallback > 0 && word != "u") { skipFallback--; continue }
                            when (word) {
                                "par", "line", "row" -> emit("\n")
                                "sect", "page" -> emit("\n")
                                "tab", "cell" -> emit(" ")
                                "emdash" -> emit("—")
                                "endash" -> emit("–")
                                "lquote" -> emit("‘")
                                "rquote" -> emit("’")
                                "ldblquote" -> emit("“")
                                "rdblquote" -> emit("”")
                                "bullet" -> emit("•")
                                "uc" -> state.uc = param ?: 1
                                "ansicpg" -> param?.let { cp ->
                                    charset = runCatching { Charset.forName("windows-$cp") }
                                        .recoverCatching { Charset.forName("cp$cp") }
                                        .getOrDefault(charset)
                                }
                                "u" -> {
                                    val code = param ?: 0
                                    emit((if (code < 0) code + 65536 else code).toChar().toString())
                                    skipFallback = state.uc
                                }
                                in SKIP_DESTINATIONS -> state.skip = true
                            }
                        }
                        n == '\'' -> {
                            val hex = src.substring(i + 1, minOf(i + 3, src.length))
                            i += 3
                            if (skipFallback > 0) { skipFallback--; continue }
                            hex.toIntOrNull(16)?.let { if (!state.skip) pendingBytes.write(it) }
                        }
                        n == '*' -> { state.skip = true; i++ }
                        n == '~' -> { emit(" "); i++ }
                        n == '_' -> { emit("-"); i++ }
                        n == '-' -> i++
                        n == '\n' || n == '\r' -> { emit("\n"); i++ }
                        else -> { // \\ \{ \} e altri simboli letterali
                            if (skipFallback > 0) skipFallback-- else emit(n.toString())
                            i++
                        }
                    }
                }
                '\r', '\n' -> i++
                else -> {
                    if (skipFallback > 0) skipFallback-- else emit(c.toString())
                    i++
                }
            }
        }
        flushBytes()
        return out.toString()
    }
}
