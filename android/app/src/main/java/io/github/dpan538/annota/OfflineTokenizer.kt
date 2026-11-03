package io.github.dpan538.annota

import org.json.JSONObject
import java.text.Normalizer

/** Frozen OPUS source SentencePiece Unigram, exported with scores and shared vocabulary IDs. */
class OfflineTokenizer(source: String, vocabulary: String) {
    private class Node {
        val children = HashMap<Char, Node>()
        var id = -1
        var score = 0.0
    }
    private val root = Node()
    private val vocab = JSONObject(vocabulary)
    private val inverse = Array(vocab.length()) { "" }
    private val unknownScore: Double

    init {
        val data = JSONObject(source)
        unknownScore = data.getDouble("minimumScore") - 10
        val pieces = data.getJSONArray("pieces")
        for (i in 0 until pieces.length()) {
            val p = pieces.getJSONArray(i)
            var node = root
            for (ch in p.getString(0)) node = node.children.getOrPut(ch) { Node() }
            node.id = p.getInt(2)
            node.score = p.getDouble(1)
        }
        for (piece in vocab.keys()) inverse[vocab.getInt(piece)] = piece
    }

    fun encode(value: String): LongArray {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .trim().replace(Regex("\\s+"), " ")
        val text = "▁" + normalized.replace(' ', '▁')
        val scores = DoubleArray(text.length + 1) { Double.NEGATIVE_INFINITY }
        val previous = IntArray(text.length + 1) { -1 }
        val ids = IntArray(text.length + 1) { -1 }
        scores[0] = 0.0
        for (start in text.indices) {
            if (!scores[start].isFinite()) continue
            var node = root
            var single = false
            for (end in start until text.length) {
                node = node.children[text[end]] ?: break
                if (node.id >= 0) {
                    if (end == start) single = true
                    val score = scores[start] + node.score
                    if (score > scores[end + 1]) {
                        scores[end + 1] = score
                        previous[end + 1] = start
                        ids[end + 1] = node.id
                    }
                }
            }
            if (!single) {
                val end = start + Character.charCount(text.codePointAt(start))
                val score = scores[start] + unknownScore
                if (score > scores[end]) {
                    scores[end] = score
                    previous[end] = start
                    ids[end] = -1
                }
            }
        }
        val parts = mutableListOf<Pair<Int, String>>()
        var end = text.length
        while (end > 0) {
            val start = previous[end]
            check(start >= 0) { "Unigram path is incomplete" }
            parts += ids[end] to text.substring(start, end)
            end = start
        }
        parts.reverse()
        val result = mutableListOf<Long>(5) // >>cmn_Hans<<
        var unknown = StringBuilder()
        fun flush() {
            if (unknown.isNotEmpty()) {
                result += vocab.optInt(unknown.toString(), 1).toLong()
                unknown = StringBuilder()
            }
        }
        for ((id, piece) in parts) {
            if (id < 0) unknown.append(piece)
            else { flush(); result += id.toLong() }
        }
        flush()
        result += 0L // EOS, never truncate an input sentence.
        return result.toLongArray()
    }

    fun decode(ids: List<Int>): String = ids.joinToString("") { inverse[it] }
        .replace('▁', ' ').trim()
}
