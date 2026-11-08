package io.github.dpan538.annota

import java.text.BreakIterator
import java.util.Locale

/** Bound work without silently truncating a selection or cutting a dependent clause in half. */
object TranslationInput {
    /**
     * Acronyms that must keep their case when the source is normalised for the model. Everything
     * else in ALL CAPS is ordinary prose that the cased SentencePiece model tokenises badly
     * (THREE -> TH+REE), so it is folded to lower case before encoding.
     */
    private val ACRONYMS =
        setOf(
            "AI", "API", "CPU", "GPU", "TPU", "RAM", "ROM", "SQL", "JSON", "XML", "HTML",
            "HTTP", "HTTPS", "TCP", "UDP", "DNS", "URL", "URI", "PDF", "OCR", "SDK", "IDE",
            "CLI", "GUI", "OS", "VM", "VPS", "SSD", "HDD", "USB", "GPU", "ML", "LLM", "RAG",
            "NLP", "CV", "RL", "CNN", "RNN", "LSTM", "GAN", "GRU", "K8S", "K8S", "ETCD",
            "HA", "HDFS", "YAML", "TOML", "CSV", "TSV", "JWT", "OAuth", "SSO", "RBAC", "CDN",
            "SLA", "QPS", "TPS", "RPC", "REST", "GRPC", "CRD", "CNI", "CSI", "PVC", "PV",
            "DNS", "VPC", "IAM", "EC2", "S3", "GCP", "AWS", "Azure", "K8s",
        )
    private val allCaps = Regex("\\b[A-Z][A-Z0-9]{1,7}\\b")

    fun normalizeForModel(text: String): String {
        val input = text.trim()
        if (input.isEmpty()) return input
        return allCaps.replace(input) { match ->
            val token = match.value
            if (token in ACRONYMS || token.lowercase(Locale.ROOT) in ACRONYMS.map { it.lowercase(Locale.ROOT) })
                token
            else token.lowercase(Locale.ROOT)
        }
    }

    class LimitExceeded : IllegalArgumentException("Select a shorter complete English sentence")

    const val MAX_CHARACTERS = 1024
    const val MAX_WORDS = 160
    const val MAX_SEGMENTS = 8
    const val MAX_SOURCE_TOKENS = 128
    private val words = Regex("[A-Za-z]+(?:['’-][A-Za-z]+)*")

    fun eligible(text: String): Boolean =
        text.length <= MAX_CHARACTERS &&
            text.none { it in '\u3400'..'\u9fff' } &&
            words.findAll(text).count() in 2..MAX_WORDS

    fun segments(text: String, tokenCount: (String) -> Int): List<String> {
        require(eligible(text)) { "Select an English phrase or short passage" }
        val input = text.trim()
        // Preserve useful context for short passages, even when they contain multiple sentences.
        if (tokenCount(input) <= MAX_SOURCE_TOKENS) return listOf(input)
        val iterator = BreakIterator.getSentenceInstance(Locale.ENGLISH).apply { setText(input) }
        val sentences = mutableListOf<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val part = input.substring(start, end).trim()
            if (part.isNotEmpty()) {
                // Sentence iterators may split after a title or initial. Join these before
                // budgeting.
                if (sentences.isNotEmpty() && continues(sentences.last()))
                    sentences[sentences.lastIndex] += " " + part
                else sentences += part
            }
            start = end
            end = iterator.next()
        }
        if (
            sentences.size !in 2..MAX_SEGMENTS ||
                sentences.any { tokenCount(it) > MAX_SOURCE_TOKENS }
        )
            throw LimitExceeded()
        // No comma-based chopping: it can detach a negation, condition or relative clause.
        return sentences
    }

    private fun continues(text: String): Boolean {
        val tail = text.substringAfterLast(' ').lowercase(Locale.ROOT)
        return tail in
            setOf(
                "mr.",
                "mrs.",
                "ms.",
                "dr.",
                "prof.",
                "sr.",
                "jr.",
                "e.g.",
                "i.e.",
                "vs.",
                "fig.",
                "no.",
                "st.",
            ) || Regex("(?:[A-Za-z]\\.)+").matches(text.substringAfterLast(' '))
    }
}
