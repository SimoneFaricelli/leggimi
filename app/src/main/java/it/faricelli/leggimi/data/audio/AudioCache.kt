package it.faricelli.leggimi.data.audio

import java.io.File
import java.security.MessageDigest

/** Audio generato salvato su disco: filesDir/audio/<docId>/<hash>.mp3 */
class AudioCache(private val root: File) {
    fun fileFor(docId: String, key: String): File = File(File(root, docId), "$key.mp3")

    fun sizeBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun clear() {
        root.listFiles()?.forEach { it.deleteRecursively() }
    }

    fun clearDocument(docId: String) {
        File(root, docId).deleteRecursively()
    }

    companion object {
        fun key(vararg parts: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            parts.forEach { md.update(it.toByteArray()); md.update(0) }
            return md.digest().joinToString("") { "%02x".format(it) }.take(40)
        }
    }
}
