package io.github.dpan538.annota

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

/** Bounded open documents avoid reparsing a long PDF for every thumbnail and 768px tile. */
class PdfRasterPool {
    private data class Entry(val size: Long, val modified: Long, val renderer: PdfRenderer)

    private val entries = LinkedHashMap<String, Entry>(4, .75f, true)
    var opens = 0
        private set

    @Synchronized
    fun <T> page(file: File, index: Int, action: (PdfRenderer.Page) -> T): T {
        val key = file.absolutePath
        var entry = entries[key]
        if (
            entry != null && (entry.size != file.length() || entry.modified != file.lastModified())
        ) {
            entries.remove(key)?.renderer?.close()
            entry = null
        }
        if (entry == null) {
            while (entries.size >= 2) {
                val oldest = entries.keys.first()
                entries.remove(oldest)?.renderer?.close()
            }
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer =
                try {
                    PdfRenderer(fd)
                } catch (t: Throwable) {
                    fd.close()
                    throw t
                }
            entry = Entry(file.length(), file.lastModified(), renderer)
            entries[key] = entry
            opens++
        }
        return entry.renderer.openPage(index).use(action)
    }

    @Synchronized
    fun trim() {
        entries.values.forEach { it.renderer.close() }
        entries.clear()
    }
}
