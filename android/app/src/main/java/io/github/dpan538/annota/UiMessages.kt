package io.github.dpan538.annota

/** Translate application failure messages at the UI boundary; document text is unchanged. */
object UiMessages {
    fun english(message: String): String {
        var result = message
        for ((zh, en) in replacements) result = result.replace(zh, en)
        return result
    }

    private val replacements =
        listOf(
            "反馈服务尚未配置，草稿已保留" to "Feedback service is not configured. Draft kept.",
            "服务未提供有效回执，草稿已保留" to "The service did not return a valid receipt. Draft kept.",
            "反馈发送失败（HTTP " to "Feedback failed (HTTP ",
            "），草稿已保留" to "). Draft kept.",
            "尚无正式发布的 APK（GitHub 404），可稍后重试" to
                "No stable APK release yet (GitHub 404). Try again later.",
            "未找到更新资源（GitHub 404），请稍后重试" to
                "Update resource not found (GitHub 404). Try again later.",
            "GitHub 限流或拒绝访问，请稍后重试" to "GitHub rate limit or access denied. Try again later.",
            "服务响应 HTTP " to "Server returned HTTP ",
            "，请重试" to ". Please retry.",
            "此 Release 尚无 Annota 更新清单" to "This release has no Annota update manifest.",
            "Release 中缺少 APK" to "No APK asset in this release.",
            "更新包标识不匹配" to "Update package identity does not match.",
            "更新下载域名不被允许" to "Update download host is not allowed.",
            "更新签名与当前安装版本不同，不能覆盖安装" to
                "The update signature differs from this installation. It cannot replace it.",
            "APK SHA-256 校验失败" to "APK SHA-256 verification failed.",
            "APK 数字签名校验失败" to "APK signature verification failed.",
            "APK 包名或版本不匹配" to "APK package or version does not match.",
            "APK 解析失败" to "Could not parse the APK.",
            "更新包过大" to "Update package is too large.",
            "下载不完整" to "Download is incomplete.",
            "重定向地址缺失" to "Redirect location is missing.",
            "更新重定向过多" to "Too many update redirects.",
            "服务器响应过大" to "Server response is too large.",
            "只允许 HTTPS" to "Only HTTPS is allowed.",
            "本地空间不足，至少保留 16 MiB" to "Not enough local space. Keep at least 16 MiB free.",
            "本地空间不足" to "Not enough local space.",
            "文件超过允许大小" to "File exceeds the size limit.",
            "无效的笔迹快照" to "Invalid ink snapshot.",
            "备份路径无效" to "Invalid backup path.",
            "备份校验失败" to "Backup verification failed.",
            "初始文档校验失败" to "Initial document verification failed",
            "初始文档页数不符" to "Initial document page count does not match",
            "PDF 权限限制，无法导入为可批注文档" to "PDF permissions do not allow import for annotation.",
            "PDF 页数不受支持" to "Unsupported PDF page count.",
            "PDF 纸张尺寸异常" to "Invalid PDF page dimensions.",
            "图片解码失败" to "Could not decode the image.",
            "缺少 PDF" to "Missing PDF.",
            "词库校验失败" to "Dictionary verification failed.",
            "暂不支持整句翻译" to "Sentence translation is unavailable.",
            "已取消" to "Cancelled",
        )
}
