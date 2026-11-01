package io.github.dpan538.annota

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.mlkit.common.MlKit
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.io.Closeable

/** Local English/Chinese translation. No model is requested without an explicit download call. */
interface SentenceTranslator : Closeable {
    fun modelReady(done: (Result<Boolean>) -> Unit)

    fun download(done: (Result<Unit>) -> Unit)

    fun translate(text: String, done: (Result<String>) -> Unit)
}

/** Policies shared by the selection menu, dictionary fallback and regression tests. */
object SentenceText {
    const val MAX_LENGTH = 4000

    fun chinese(text: String) = text.any { it in '\u3400'..'\u9fff' }

    fun looksLikeSentence(text: String): Boolean {
        val value = text.trim()
        return value.length > Dictionary.MAX_QUERY_LENGTH ||
            (chinese(value) && (value.length > 12 || value.any { it in "。！？；，" })) ||
            Regex("[A-Za-z]+(?:['’-][A-Za-z]+)*").findAll(value).count() >= 6 ||
            (value.any { it in ".!?;" } && value.any { it.isWhitespace() })
    }

    fun canOffer(text: String): Boolean =
        text.isNotBlank() &&
            (looksLikeSentence(text) ||
                (chinese(text) && text.count { it in '\u3400'..'\u9fff' } >= 4) ||
                Regex("[A-Za-z]+").findAll(text).count() >= 2)
}

/**
 * Lazy ML Kit wrapper: ordinary dictionary lookups never initialize the translation client. Input
 * and output stay on the device. ML Kit may send SDK metrics; the download UI discloses it.
 */
class DeviceSentenceTranslator(context: Context) : SentenceTranslator {
    init {
        initializeOnce(context.applicationContext)
    }

    companion object {
        private var initialized = false

        @Synchronized
        private fun initializeOnce(context: Context) {
            if (!initialized) {
                MlKit.initialize(context)
                initialized = true
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closed = false

    private class Reply<T>(private var callback: ((Result<T>) -> Unit)?) {
        @Synchronized fun take(): ((Result<T>) -> Unit)? = callback.also { callback = null }

        @Synchronized
        fun clear() {
            callback = null
        }
    }

    private val replies = mutableSetOf<Reply<*>>()
    private val clients = mutableMapOf<Boolean, Translator>()
    private val manager by lazy { RemoteModelManager.getInstance() }
    private val chineseModel by lazy {
        TranslateRemoteModel.Builder(TranslateLanguage.CHINESE).build()
    }

    override fun modelReady(done: (Result<Boolean>) -> Unit) {
        val reply = reply(done) ?: return
        runCatching {
                manager.isModelDownloaded(chineseModel).addOnCompleteListener { task ->
                    deliver(
                        reply,
                        if (task.isSuccessful) Result.success(task.result == true)
                        else
                            Result.failure(
                                task.exception ?: IllegalStateException("Model check failed")
                            ),
                    )
                }
            }
            .onFailure { deliver(reply, Result.failure(it)) }
    }

    override fun download(done: (Result<Unit>) -> Unit) {
        val reply = reply(done) ?: return
        runCatching {
                manager
                    .download(chineseModel, DownloadConditions.Builder().requireWifi().build())
                    .addOnCompleteListener { task ->
                        deliver(
                            reply,
                            if (task.isSuccessful) Result.success(Unit)
                            else
                                Result.failure(
                                    task.exception ?: IllegalStateException("Model download failed")
                                ),
                        )
                    }
            }
            .onFailure { deliver(reply, Result.failure(it)) }
    }

    override fun translate(text: String, done: (Result<String>) -> Unit) {
        val reply = reply(done) ?: return
        if (text.isBlank() || text.length > SentenceText.MAX_LENGTH) {
            deliver(reply, Result.failure(IllegalArgumentException("Select a shorter sentence")))
            return
        }
        runCatching {
                val fromChinese = SentenceText.chinese(text)
                val client =
                    clients.getOrPut(fromChinese) {
                        Translation.getClient(
                            TranslatorOptions.Builder()
                                .setSourceLanguage(
                                    if (fromChinese) TranslateLanguage.CHINESE
                                    else TranslateLanguage.ENGLISH
                                )
                                .setTargetLanguage(
                                    if (fromChinese) TranslateLanguage.ENGLISH
                                    else TranslateLanguage.CHINESE
                                )
                                .build()
                        )
                    }
                // Unlike downloadModelIfNeeded, translate cannot silently fetch a missing model.
                client.translate(text).addOnCompleteListener { task ->
                    deliver(
                        reply,
                        if (task.isSuccessful && !task.result.isNullOrBlank())
                            Result.success(task.result)
                        else
                            Result.failure(
                                task.exception ?: IllegalStateException("Translation failed")
                            ),
                    )
                }
            }
            .onFailure { deliver(reply, Result.failure(it)) }
    }

    private fun <T> reply(done: (Result<T>) -> Unit): Reply<T>? =
        synchronized(replies) { if (closed) null else Reply(done).also { replies.add(it) } }

    private fun <T> deliver(reply: Reply<T>, value: Result<T>) {
        if (closed) {
            reply.clear()
            synchronized(replies) { replies.remove(reply) }
            return
        }
        main.post {
            synchronized(replies) { replies.remove(reply) }
            val callback = reply.take()
            if (!closed) callback?.invoke(value)
        }
    }

    override fun close() {
        synchronized(replies) {
            closed = true
            // An SDK download can outlive this panel while waiting for Wi-Fi. Release the
            // callbacks now so its eventual task listener cannot retain the closed Activity.
            replies.forEach { it.clear() }
            replies.clear()
        }
        main.removeCallbacksAndMessages(null)
        clients.values.forEach { it.close() }
        clients.clear()
    }
}
