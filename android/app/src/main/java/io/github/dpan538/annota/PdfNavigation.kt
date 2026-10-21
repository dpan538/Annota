package io.github.dpan538.annota

import android.graphics.PointF
import android.graphics.RectF
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.*
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import java.io.InterruptedIOException
import java.util.Collections
import java.util.IdentityHashMap

data class PdfTarget(val source: Int, val point: PointF? = null)

data class OutlineEntry(val title: String, val depth: Int, val target: PdfTarget?)

data class PageLink(val box: RectF, val target: PdfTarget)

data class PageContent(val words: List<Word>, val links: List<PageLink>)

data class SearchProgress(
    val hits: List<SearchHit>,
    val scanned: Int,
    val total: Int,
    val complete: Boolean,
    val limited: Boolean,
)

// No shared PDDocument: each worker owns and closes its parser.
private fun <T> PdfEngine.readBook(book: Book, body: (PDDocument) -> T): T =
    PDDocument.load(
            store.file(book.source),
            MemoryUsageSetting.setupMixed(24L * 1024 * 1024).setTempDir(context.cacheDir),
        )
        .use(body)

internal fun displayedPoint(page: PDPage, x: Float, y: Float): PointF {
    val crop = page.cropBox
    val a = x - crop.lowerLeftX
    val b = y - crop.lowerLeftY
    return when ((page.rotation % 360 + 360) % 360) {
        90 -> PointF(b, a)
        180 -> PointF(crop.width - a, b)
        270 -> PointF(crop.height - b, crop.width - a)
        else -> PointF(a, crop.height - b)
    }
}

private fun target(doc: PDDocument, destination: PDDestination?): PdfTarget? {
    val dest =
        when (destination) {
            is PDNamedDestination -> doc.documentCatalog.findNamedDestinationPage(destination)
            is PDPageDestination -> destination
            else -> null
        } ?: return null
    val index = dest.page?.let { doc.pages.indexOf(it) } ?: dest.retrievePageNumber()
    if (index !in 0 until doc.numberOfPages) return null
    val page = doc.getPage(index)
    val p =
        when (dest) {
            is PDPageXYZDestination ->
                if (dest.top >= 0)
                    displayedPoint(
                        page,
                        if (dest.left >= 0) dest.left.toFloat() else page.cropBox.lowerLeftX,
                        dest.top.toFloat(),
                    )
                else null
            is PDPageFitWidthDestination ->
                if (dest.top >= 0) displayedPoint(page, page.cropBox.lowerLeftX, dest.top.toFloat())
                else null
            else -> null
        }
    return PdfTarget(index, p)
}

fun PdfEngine.outline(book: Book, cancelled: () -> Boolean = { false }): List<OutlineEntry> {
    if (book.source.isEmpty()) return emptyList()
    return readBook(book) { doc ->
        val result = mutableListOf<OutlineEntry>()
        val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        fun walk(node: PDOutlineNode, depth: Int) {
            if (depth > 24 || result.size >= 2000) return
            var child = node.firstChild
            while (child != null && result.size < 2000) {
                if (cancelled()) throw InterruptedIOException("Cancelled")
                val item = child
                if (!seen.add(item.cosObject)) break
                val dest =
                    runCatching {
                            target(
                                doc,
                                item.destination ?: (item.action as? PDActionGoTo)?.destination,
                            )
                        }
                        .getOrNull()
                result += OutlineEntry(item.title.orEmpty().take(200), depth, dest)
                walk(item, depth + 1)
                child = item.nextSibling
            }
        }
        doc.documentCatalog.documentOutline?.let { walk(it, 0) }
        result
    }
}

fun PdfEngine.pageContent(book: Book, page: Sheet): PageContent {
    if (page.source < 0) return PageContent(emptyList(), emptyList())
    return readBook(book) { doc ->
        val original = doc.getPage(page.source)
        val links =
            original.annotations.filterIsInstance<PDAnnotationLink>().take(500).mapNotNull { link ->
                runCatching {
                        val t =
                            target(
                                doc,
                                link.destination ?: (link.action as? PDActionGoTo)?.destination,
                            ) ?: return@runCatching null
                        val r = link.rectangle ?: return@runCatching null
                        val a = displayedPoint(original, r.lowerLeftX, r.lowerLeftY)
                        val b = displayedPoint(original, r.upperRightX, r.upperRightY)
                        PageLink(
                            RectF(
                                minOf(a.x, b.x),
                                minOf(a.y, b.y),
                                maxOf(a.x, b.x),
                                maxOf(a.y, b.y),
                            ),
                            t,
                        )
                    }
                    .getOrNull()
            }
        PageContent(words(doc, page), links)
    }
}

fun PdfEngine.search(
    book: Book,
    query: String,
    cancelled: () -> Boolean = { false },
    progress: (SearchProgress) -> Unit,
) {
    val pages = book.pages.toList()
    val hits = mutableListOf<SearchHit>()
    if (book.source.isEmpty()) {
        progress(SearchProgress(emptyList(), pages.size, pages.size, true, false))
        return
    }
    readBook(book) { doc ->
        for ((i, page) in pages.withIndex()) {
            if (cancelled()) throw InterruptedIOException("Cancelled")
            if (page.source >= 0)
                hits += PageText(words(doc, page, cancelled)).find(query, page.id, 1000 - hits.size)
            if (cancelled()) throw InterruptedIOException("Cancelled")
            val limited = hits.size >= 1000
            progress(
                SearchProgress(
                    hits.toList(),
                    i + 1,
                    pages.size,
                    i == pages.lastIndex || limited,
                    limited,
                )
            )
            if (limited) break
        }
    }
}
