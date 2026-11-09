package it.faricelli.leggimi.data.text

/**
 * Un blocco di sintesi vocale: una singola richiesta a ElevenLabs e un singolo file audio.
 * Copre il testo da ([startBlock], [startOffset]) incluso a ([endBlock], [endOffset]) escluso.
 */
data class Segment(
    val index: Int,
    val chapter: Int,
    val startBlock: Int,
    val startOffset: Int,
    val endBlock: Int,
    val endOffset: Int,
    val text: String,
) {
    /** Intervallo di caratteri di [blockIndex] coperto da questo segmento, o null. */
    fun rangeIn(blockIndex: Int, blockLength: Int): IntRange? {
        if (blockIndex !in startBlock..endBlock) return null
        val from = if (blockIndex == startBlock) startOffset else 0
        val to = if (blockIndex == endBlock) endOffset else blockLength
        return if (to > from) from until to else null
    }
}

data class ChapterRef(val index: Int, val title: String, val block: Int)

/** Documento pronto per la lettura: blocchi, segmenti e indici di navigazione. */
class SegmentedDocument(val blocks: List<Block>, val segments: List<Segment>) {
    /** Per ogni blocco, il primo segmento che lo contiene (o il successivo, se il blocco non si legge). */
    val blockToSegment: IntArray = IntArray(blocks.size) { -1 }.also { map ->
        for (s in segments) for (b in s.startBlock..s.endBlock) if (map[b] == -1) map[b] = s.index
        var next = segments.size - 1
        for (b in map.indices.reversed()) if (map[b] == -1) map[b] = next.coerceAtLeast(0) else next = map[b]
    }

    val chapters: List<ChapterRef> = buildList {
        var chapter = 0
        blocks.forEachIndexed { i, b ->
            if (b.isChapterStart) {
                if (i > 0) chapter++
                add(ChapterRef(chapter, b.text, i))
            }
        }
    }

    fun segmentForBlock(block: Int): Int = blockToSegment.getOrElse(block) { 0 }

    /** Il segmento che contiene il carattere [offset] del blocco [block] (o il primo che lo segue). */
    fun segmentAt(block: Int, offset: Int): Int {
        var result = segmentForBlock(block)
        for (i in result until segments.size) {
            val s = segments[i]
            if (s.startBlock != block || s.startOffset > offset) break
            result = i
        }
        return result
    }
}

object Segmenter {
    const val MAX_CHARS = 1200

    private data class Piece(val block: Int, val start: Int, val end: Int)

    private val SENTENCE_END = Regex("[.!?…;:]+[\"'»”’)\\]]*\\s+")

    /**
     * Divide i blocchi in segmenti di al più [maxChars] caratteri. Un segmento non contiene mai più
     * di un blocco, così toccare un paragrafo fa partire la lettura esattamente da lì.
     * [packParagraphs] = true riproduce la divisione delle versioni 1.0.0–1.0.1, che univa più
     * paragrafi nello stesso segmento: serve solo a convertire le posizioni salvate allora.
     */
    fun segment(blocks: List<Block>, maxChars: Int = MAX_CHARS, packParagraphs: Boolean = false): SegmentedDocument {
        val segments = mutableListOf<Segment>()
        var chapter = 0
        val current = mutableListOf<Piece>()
        var currentLen = 0

        fun flush() {
            if (current.isEmpty()) return
            val text = buildString {
                current.forEachIndexed { i, p ->
                    if (i > 0) append(if (p.block != current[i - 1].block) "\n\n" else " ")
                    append(blocks[p.block].text, p.start, p.end)
                }
            }
            val first = current.first()
            val last = current.last()
            segments += Segment(segments.size, chapter, first.block, first.start, last.block, last.end, text.trim())
            current.clear()
            currentLen = 0
        }

        blocks.forEachIndexed { bi, block ->
            if (block.isChapterStart && bi > 0) {
                flush()
                chapter++
            }
            if (!hasReadableText(block.text)) return@forEachIndexed
            if (!packParagraphs) flush()
            for (piece in split(bi, block.text, maxChars)) {
                val len = piece.end - piece.start
                if (current.isNotEmpty() && currentLen + len + 2 > maxChars) flush()
                current += piece
                currentLen += len + 2
            }
        }
        flush()
        return SegmentedDocument(blocks, segments)
    }

    /** Divide un blocco troppo lungo in pezzi, preferendo i confini di frase, poi gli spazi. */
    private fun split(block: Int, text: String, maxChars: Int): List<Piece> {
        if (text.length <= maxChars) return listOf(Piece(block, 0, text.length))
        val sentenceEnds = SENTENCE_END.findAll(text).map { it.range.last + 1 }.toMutableList()
        if (sentenceEnds.lastOrNull() != text.length) sentenceEnds += text.length

        val pieces = mutableListOf<Piece>()
        var start = 0
        var lastGood = -1
        for (end in sentenceEnds) {
            if (end - start <= maxChars) {
                lastGood = end
                continue
            }
            if (lastGood > start) {
                pieces += trimmed(block, text, start, lastGood)
                start = lastGood
            }
            // La frase da sola è ancora troppo lunga: taglio sugli spazi.
            while (end - start > maxChars) {
                var cut = text.lastIndexOf(' ', start + maxChars)
                if (cut <= start) cut = start + maxChars
                pieces += trimmed(block, text, start, cut)
                start = cut
            }
            lastGood = end
        }
        if (start < text.length) pieces += trimmed(block, text, start, text.length)
        return pieces.filter { it.end > it.start }
    }

    private fun trimmed(block: Int, text: String, start: Int, end: Int): Piece {
        var s = start
        var e = end
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        return Piece(block, s, e)
    }
}
