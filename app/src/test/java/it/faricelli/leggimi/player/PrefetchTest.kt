package it.faricelli.leggimi.player

import it.faricelli.leggimi.data.text.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrefetchTest {
    private fun segments(vararg lengths: Int) =
        lengths.mapIndexed { i, len -> Segment(i, 0, i, 0, i, len, "x".repeat(len)) }

    @Test
    fun `segmenti lunghi - bastano i due successivi`() {
        assertEquals(2, prefetchTarget(segments(1200, 1200, 1200, 1200), 0))
    }

    @Test
    fun `segmenti brevi - si va avanti fino a 1500 caratteri`() {
        val s = segments(*IntArray(40) { 100 })
        assertEquals(15, prefetchTarget(s, 0)) // 15 segmenti da 100 dopo il corrente
        assertEquals(39, prefetchTarget(s, 30)) // non oltre la fine
    }

    @Test
    fun `contesto prima e dopo fino a 500 caratteri`() {
        val s = segments(300, 300, 50, 300, 300)
        assertEquals(500, contextBefore(s, 2)!!.length)
        assertEquals(500, contextAfter(s, 2)!!.length)
        assertEquals(300, contextBefore(s, 1)!!.length)
        assertNull(contextBefore(s, 0))
        assertNull(contextAfter(s, 4))
    }
}
