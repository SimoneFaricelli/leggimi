package it.faricelli.leggimi.data

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object TestFiles {
    fun temp(suffix: String, bytes: ByteArray): File =
        File.createTempFile("leggimi", suffix).apply { deleteOnExit(); writeBytes(bytes) }

    fun temp(suffix: String, text: String): File = temp(suffix, text.toByteArray())

    fun zip(suffix: String, entries: Map<String, String>): File {
        val f = File.createTempFile("leggimi", suffix).apply { deleteOnExit() }
        ZipOutputStream(f.outputStream()).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return f
    }
}
