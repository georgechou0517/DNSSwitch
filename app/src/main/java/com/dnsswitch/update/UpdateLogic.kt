package com.dnsswitch.update

import org.json.JSONArray
import org.json.JSONObject

/**
 * 更新检查的纯逻辑：版本号归一化与比较、GitHub / Gitee Release JSON 解析。
 *
 * 刻意不引用任何 Android 类，以便在 JVM 上直接单测（见 UpdateLogicTest）。
 * 网络请求、下载、持久化等 IO 全部在 [UpdateManager] 里。
 */
object UpdateLogic {

    /** Release 页面地址（查询失败时给用户的兜底入口） */
    const val PAGE_GITHUB = "https://github.com/georgechou0517/DNSSwitch/releases"
    const val PAGE_GITEE = "https://gitee.com/georgechou17/DNSSwitch/releases"

    /** 统一的「最新版本」描述 */
    data class ReleaseInfo(
        val version: String,
        val rawTag: String,
        val name: String,
        val notes: String,
        val apkUrl: String,
        val apkName: String,
        val pageUrl: String
    ) {
        fun toJson() = JSONObject().apply {
            put("version", version)
            put("rawTag", rawTag)
            put("name", name)
            put("notes", notes)
            put("apkUrl", apkUrl)
            put("apkName", apkName)
            put("pageUrl", pageUrl)
        }
    }

    private val VERSION_RE = Regex("\\d+(?:\\.\\d+)*")

    /**
     * 从 tag / versionName 中提取 `数字(.数字)*`。
     * `v26.10.1`、`26.10.1-debug`、`DNSSwitch-26.10.1-release` 都得 `26.10.1`。
     */
    fun normalizeVersion(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return VERSION_RE.find(raw)?.value ?: ""
    }

    /** 逐段按数值比较（缺位补 0）：a > b 返回正数，相等返回 0 */
    fun compareVersion(a: String, b: String): Int {
        val pa = segments(a)
        val pb = segments(b)
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0L }
            val y = pb.getOrElse(i) { 0L }
            if (x != y) return if (x > y) 1 else -1
        }
        return 0
    }

    private fun segments(v: String): List<Long> =
        if (v.isBlank()) emptyList() else v.split('.').map { it.toLongOrNull() ?: 0L }

    /** 最新版本是否比当前版本新（任一侧无法解析出版本号时保守返回 false） */
    fun isNewer(latest: String, current: String): Boolean {
        val l = normalizeVersion(latest)
        val c = normalizeVersion(current)
        if (l.isEmpty() || c.isEmpty()) return false
        return compareVersion(l, c) > 0
    }

    fun parseGitHub(json: String): ReleaseInfo? = parseRelease(json, PAGE_GITHUB)

    fun parseGitee(json: String): ReleaseInfo? = parseRelease(json, PAGE_GITEE)

    /**
     * GitHub 与 Gitee 的 Release JSON 字段名基本一致（tag_name / name / body / html_url / assets），
     * 故统一解析，差异只在页面兜底地址。
     */
    fun parseRelease(json: String, pageFallback: String = ""): ReleaseInfo? = runCatching {
        val o = JSONObject(json)
        val tag = o.optString("tag_name").ifBlank { o.optString("name") }
        val version = normalizeVersion(tag)
        if (version.isEmpty()) return null
        val apk = pickApkAsset(o.optJSONArray("assets"))
        ReleaseInfo(
            version = version,
            rawTag = tag,
            name = o.optString("name").ifBlank { tag },
            notes = o.optString("body"),
            apkUrl = apk?.first.orEmpty(),
            apkName = apk?.second.orEmpty(),
            pageUrl = o.optString("html_url").ifBlank { pageFallback }
        )
    }.getOrNull()

    /** 挑 APK：优先名字不含 debug 的包；只有 debug 包时才退回 debug 包 */
    private fun pickApkAsset(assets: JSONArray?): Pair<String, String>? {
        if (assets == null) return null
        var debugOne: Pair<String, String>? = null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name")
            val url = a.optString("browser_download_url")
            if (url.isBlank() || !name.endsWith(".apk", ignoreCase = true)) continue
            if (!name.contains("debug", ignoreCase = true)) return url to name
            if (debugOne == null) debugOne = url to name
        }
        return debugOne
    }
}
