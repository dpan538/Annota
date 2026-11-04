package io.github.dpan538.annota

import java.text.Normalizer
import java.util.Locale

/**
 * Independent implementation of the bundled all-MiniLM-L6-v2 BERT uncased tokenizer contract. Its
 * tokenizer.json specifies clean_text, handle_chinese_chars, lowercase and WordPiece with
 * max_input_chars_per_word=100. No compatibility normalization or PDF rewriting is applied here:
 * https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/blob/main/tokenizer.json
 */
class WordPieceTokenizer(vocabulary: List<String>) {
    class InputTooLongException(message: String) : IllegalArgumentException(message)

    private val vocab = vocabulary.withIndex().associate { it.value to it.index.toLong() }
    private val specials = listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]")
    private val specialPattern = Regex(specials.joinToString("|") { Regex.escape(it) })
    private val punctuationTypes =
        setOf(
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt(),
        )

    init {
        require(vocabulary.size == vocab.size) { "Duplicate WordPiece vocabulary entries" }
        require(specials.all { it in vocab }) { "Missing BERT special tokens" }
    }

    /** Token count includes CLS and SEP. No overflow is silently truncated. */
    fun tokenCount(text: String): Int = tokenize(text).size + 2

    fun encode(text: String): LongArray {
        val pieces = tokenize(text)
        if (pieces.size + 2 > MAX_TOKENS) {
            throw InputTooLongException("Semantic input exceeds $MAX_TOKENS WordPiece tokens")
        }
        return (listOf("[CLS]") + pieces + "[SEP]").map { vocab.getValue(it) }.toLongArray()
    }

    fun tokenize(text: String): List<String> {
        if (text.length > MAX_INPUT_CHARS) {
            throw InputTooLongException("Semantic input exceeds $MAX_INPUT_CHARS characters")
        }
        val result = mutableListOf<String>()
        var start = 0
        // Added special tokens are matched before lowercasing, exactly as the frozen tokenizer.
        for (match in specialPattern.findAll(text)) {
            basic(text.substring(start, match.range.first)).forEach { result += wordPieces(it) }
            result += match.value
            start = match.range.last + 1
        }
        basic(text.substring(start)).forEach { result += wordPieces(it) }
        return result
    }

    private fun basic(text: String): List<String> {
        val clean = StringBuilder()
        text.codePoints().forEach { cp ->
            when {
                cp == 0 || cp == 0xfffd || isControl(cp) -> Unit
                isWhitespace(cp) -> clean.append(' ')
                isChinese(cp) -> clean.append(' ').appendCodePoint(cp).append(' ')
                else -> clean.appendCodePoint(cp)
            }
        }
        // HF's fast BERT normalizer strips NFD marks before Unicode character lowercasing.
        // Per-codepoint lowercase avoids Java's context-sensitive final-sigma transformation.
        val lowered = StringBuilder()
        Normalizer.normalize(clean.toString(), Normalizer.Form.NFD).codePoints().forEach { cp ->
            if (Character.getType(cp) != Character.NON_SPACING_MARK.toInt()) {
                lowered.append(String(Character.toChars(cp)).lowercase(Locale.ROOT))
            }
        }
        val result = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) {
                result += current.toString()
                current.setLength(0)
            }
        }
        lowered.toString().codePoints().forEach { cp ->
            when {
                isWhitespace(cp) -> flush()
                isPunctuation(cp) -> {
                    flush()
                    result += String(Character.toChars(cp))
                }
                else -> current.appendCodePoint(cp)
            }
        }
        flush()
        return result
    }

    private fun wordPieces(token: String): List<String> {
        val points = token.codePoints().toArray()
        if (points.size > 100) return listOf("[UNK]")
        val result = mutableListOf<String>()
        var start = 0
        while (start < points.size) {
            var end = points.size
            var piece: String? = null
            while (end > start) {
                val candidate =
                    (if (start == 0) "" else "##") +
                        java.lang.String(points, start, end - start).toString()
                if (candidate in vocab) {
                    piece = candidate
                    break
                }
                end--
            }
            if (piece == null) return listOf("[UNK]")
            result += piece
            start = end
        }
        return result
    }

    private fun isWhitespace(cp: Int): Boolean =
        cp == 32 ||
            cp == 9 ||
            cp == 10 ||
            cp == 13 ||
            Character.getType(cp) == Character.SPACE_SEPARATOR.toInt() ||
            Character.getType(cp) == Character.LINE_SEPARATOR.toInt() ||
            Character.getType(cp) == Character.PARAGRAPH_SEPARATOR.toInt()

    private fun isControl(cp: Int): Boolean =
        cp != 9 &&
            cp != 10 &&
            cp != 13 &&
            (Character.getType(cp) == Character.CONTROL.toInt() ||
                Character.getType(cp) == Character.FORMAT.toInt() ||
                Character.getType(cp) == Character.UNASSIGNED.toInt() ||
                Character.getType(cp) == Character.PRIVATE_USE.toInt() ||
                Character.getType(cp) == Character.SURROGATE.toInt())

    private fun isPunctuation(cp: Int): Boolean =
        cp in 33..47 ||
            cp in 58..64 ||
            cp in 91..96 ||
            cp in 123..126 ||
            Character.getType(cp) in punctuationTypes

    private fun isChinese(cp: Int): Boolean =
        cp in 0x4e00..0x9fff ||
            cp in 0x3400..0x4dbf ||
            cp in 0x20000..0x2a6df ||
            cp in 0x2a700..0x2b73f ||
            cp in 0x2b740..0x2b81f ||
            cp in 0x2b920..0x2ceaf ||
            cp in 0xf900..0xfaff ||
            cp in 0x2f800..0x2fa1f

    companion object {
        const val MAX_TOKENS = 128
        const val MAX_INPUT_CHARS = 8192
        const val VERSION = "bert-uncased-wordpiece-v1"
    }
}
