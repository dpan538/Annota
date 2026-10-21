package io.github.dpan538.annota

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException

/** Consume the sender's temporary read grant by making a private, validated local copy. */
object ExternalPdf {
    fun uri(intent: Intent): Uri? {
        if (intent.action !in setOf(Intent.ACTION_VIEW, Intent.ACTION_SEND)) return null
        val stream =
            if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        val result =
            if (intent.action == Intent.ACTION_VIEW) intent.data
            else stream ?: intent.clipData?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.uri
        require(result != null && result.scheme in setOf("content", "file")) {
            "请通过其他应用打开或分享一个 PDF 文件"
        }
        return result
    }

    fun name(context: Context, uri: Uri): String {
        val name =
            runCatching {
                    context.contentResolver
                        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { if (it.moveToFirst()) it.getString(0) else null }
                }
                .getOrNull() ?: uri.lastPathSegment ?: "Imported PDF"
        return name
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[\\p{Cntrl}]"), "")
            .replace(Regex("(?i)\\.pdf$"), "")
            .trim()
            .take(200)
            .ifBlank { "Imported PDF" }
    }

    fun requirePdf(file: File) {
        val header = ByteArray(1024)
        val count = file.inputStream().use { it.read(header) }
        if (count < 5 || !String(header, 0, count, Charsets.ISO_8859_1).contains("%PDF-"))
            throw IOException("这不是可读取的 PDF 文件")
    }
}
