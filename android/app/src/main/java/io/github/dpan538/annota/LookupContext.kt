package io.github.dpan538.annota

import java.text.Normalizer
import java.util.Locale
import kotlin.math.*

/** A short local reading window. It is never sent to a service or stored in preferences. */
data class LookupContext(val selected: String, val before: List<String>, val after: List<String>) {
    fun matches(query: String) = key(query) == key(selected)

    companion object {
        fun key(value: String): String =
            Normalizer.normalize(value, Normalizer.Form.NFKC)
                .lowercase(Locale.ROOT)
                .replace(Regex("[‐‑–—]"), "-")
                .replace(Regex("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$"), "")
                .replace(Regex("\\s+"), " ")
                .trim()

        fun tokens(value: String) =
            Regex("[\\p{L}\\p{N}]+(?:[-'’][\\p{L}\\p{N}]+)*")
                .findAll(key(value))
                .map { it.value }
                .toList()

        fun fromWords(words: List<Word>, selected: List<Word>): LookupContext? {
            val anchor = selected.firstOrNull() ?: return null
            val h =
                selected.map { it.box.height() }.sorted().let { it[it.size / 2] }.coerceAtLeast(1f)
            val nearby =
                words
                    .filter { abs(it.box.centerY() - anchor.box.centerY()) < h * 8 }
                    .sortedWith(compareBy<Word> { it.box.centerY() }.thenBy { it.box.left })
            val lines = mutableListOf<MutableList<Word>>()
            nearby.forEach { word ->
                val line = lines.lastOrNull()
                if (line == null || abs(word.box.centerY() - line.first().box.centerY()) > h * .6f)
                    lines.add(mutableListOf(word))
                else line.add(word)
            }
            fun groups(line: List<Word>): List<List<Word>> {
                val groups = mutableListOf<MutableList<Word>>()
                line
                    .sortedBy { it.box.left }
                    .forEach { word ->
                        val last = groups.lastOrNull()
                        if (last == null || word.box.left - last.last().box.right > h * 3.2f)
                            groups.add(mutableListOf(word))
                        else last.add(word)
                    }
                return groups
            }
            val anchorGroup =
                lines.flatMap(::groups).firstOrNull { it.contains(anchor) } ?: return null
            val left = anchorGroup.minOf { it.box.left }
            val right = anchorGroup.maxOf { it.box.right }
            val local =
                lines.flatMap { line ->
                    groups(line)
                        .filter { group ->
                            val gl = group.minOf { it.box.left }
                            val gr = group.maxOf { it.box.right }
                            min(right, gr) - max(left, gl) >= min(right - left, gr - gl) * .3f
                        }
                        .flatten()
                }
            val start = local.indexOfFirst { it == anchor }
            val end = local.indexOfLast { selected.contains(it) }
            if (start < 0 || end < start) return null
            val beforeWords = local.subList(max(0, start - 22), start)
            val sentenceStart =
                beforeWords.indexOfLast { Regex("[.!?。！？]$").containsMatchIn(it.text) } + 1
            val afterWords = local.subList(end + 1, min(local.size, end + 23))
            val sentenceEnd =
                afterWords.indexOfFirst { Regex("[.!?。！？]$").containsMatchIn(it.text) }
            return LookupContext(
                selected.joinToString(" ") { it.text },
                tokens(beforeWords.drop(sentenceStart).joinToString(" ") { it.text }).takeLast(22),
                tokens(
                        afterWords
                            .take(if (sentenceEnd < 0) afterWords.size else sentenceEnd + 1)
                            .joinToString(" ") { it.text }
                    )
                    .take(22),
            )
        }
    }
}
