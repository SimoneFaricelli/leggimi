package it.faricelli.leggimi.data

import it.faricelli.leggimi.data.extract.DocumentFormat
import it.faricelli.leggimi.data.extract.DocxExtractor
import it.faricelli.leggimi.data.extract.EpubExtractor
import it.faricelli.leggimi.data.extract.HtmlExtractor
import it.faricelli.leggimi.data.extract.MarkdownExtractor
import it.faricelli.leggimi.data.extract.OdtExtractor
import it.faricelli.leggimi.data.extract.RtfExtractor
import it.faricelli.leggimi.data.extract.TxtExtractor
import it.faricelli.leggimi.data.text.Block
import it.faricelli.leggimi.data.text.UserFacingException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.Charset

private const val BS = "\\"

class ExtractorsTest {
    @Test
    fun `riconoscimento del formato`() {
        assertEquals(DocumentFormat.PDF, DocumentFormat.detect("libro.PDF", null))
        assertEquals(DocumentFormat.DOCX, DocumentFormat.detect("x", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        assertEquals(DocumentFormat.MARKDOWN, DocumentFormat.detect("note.md", "application/octet-stream"))
        assertEquals(DocumentFormat.EPUB, DocumentFormat.detect(null, "application/epub+zip"))
        assertEquals(null, DocumentFormat.detect("foto.jpg", "image/jpeg"))
    }

    @Test
    fun `txt utf8 con righe vuote`() {
        val f = TestFiles.temp(".txt", "Prima riga\ncontinua qui.\n\nSecondo paragrafo: perché sì.\n")
        val doc = TxtExtractor.extract(f, "Il mio testo.txt")
        assertEquals("Il mio testo", doc.title)
        assertEquals(listOf(Block("Prima riga continua qui."), Block("Secondo paragrafo: perché sì.")), doc.blocks)
    }

    @Test
    fun `txt windows-1252 e utf16`() {
        val cp = TestFiles.temp(".txt", "Città e caffè".toByteArray(Charset.forName("windows-1252")))
        assertEquals("Città e caffè", TxtExtractor.extract(cp, "a.txt").blocks.single().text)
        val u16 = TestFiles.temp(".txt", byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Però".toByteArray(Charsets.UTF_16LE))
        assertEquals("Però", TxtExtractor.extract(u16, "a.txt").blocks.single().text)
    }

    @Test
    fun `markdown senza codice ne markup`() {
        val md = """
            # Titolo del libro

            Un paragrafo con **grassetto**, un [link](http://x.it) e `codice`.
            Continua sulla riga dopo.

            ## Capitolo 1

            - voce di elenco

            ```
            val x = 1
            ```
        """.trimIndent()
        val doc = MarkdownExtractor.extract(TestFiles.temp(".md", md), "x.md")
        assertEquals("Titolo del libro", doc.title)
        assertEquals(
            listOf(
                Block("Titolo del libro", 1),
                Block("Un paragrafo con grassetto, un link e codice. Continua sulla riga dopo."),
                Block("Capitolo 1", 2),
                Block("voce di elenco"),
            ),
            doc.blocks,
        )
    }

    @Test
    fun `html con titoli e parti da ignorare`() {
        val html = """
            <html><head><title>Pagina</title><style>p{}</style></head><body>
            <nav>Menu</nav>
            <h1>Inizio</h1>
            <p>Testo <b>importante</b><br>a capo.</p>
            <div>Blocco <span>div</span><p>annidato</p></div>
            <script>alert(1)</script>
            </body></html>
        """.trimIndent()
        val doc = HtmlExtractor.extract(TestFiles.temp(".html", html), "p.html")
        assertEquals("Pagina", doc.title)
        assertEquals(
            listOf(Block("Inizio", 1), Block("Testo importante a capo."), Block("Blocco div"), Block("annidato")),
            doc.blocks,
        )
    }

    @Test
    fun `rtf con accenti, unicode e tabelle da saltare`() {
        val rtf = """{\rtf1\ansi\ansicpg1252\deff0{\fonttbl{\f0 Times;}}{\colortbl;\red0\green0\blue0;}
{\*\generator Test;}\f0 Citt\'e0 di prova.\par
Seconda riga con ${BS}u8364? euro e \ldblquote virgolette\rdblquote .\par
{\pict\pngblip 89504e47}Fine\par}"""
        val doc = RtfExtractor.extract(TestFiles.temp(".rtf", rtf), "doc.rtf")
        assertEquals(
            listOf("Città di prova.", "Seconda riga con € euro e “virgolette”.", "Fine"),
            doc.blocks.map { it.text },
        )
    }

    @Test
    fun `docx con stili di titolo, tab e casella di testo`() {
        val documentXml = """<?xml version="1.0" encoding="UTF-8"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
<w:p><w:pPr><w:pStyle w:val="Titolo"/></w:pPr><w:r><w:t>Il romanzo</w:t></w:r></w:p>
<w:p><w:pPr><w:pStyle w:val="Titolo1"/></w:pPr><w:r><w:t>Capitolo primo</w:t></w:r></w:p>
<w:p><w:r><w:t xml:space="preserve">Era una </w:t></w:r><w:r><w:rPr><w:b/></w:rPr><w:t>notte</w:t></w:r><w:r><w:tab/><w:t>buia.</w:t></w:r><w:del><w:r><w:delText>cancellato</w:delText></w:r></w:del></w:p>
<w:p><w:r><w:t>Testo</w:t></w:r><w:r><w:pict><w:txbxContent><w:p><w:r><w:t>Nel riquadro</w:t></w:r></w:p></w:txbxContent></w:pict></w:r></w:p>
<w:p/>
</w:body></w:document>"""
        val stylesXml = """<?xml version="1.0" encoding="UTF-8"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:style w:type="paragraph" w:styleId="Titolo"><w:name w:val="Title"/></w:style>
<w:style w:type="paragraph" w:styleId="Titolo1"><w:name w:val="heading 1"/><w:pPr><w:outlineLvl w:val="0"/></w:pPr></w:style>
</w:styles>"""
        val f = TestFiles.zip(".docx", mapOf("word/document.xml" to documentXml, "word/styles.xml" to stylesXml))
        val doc = DocxExtractor.extract(f, "romanzo.docx")
        assertEquals("Il romanzo", doc.title)
        assertEquals(
            listOf(
                Block("Il romanzo", 1),
                Block("Capitolo primo", 1),
                Block("Era una notte buia."),
                Block("Testo"),
                Block("Nel riquadro"),
            ),
            doc.blocks,
        )
    }

    @Test(expected = UserFacingException::class)
    fun `docx danneggiato`() {
        DocxExtractor.extract(TestFiles.temp(".docx", "non sono uno zip"), "x.docx")
    }

    @Test
    fun `odt con titoli, spazi e note`() {
        val content = """<?xml version="1.0" encoding="UTF-8"?>
<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0">
<office:body><office:text>
<text:h text:outline-level="1">Parte prima</text:h>
<text:p>Uno<text:s text:c="3"/>due<text:tab/>tre<text:note><text:note-body><text:p>nota</text:p></text:note-body></text:note>.</text:p>
<text:list><text:list-item><text:p>Elemento</text:p></text:list-item></text:list>
</office:text></office:body></office:document-content>"""
        val meta = """<?xml version="1.0"?><office:document-meta xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:dc="http://purl.org/dc/elements/1.1/"><office:meta><dc:title>Saggio</dc:title></office:meta></office:document-meta>"""
        val doc = OdtExtractor.extract(TestFiles.zip(".odt", mapOf("content.xml" to content, "meta.xml" to meta)), "x.odt")
        assertEquals("Saggio", doc.title)
        assertEquals(listOf(Block("Parte prima", 1), Block("Uno due tre."), Block("Elemento")), doc.blocks)
    }

    @Test
    fun `epub segue la spine e risolve i percorsi`() {
        val container = """<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Il libro</dc:title></metadata>
<manifest>
<item id="c2" href="text/cap%202.xhtml" media-type="application/xhtml+xml"/>
<item id="c1" href="text/cap1.xhtml" media-type="application/xhtml+xml"/>
<item id="css" href="style.css" media-type="text/css"/>
</manifest>
<spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>"""
        val cap1 = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body><h1>Uno</h1><p>Primo testo<a epub:type="noteref" href="#n1">1</a>.</p><aside epub:type="footnote" id="n1"><p>Nota.</p></aside></body></html>"""
        val cap2 = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><body><h1>Due</h1><p>Secondo testo.</p></body></html>"""
        val f = TestFiles.zip(
            ".epub",
            mapOf(
                "mimetype" to "application/epub+zip",
                "META-INF/container.xml" to container,
                "OEBPS/content.opf" to opf,
                "OEBPS/text/cap1.xhtml" to cap1,
                "OEBPS/text/cap 2.xhtml" to cap2,
            ),
        )
        val doc = EpubExtractor.extract(f, "x.epub")
        assertEquals("Il libro", doc.title)
        assertEquals(listOf(Block("Uno", 1), Block("Primo testo."), Block("Due", 1), Block("Secondo testo.")), doc.blocks)
    }
}
