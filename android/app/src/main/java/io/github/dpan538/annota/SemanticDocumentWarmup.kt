package io.github.dpan538.annota

import android.content.Context
import android.os.Process
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Opportunistic warming of already extracted pages, never a second PDF text extraction pass. */
internal class SemanticDocumentWarmup(
    private val warm: (LookupContext, () -> Boolean) -> Boolean,
    private val foregroundPending: () -> Boolean,
    private val enabled: () -> Boolean = { true },
    private val prepare: (() -> Boolean) -> Boolean = { true },
) : AutoCloseable {
    constructor(
        context: Context
    ) : this(
        { window, active -> SemanticLookup.get(context.applicationContext).warm(window, active) },
        { SemanticLookup.get(context.applicationContext).foregroundPending },
        { Prefs(context).semanticDictionary },
        { active -> SemanticLookup.get(context.applicationContext).prepare(active) },
    )

    private class Page(val words: List<Word>) {
        var windows: List<LookupContext>? = null
        var next = 0
    }

    private val lock = Any()
    private val pages = linkedMapOf<String, Page>()
    private val completed = linkedSetOf<String>()
    private val worker =
        Executors.newSingleThreadScheduledExecutor { command ->
            Thread(
                {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                    command.run()
                },
                "annota-semantic-warmup",
            )
        }
    @Volatile private var closed = false
    @Volatile private var paused = false
    @Volatile private var idleAfter = 0L
    @Volatile private var generation = 0
    private var preferred: String? = null
    private var scheduled = false

    fun offer(pageId: String, words: List<Word>) =
        synchronized(lock) {
            if (closed || !enabled() || words.isEmpty() || pageId in completed) return@synchronized
            if (pageId in pages) {
                schedule(800)
                return@synchronized
            }
            // Hold only a few visible pages. Evicted work can be offered again when revisited.
            if (pages.size >= 3) pages.keys.firstOrNull { it != preferred }?.let(pages::remove)
            pages[pageId] = Page(words)
            schedule(800)
        }

    fun prefer(pageId: String) = synchronized(lock) { preferred = pageId }

    /** Pointer samples and scrolling postpone background inference, not foreground lookup. */
    fun defer(milliseconds: Long = 700) {
        idleAfter = SystemClock.uptimeMillis() + milliseconds
    }

    fun pause(value: Boolean) {
        paused = value
        if (!value) synchronized(lock) { schedule(800) }
    }

    fun clearPending() =
        synchronized(lock) {
            generation++
            pages.clear()
        }

    fun prepareModel() {
        if (closed || !enabled()) return
        worker.schedule({ if (active()) prepare { active() } }, 600, TimeUnit.MILLISECONDS)
    }

    private fun active() =
        !closed &&
            !paused &&
            enabled() &&
            SystemClock.uptimeMillis() >= idleAfter &&
            !foregroundPending()

    private fun schedule(delay: Long) {
        if (closed || paused || !enabled() || scheduled || pages.isEmpty()) return
        scheduled = true
        worker.schedule(Runnable { step() }, delay, TimeUnit.MILLISECONDS)
    }

    private fun step() {
        try {
            if (!active()) return
            val item =
                synchronized(lock) {
                    val id = preferred?.takeIf { it in pages } ?: pages.keys.firstOrNull()
                    id?.let { Triple(it, pages.getValue(it), generation) }
                } ?: return
            val (id, page, epoch) = item
            val eligible = { generation == epoch && active() }
            val contexts = page.windows ?: windows(page.words).also { page.windows = it }
            if (!eligible()) return
            val window = contexts.getOrNull(page.next)
            if (window != null) {
                val warmed = warm(window, eligible)
                // Pre-emption retains this window. A genuine model error is left to the normal
                // foreground error path, rather than spinning a retry loop in the background.
                if (warmed || eligible()) page.next++
            }
            if (page.next >= contexts.size)
                synchronized(lock) {
                    if (pages[id] === page) {
                        pages.remove(id)
                        completed.add(id)
                        if (completed.size > 512) completed.remove(completed.first())
                    }
                }
        } catch (_: Exception) {
            // Optional precomputation must never interrupt reading or report unrelated UI errors.
        } finally {
            synchronized(lock) {
                scheduled = false
                schedule(if (paused) 1200 else 250)
            }
        }
    }

    override fun close() =
        synchronized(lock) {
            closed = true
            pages.clear()
            completed.clear()
            worker.shutdownNow()
            Unit
        }

    companion object {
        /** Bound first-open CPU work; longer/different windows are computed on selection. */
        fun windows(words: List<Word>): List<LookupContext> {
            val result = linkedMapOf<String, LookupContext>()
            val flow = PageText(words)
            for (column in flow.flows) {
                var start = 0
                for (index in column.indices) {
                    val word = column[index]
                    val next = column.getOrNull(index + 1)
                    val paragraphEnd =
                        next != null &&
                            next.box.top - word.box.bottom >
                                word.box.height().coerceAtLeast(1f) * 1.4f
                    if (
                        index == column.lastIndex ||
                            paragraphEnd ||
                            index - start >= 39 ||
                            Regex("[.!?。！？][\"'’”)]*$").containsMatchIn(word.text)
                    ) {
                        val anchor = column[(start + index) / 2]
                        val context = LookupContext.fromWords(words, listOf(anchor))
                        val text = context?.let { SemanticLookup.contextText(it) }.orEmpty()
                        if (context != null && text.isNotBlank() && text.any(Char::isLetter)) {
                            result.putIfAbsent(text, context)
                            if (result.size >= 24) return result.values.toList()
                        }
                        start = index + 1
                    }
                }
            }
            return result.values.toList()
        }
    }
}
