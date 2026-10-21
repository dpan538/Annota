package io.github.dpan538.annota

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.android.apksig.ApkVerifier
import java.io.*
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import org.json.JSONArray
import org.json.JSONObject

class Network(val context: Context) {
    class Task {
        val cancelled = AtomicBoolean(false)
        @Volatile var connection: HttpsURLConnection? = null

        fun cancel() {
            cancelled.set(true)
            connection?.disconnect()
        }

        fun check() {
            if (cancelled.get()) throw InterruptedIOException("已取消")
        }
    }

    data class Update(
        val versionCode: Long,
        val versionName: String,
        val notes: String,
        val url: String,
        val sha: String,
        val certificate: String,
    )

    private fun open(url: String, task: Task, github: Boolean): HttpsURLConnection {
        var current = url
        repeat(6) {
            task.check()
            val u = URL(current)
            require(u.protocol == "https" && u.userInfo == null) { "只允许 HTTPS" }
            if (github)
                require(
                    u.host in
                        setOf(
                            "api.github.com",
                            "github.com",
                            "release-assets.githubusercontent.com",
                            "objects.githubusercontent.com",
                        )
                ) {
                    "更新下载域名不被允许"
                }
            val c = u.openConnection() as HttpsURLConnection
            task.connection = c
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "Annota/${BuildConfig.VERSION_NAME}")
            c.setRequestProperty("Accept", "application/vnd.github+json")
            val code = c.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val next = c.getHeaderField("Location") ?: error("重定向地址缺失")
                current = URL(u, next).toString()
                c.disconnect()
            } else return c
        }
        error("更新重定向过多")
    }

    private fun read(c: HttpsURLConnection, task: Task, max: Int = 1024 * 1024): String =
        c.inputStream.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                task.check()
                val n = input.read(buf)
                if (n < 0) break
                require(out.size() + n <= max) { "服务器响应过大" }
                out.write(buf, 0, n)
            }
            out.toString("UTF-8")
        }

    private fun checkHttp(code: Int) {
        when (code) {
            404 -> error("未找到更新资源（GitHub 404），请稍后重试")
            403,
            429 -> error("GitHub 限流或拒绝访问，请稍后重试")
            in 200..299 -> Unit
            else -> error("服务响应 HTTP $code，请重试")
        }
    }

    companion object {
        fun previewUpdates(
            versionName: String = BuildConfig.VERSION_NAME,
            debug: Boolean = BuildConfig.DEBUG,
        ): Boolean =
            debug ||
                Regex("(?i).*(?:-dev|-alpha|-beta|-rc|preview|snapshot).*").matches(versionName)
    }

    fun releaseCandidates(releases: JSONArray, includePreview: Boolean): List<JSONObject> =
        (0 until releases.length())
            .map { releases.getJSONObject(it) }
            .filter { release ->
                !release.optBoolean("draft") &&
                    (includePreview || !release.optBoolean("prerelease")) &&
                    (0 until release.getJSONArray("assets").length()).any { i ->
                        release.getJSONArray("assets").getJSONObject(i).optString("name") ==
                            "annota-update.json"
                    }
            }

    fun checkUpdate(task: Task): Update? {
        val previews = previewUpdates()
        val endpoint = if (previews) "releases?per_page=30" else "releases/latest"
        val c = open("https://api.github.com/repos/dpan538/Annota/$endpoint", task, true)
        val releases =
            try {
                if (!previews && c.responseCode == 404) return null
                checkHttp(c.responseCode)
                val body = read(c, task)
                if (previews) JSONArray(body) else JSONArray().put(JSONObject(body))
            } finally {
                c.disconnect()
            }
        var newest: Update? = null
        for (release in releaseCandidates(releases, previews)) {
            task.check()
            val assets = release.getJSONArray("assets")
            val manifestUrl =
                (0 until assets.length())
                    .map { assets.getJSONObject(it) }
                    .first { it.getString("name") == "annota-update.json" }
                    .getString("browser_download_url")
            require(
                manifestUrl.startsWith("https://github.com/dpan538/Annota/releases/download/")
            ) {
                "更新下载域名不被允许"
            }
            val mc = open(manifestUrl, task, true)
            val manifest =
                try {
                    checkHttp(mc.responseCode)
                    JSONObject(read(mc, task, 65536))
                } finally {
                    mc.disconnect()
                }
            updateFromManifest(release, manifest)?.let { update ->
                if (newest == null || update.versionCode > newest!!.versionCode) newest = update
            }
        }
        return newest
    }

    fun updateFromManifest(release: JSONObject, m: JSONObject): Update? {
        val assets = release.getJSONArray("assets")
        val urls =
            (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .associate { it.getString("name") to it.getString("browser_download_url") }
        require(
            m.getInt("schemaVersion") == 1 && m.getString("packageName") == context.packageName
        ) {
            "更新包标识不匹配"
        }
        val code = m.getLong("versionCode")
        if (code <= BuildConfig.VERSION_CODE) return null
        val name = m.getString("apk")
        val url = urls[name] ?: error("Release 中缺少 APK")
        require(
            name.endsWith(".apk") &&
                url.startsWith("https://github.com/dpan538/Annota/releases/download/")
        )
        val sha = m.getString("sha256").lowercase()
        val cert = m.getString("certificateSha256").lowercase()
        require(sha.matches(Regex("[a-f0-9]{64}")) && cert.matches(Regex("[a-f0-9]{64}")))
        return Update(code, m.getString("versionName"), release.optString("body"), url, sha, cert)
    }

    fun download(update: Update, task: Task, progress: (Long, Long) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val part = File(dir, "download.part")
        val final = File(dir, "annota-${update.versionCode}.apk")
        val c = open(update.url, task, true)
        try {
            checkHttp(c.responseCode)
            val length = c.contentLengthLong
            require(length <= 512L * 1024 * 1024) { "更新包过大" }
            c.inputStream.use { input ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(65536)
                    var total = 0L
                    while (true) {
                        task.check()
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        require(total <= 512L * 1024 * 1024)
                        require(dir.usableSpace > 16L * 1024 * 1024) { "本地空间不足" }
                        out.write(buf, 0, n)
                        progress(total, length)
                    }
                    out.fd.sync()
                    if (length >= 0) require(total == length) { "下载不完整" }
                }
            }
            verify(part, update)
            task.check()
            check(part.renameTo(final))
            return final
        } finally {
            c.disconnect()
            part.delete()
        }
    }

    fun verify(file: File, update: Update) {
        require(sha256(file) == update.sha) { "APK SHA-256 校验失败" }
        val result =
            ApkVerifier.Builder(file)
                .setMinCheckedPlatformVersion(Build.VERSION.SDK_INT)
                .build()
                .verify()
        require(result.isVerified) { "APK 数字签名校验失败" }
        fun digest(bytes: ByteArray) =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it)
            }
        val certs = result.signerCertificates.map { digest(it.encoded) }.toSet()
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val installed =
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo!!
                .apkContentsSigners
                .map { digest(it.toByteArray()) }
                .toSet()
        require(certs == installed && update.certificate in installed) { "更新签名与当前安装版本不同，不能覆盖安装" }
        @Suppress("DEPRECATION")
        val info =
            pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
                ?: error("APK 解析失败")
        require(
            info.packageName == context.packageName &&
                info.longVersionCode == update.versionCode &&
                info.longVersionCode > BuildConfig.VERSION_CODE &&
                info.versionName == update.versionName
        ) {
            "APK 包名或版本不匹配"
        }
    }

    fun submitFeedback(
        endpoint: String,
        body: String,
        contact: String,
        id: String,
        task: Task,
    ): String {
        require(
            body.isNotBlank() &&
                body.codePointCount(0, body.length) <= 1000 &&
                contact.codePointCount(0, contact.length) <= 120
        )
        require(endpoint.isNotBlank()) { "反馈服务尚未配置，草稿已保留" }
        val u = URL(endpoint)
        require(u.protocol == "https" && u.userInfo == null)
        val c = u.openConnection() as HttpsURLConnection
        task.connection = c
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.instanceFollowRedirects = false
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        try {
            task.check()
            c.outputStream.use {
                it.write(
                    JSONObject()
                        .put("body", body)
                        .put("contact", contact)
                        .put("requestId", id)
                        .toString()
                        .toByteArray()
                )
            }
            require(c.responseCode in 200..299) { "反馈发送失败（HTTP ${c.responseCode}），草稿已保留" }
            val result = JSONObject(read(c, task, 16384))
            require(result.optBoolean("accepted") && result.optString("receipt").isNotBlank()) {
                "服务未提供有效回执，草稿已保留"
            }
            return result.getString("receipt")
        } finally {
            c.disconnect()
        }
    }
}
