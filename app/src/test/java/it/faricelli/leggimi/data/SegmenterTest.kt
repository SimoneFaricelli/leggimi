package it.faricelli.leggimi.data

import it.faricelli.leggimi.data.text.Block
import it.faricelli.leggimi.data.text.Segmenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmenterTest {
    @Test
    fun `ogni paragrafo ha i suoi segmenti, e toccarlo parte dal suo inizio`() {
        val blocks = listOf(
            Block("Titolo", heading = 1),
            Block("Breve."),
            Block("Frase lunga di prova. ".repeat(20).trim()),
            Block("Altro paragrafo breve."),
        )
        val doc = Segmenter.segment(blocks, maxChars = 200)
        doc.segments.forEach { assertEquals("segmento a cavallo di blocchi: $it", it.startBlock, it.endBlock) }
        blocks.indices.forEach { b ->
            val seg = doc.segments[doc.segmentForBlock(b)]
            assertEquals(b, seg.startBlock)
            assertEquals(0, seg.startOffset)
        }
        assertEquals(listOf("Titolo", "Breve."), doc.segments.take(2).map { it.text })
        assertTrue(doc.segments.size > blocks.size)
    }

    @Test
    fun `posizione salvata con la vecchia divisione a pacchetti`() {
        val blocks = List(12) { i -> Block(if (i % 5 == 4) "Frase lunga di prova. ".repeat(15).trim() else "Paragrafo $i.") }
        val packed = Segmenter.segment(blocks, maxChars = 200, packParagraphs = true)
        val current = Segmenter.segment(blocks, maxChars = 200)
        assertTrue(packed.segments.size < current.segments.size)
        for (old in packed.segments) {
            val new = current.segments[current.segmentAt(old.startBlock, old.startOffset)]
            assertEquals(old.startBlock, new.startBlock)
            assertEquals(old.startOffset, new.startOffset)
        }
    }

    @Test
    fun `con packParagraphs i paragrafi brevi vengono uniti fino al limite`() {
        val blocks = List(10) { Block("Paragrafo numero $it con un po' di testo.") }
        val doc = Segmenter.segment(blocks, maxChars = 200, packParagraphs = true)
        assertTrue(doc.segments.size in 2..4)
        assertTrue(doc.segments.all { it.text.length <= 200 })
        // Copertura completa e in ordine.
        assertEquals(0, doc.segments.first().startBlock)
        assertEquals(9, doc.segments.last().endBlock)
        doc.segments.zipWithNext().forEach { (a, b) -> assertEquals(a.endBlock + 1, b.startBlock) }
    }

    @Test
    fun `un paragrafo lungo si divide sulle frasi`() {
        val sentence = "Questa è una frase di prova abbastanza lunga da contare. "
        val long = sentence.repeat(60).trim()
        val doc = Segmenter.segment(listOf(Block(long)), maxChars = 500)
        assertTrue(doc.segments.size > 1)
        doc.segments.forEach {
            assertTrue("troppo lungo: ${it.text.length}", it.text.length <= 500)
            assertTrue("non finisce con una frase: ${it.text.takeLast(20)}", it.text.endsWith("."))
        }
        // Gli offset ricostruiscono il testo originale.
        val rebuilt = doc.segments.joinToString(" ") { long.substring(it.startOffset, it.endOffset) }
        assertEquals(long, rebuilt)
    }

    @Test
    fun `una frase senza punteggiatura si divide sugli spazi`() {
        val long = List(400) { "parola" }.joinToString(" ")
        val doc = Segmenter.segment(listOf(Block(long)), maxChars = 300)
        assertTrue(doc.segments.all { it.text.length <= 300 })
        assertEquals(long.split(' ').size, doc.segments.sumOf { it.text.split(' ').size })
    }

    @Test
    fun `i capitoli non vengono attraversati`() {
        val blocks = listOf(
            Block("Capitolo uno", heading = 1), Block("Testo uno."),
            Block("Capitolo due", heading = 1), Block("Testo due."),
            Block("Sezione", heading = 3), Block("Testo tre."),
        )
        val doc = Segmenter.segment(blocks)
        assertEquals(listOf(0, 0, 1, 1, 1, 1), doc.segments.map { it.chapter })
        assertEquals(listOf("Capitolo uno", "Capitolo due"), doc.chapters.map { it.title })
        assertEquals(3, doc.segmentForBlock(3))
        val packed = Segmenter.segment(blocks, packParagraphs = true)
        assertEquals("Capitolo due\n\nTesto due.\n\nSezione\n\nTesto tre.", packed.segments[1].text)
    }

    @Test
    fun `blocchi senza lettere non vengono letti ma restano navigabili`() {
        val blocks = listOf(Block("Inizio."), Block("* * *"), Block("Fine."))
        val doc = Segmenter.segment(blocks, maxChars = 10)
        assertEquals(listOf("Inizio.", "Fine."), doc.segments.map { it.text })
        assertEquals(1, doc.segmentForBlock(1))
    }

    @Test
    fun `evidenziazione del segmento nel blocco`() {
        val text = "Prima frase. Seconda frase. Terza frase."
        val doc = Segmenter.segment(listOf(Block(text)), maxChars = 20)
        val second = doc.segments[1]
        val range = second.rangeIn(0, text.length)!!
        assertEquals("Seconda frase.", text.substring(range.first, range.last + 1))
    }
}
