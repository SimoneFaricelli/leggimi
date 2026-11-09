package it.faricelli.leggimi.data.text

import kotlinx.serialization.Serializable

/**
 * Un blocco di testo del documento: un paragrafo ([heading] = 0) o un titolo (livello 1..6).
 * I titoli di livello 1 e 2 aprono un nuovo capitolo.
 */
@Serializable
data class Block(val text: String, val heading: Int = 0) {
    val isChapterStart: Boolean get() = heading in 1..CHAPTER_LEVEL

    companion object {
        const val CHAPTER_LEVEL = 2
    }
}

/** [extractorVersion]: versione dell'estrattore che l'ha prodotto (vedi DocumentExtractor.version). */
@Serializable
data class ParsedDocument(val title: String, val blocks: List<Block>, val extractorVersion: Int = 1)

/** Messaggio di errore pensato per essere mostrato così com'è all'utente. */
open class UserFacingException(message: String, cause: Throwable? = null) : Exception(message, cause)
