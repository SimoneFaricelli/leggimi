package it.faricelli.leggimi.data.text

private val WHITESPACE = Regex("[\\s\\u00A0\\u2007\\u202F\\u200B]+")
private val PAGE_NUMBER = Regex("^\\s*(-\\s*)?\\d{1,4}(\\s*-)?\\s*$")
private val HYPHENATED_LINE_END = Regex("(\\p{L})[-\\u00AD]\\n(\\p{Ll})")

/** Comprime gli spazi e rimuove quelli ai bordi. */
fun normalizeSpaces(s: String): String = WHITESPACE.replace(s, " ").trim()

/** True se la riga contiene solo un numero di pagina, come "12" o "- 12 -". */
fun isPageNumberLine(s: String): Boolean = PAGE_NUMBER.matches(s)

private val HYPHEN_AT_END = Regex("(\\p{L})[-\\u00AD]$")

/**
 * Aggiunge una riga in coda a un paragrafo: con uno spazio, oppure ricucendo la parola
 * se la riga precedente finisce con un trattino di sillabazione e questa inizia in minuscolo.
 */
fun joinLine(paragraph: StringBuilder, line: String) {
    val next = line.trim()
    if (next.isEmpty()) return
    if (paragraph.isEmpty()) {
        paragraph.append(next)
        return
    }
    val end = paragraph.trimEnd()
    if (HYPHEN_AT_END.containsMatchIn(end) && next.first().isLowerCase()) {
        paragraph.setLength(end.length - 1)
        paragraph.append(next)
    } else {
        if (end.endsWith('\u00AD')) paragraph.setLength(end.length - 1) else paragraph.setLength(end.length)
        paragraph.append(' ').append(next)
    }
}

/** True se il testo contiene almeno una lettera o una cifra (cioè c'è qualcosa da leggere). */
fun hasReadableText(s: String): Boolean = s.any { it.isLetterOrDigit() }

/**
 * Divide testo semplice in paragrafi.
 * - Se ci sono righe vuote, sono loro a separare i paragrafi e le righe interne vengono unite.
 * - Altrimenti ogni riga è un paragrafo.
 * Le parole spezzate con il trattino a fine riga vengono ricucite.
 */
fun paragraphsFromPlainText(raw: String, dropPageNumbers: Boolean = false): List<String> {
    val text = raw.replace("\r\n", "\n").replace('\r', '\n')
        .let { HYPHENATED_LINE_END.replace(it, "$1$2") }
    val lines = text.split('\n').let { ls -> if (dropPageNumbers) ls.filterNot(::isPageNumberLine) else ls }
    val hasBlankLines = lines.any { it.isBlank() } && lines.count { it.isNotBlank() } > 1
    val result = mutableListOf<String>()
    if (hasBlankLines) {
        val current = StringBuilder()
        for (line in lines) {
            if (line.isBlank()) {
                if (current.isNotEmpty()) result += normalizeSpaces(current.toString())
                current.clear()
            } else {
                if (current.isNotEmpty()) current.append(' ')
                current.append(line)
            }
        }
        if (current.isNotEmpty()) result += normalizeSpaces(current.toString())
    } else {
        lines.mapTo(result) { normalizeSpaces(it) }
    }
    return result.filter { it.isNotEmpty() }
}

/** Accumula blocchi scartando quelli vuoti. */
class BlockListBuilder {
    private val blocks = mutableListOf<Block>()

    fun paragraph(text: String) {
        val t = normalizeSpaces(text)
        if (t.isNotEmpty()) blocks += Block(t)
    }

    fun heading(level: Int, text: String) {
        val t = normalizeSpaces(text)
        if (t.isNotEmpty()) blocks += Block(t, level.coerceIn(1, 6))
    }

    fun addAll(other: List<Block>) {
        blocks += other
    }

    fun build(): List<Block> = blocks.toList()
}
