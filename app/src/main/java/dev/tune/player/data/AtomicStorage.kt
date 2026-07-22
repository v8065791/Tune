package dev.tune.player.data

import android.util.AtomicFile
import java.io.File

/** Small UTF-8 wrapper around [AtomicFile] used by every JSON-backed store. */
internal class AtomicTextFile(path: File) {
    private val file = AtomicFile(path)

    val exists: Boolean get() = file.baseFile.exists()

    fun readText(): String = file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }

    fun writeText(text: String) {
        val output = file.startWrite()
        try {
            output.writer(Charsets.UTF_8).apply {
                write(text)
                flush()
            }
            file.finishWrite(output)
        } catch (failure: Throwable) {
            file.failWrite(output)
            throw failure
        }
    }

    fun delete() = file.delete()
}
