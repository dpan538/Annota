package io.github.dpan538.annota

import java.text.Normalizer
import java.util.Locale
import kotlin.math.*

/** Geometric reading flows: repeated gutters separate columns, spanning headings stay separate. */
class PageText(val words: List<Word>) {
    val flows: List<List<Word>>

    init {
        val h =
            words
                .map { it.box.height() }
                .sorted()
                .let { it.getOrNull(it.size / 2) ?: 10f }
                .coerceAtLeast(1f)
        val lines = mutableListOf<MutableList<Word>>()
        words.sortedWith(compareBy<Word> { it.box.centerY() }.thenBy { it.box.left }).forEach { w ->
            val line = lines.lastOrNull()
            if (line == null || abs(w.box.centerY() - line.first().box.centerY()) > h * .65f)
                lines.add(mutableListOf(w))
            else line.add(w)
        }
        val runs =
            lines.map { line ->
                val groups = mutableListOf<MutableList<Word>>()
                line
                    .sortedBy { it.box.left }
                    .forEach { w ->
                        val last = groups.lastOrNull()
                        if (last == null || w.box.left - last.last().box.right > h * 3.2f)
                            groups.add(mutableListOf(w))
                        else last.add(w)
                    }
                groups
            }
        data class Gap(val left: Float, val right: Float, val line: Int)
        val gaps =
            runs.flatMapIndexed { i, r ->
                r.zipWithNext().map { (a, b) -> Gap(a.last().box.right, b.first().box.left, i) }
            }
        val cuts = mutableListOf<Float>()
        var remaining = gaps
        while (remaining.isNotEmpty()) {
            val best =
                remaining
                    .map { (it.left + it.right) / 2 }
                    .maxBy { x ->
                        remaining
                            .filter { x > it.left && x < it.right }
                            .map { it.line }
                            .distinct()
                            .size
                    }
            val supporting = remaining.filter { best > it.left && best < it.right }
            if (supporting.map { it.line }.distinct().size < 2) break
            cuts += best
            remaining = remaining.filter { best <= it.left || best >= it.right }
        }
        cuts.sort()
        val groups = linkedMapOf<String, MutableList<Word>>()
        runs.forEachIndexed { line, r ->
            r.forEachIndexed { n, run ->
                val left = run.first().box.left
                val right = run.last().box.right
                val key =
                    if (cuts.any { it > left && it < right }) "heading-$line-$n"
                    else "column-${cuts.count { it < (left + right) / 2 }}"
                groups.getOrPut(key) { mutableListOf() }.addAll(run)
            }
        }
        flows = groups.values.map { it.toList() }
    }

    fun flow(anchor: Word) = flows.firstOrNull { anchor in it }.orEmpty()

    fun range(anchor: Word, end: Word): List<Word> {
        val flow = flow(anchor)
        val a = flow.indexOf(anchor)
        val b = flow.indexOf(end)
        return if (a < 0 || b < 0) listOf(anchor) else flow.subList(min(a, b), max(a, b) + 1)
    }

    fun nearest(anchor: Word, x: Float, y: Float): Word? =
        flow(anchor).minByOrNull {
            val dx = max(0f, max(it.box.left - x, x - it.box.right))
            val dy = max(0f, max(it.box.top - y, y - it.box.bottom))
            dx * dx + dy * dy * 4
        }

    companion object {
        private fun isCjk(c: Char?) =
            c != null && (c in '\u3400'..'\u9fff' || c in '\u3040'..'\u30ff')

        fun join(words: List<Word>): String = buildString {
            words.forEachIndexed { i, w ->
                if (
                    i > 0 && !(isCjk(words[i - 1].text.lastOrNull()) && isCjk(w.text.firstOrNull()))
                )
                    append(' ')
                append(w.text)
            }
        }

        fun normalize(text: String) =
            Normalizer.normalize(text, Normalizer.Form.NFKC)
                .lowercase(Locale.ROOT)
                .replace(Regex("[‐‑–—]"), "-")
                .replace(Regex("\\s+"), " ")
                .trim()
    }
}

data class SearchHit(val pageId: String, val words: List<Word>, val snippet: String)

fun PageText.find(query: String, pageId: String, limit: Int = 1000): List<SearchHit> {
    val q = PageText.normalize(query)
    if (q.isEmpty()) return emptyList()
    val hits = mutableListOf<SearchHit>()
    for (flow in flows) {
        val text = StringBuilder()
        val owners = mutableListOf<Int>()
        flow.forEachIndexed { i, w ->
            val joinedCjk =
                i > 0 &&
                    flow[i - 1].text.lastOrNull()?.let {
                        it in '\u3400'..'\u9fff' || it in '\u3040'..'\u30ff'
                    } == true &&
                    w.text.firstOrNull()?.let {
                        it in '\u3400'..'\u9fff' || it in '\u3040'..'\u30ff'
                    } == true
            val previous = flow.getOrNull(i - 1)
            val wrapped =
                previous != null &&
                    previous.text.endsWith("-") &&
                    w.box.centerY() - previous.box.centerY() > previous.box.height() * .6f
            if (wrapped && '-' !in q && text.endsWith("-")) {
                text.deleteCharAt(text.lastIndex)
                owners.removeAt(owners.lastIndex)
            }
            if (text.isNotEmpty() && !joinedCjk && !wrapped) {
                text.append(' ')
                owners += i
            }
            val value = PageText.normalize(w.text)
            text.append(value)
            repeat(value.length) { owners += i }
        }
        var start = 0
        while (hits.size < limit) {
            val at = text.indexOf(q, start)
            if (at < 0) break
            val a = owners[at]
            val b = owners[at + q.length - 1]
            hits +=
                SearchHit(
                    pageId,
                    flow.subList(a, b + 1),
                    PageText.join(flow.subList(max(0, a - 5), min(flow.size, b + 7))).take(180),
                )
            start = at + max(1, q.length)
        }
        if (hits.size == limit) break
    }
    return hits
}
