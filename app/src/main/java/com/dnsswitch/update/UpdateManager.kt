package com.dnsswitch.update

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * 检查更新：从 GitHub / Gitee 的 latest Release 取最新版本，并按地区选择下载源。
 *
 * 设计取舍：
 * - 版本比较与 JSON 解析全部下沉到 [UpdateLogic]（纯逻辑、可单测），这里只做 IO。
 * - 地区判断优先查 IP 归属接口，失败后回退到「系统语言 + 时区」猜测，结果缓存 7 天，
 *   避免每次检查更新都多一个网络请求。
 * - 下载交给系统 DownloadManager（后台、带通知）；APK 落在应用私有外部目录，
 *   因此 Android 10+ 不需要任何存储权限。Android 9 及以下不支持该目录，
 *   回退为「用浏览器打开下载链接」，不为了老设备去申请存储权限。
 */
object UpdateManager {

    const val SOURCE_AUTO = "auto"
    const val SOURCE_GITHUB = "github"
    const val SOURCE_GITEE = "gitee"

    private const val PREFS = "dnsswitch_update"
    private const val KEY_SOURCE = "source"
    private const val KEY_REGION = "region"
    private const val KEY_REGION_AT = "region_at"
    private const val REGION_TTL_MS = 7 * 24 * 60 * 60 * 1000L

    private const val UA = "DNSSwitch-Android"
    private const val API_GITHUB =
        "https://api.github.com/repos/georgechou0517/DNSSwitch/releases/latest"
    private const val API_GITEE =
        "https://gitee.com/api/v5/repos/georgechou17/DNSSwitch/releases/latest"

    private const val PROBE_IPIP = "https://myip.ipip.net/json"
    private const val PROBE_IPAPI = "https://ip-api.com/json/?fields=status,countryCode"

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    /** 地区探测单独用短超时，避免拖慢「检查更新」 */
    private val probeHttp = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private var prefs: android.content.SharedPreferences? = null
    private var appContext: Context? = null

    @Synchronized
    fun init(context: Context) {
        if (prefs == null) {
            appContext = context.applicationContext
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    /* ---------------- 下载源偏好 ---------------- */

    @Synchronized
    fun getSource(): String = prefs?.getString(KEY_SOURCE, SOURCE_AUTO) ?: SOURCE_AUTO

    @Synchronized
    fun setSource(source: String) {
        val v = if (source == SOURCE_GITHUB || source == SOURCE_GITEE) source else SOURCE_AUTO
        prefs?.edit()?.putString(KEY_SOURCE, v)?.apply()
    }

    /** auto 按地区落到具体源；手动指定则直接返回 */
    private fun resolveSource(): String {
        val pref = getSource()
        if (pref != SOURCE_AUTO) return pref
        return if (regionCached() == "cn") SOURCE_GITEE else SOURCE_GITHUB
    }

    fun pageUrlOf(source: String): String =
        if (source == SOURCE_GITEE) UpdateLogic.PAGE_GITEE else UpdateLogic.PAGE_GITHUB

    /* ---------------- 检查更新 ---------------- */

    fun currentVersion(context: Context): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: ""

    /**
     * 异步检查；结果 JSON 通过 [deliver] 回传（调用方负责切到主线程）。
     * 返回字段见 [checkSync]。
     */
    fun checkAsync(context: Context, deliver: (String) -> Unit) {
        Thread {
            val json = try {
                checkSync(context)
            } catch (e: Throwable) {
                JSONObject().put("ok", false)
                    .put("error", "检查失败：" + (e.message ?: e.javaClass.simpleName))
            }
            deliver(json.toString())
        }.apply { isDaemon = true; name = "update-check" }.start()
    }

    private fun checkSync(context: Context): JSONObject {
        val source = resolveSource()
        val current = currentVersion(context)
        val base = JSONObject().put("source", source).put("current", current)
            .put("region", regionCached())

        val info = when (source) {
            SOURCE_GITEE -> fetch(API_GITEE)?.let(UpdateLogic::parseGitee)
            else -> fetch(API_GITHUB)?.let(UpdateLogic::parseGitHub)
        }
        if (info == null) {
            return base.put("ok", false)
                .put("error", "未能获取最新版本信息，可前往 Release 页面查看")
                .put("pageUrl", pageUrlOf(source))
        }
        return base.put("ok", true)
            .put("latest", info.version)
            .put("hasUpdate", UpdateLogic.isNewer(info.version, current))
            .put("name", info.name)
            .put("notes", info.notes)
            .put("apkUrl", info.apkUrl)
            .put("apkName", info.apkName)
            .put("pageUrl", info.pageUrl.ifBlank { pageUrlOf(source) })
    }

    private fun fetch(url: String): String? = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", UA).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.string()
        }
    }.getOrNull()

    /* ---------------- 地区判断 ---------------- */

    @Synchronized
    private fun regionCached(): String {
        val p = prefs ?: return "other"
        val v = p.getString(KEY_REGION, null)
        val at = p.getLong(KEY_REGION_AT, 0L)
        if (!v.isNullOrEmpty() && System.currentTimeMillis() - at < REGION_TTL_MS) return v
        val fresh = detectRegion()
        p.edit().putString(KEY_REGION, fresh).putLong(KEY_REGION_AT, System.currentTimeMillis())
            .apply()
        return fresh
    }

    /** 只在 [SOURCE_AUTO] 下被调用一次；结果写入缓存 */
    private fun detectRegion(): String {
        val country = probeCountry()
        if (!country.isNullOrBlank()) {
            return if (country.contains("中国") || country.equals("CN", ignoreCase = true)
            ) "cn" else "other"
        }
        return if (guessCnByLocale()) "cn" else "other"
    }

    /** 依次尝试两个 IP 归属接口；都失败返回 null */
    private fun probeCountry(): String? {
        runCatching {
            val s = probe(PROBE_IPIP) ?: return@runCatching
            val o = JSONObject(s)
            val arr = o.optJSONArray("data")
            if (arr != null && arr.length() > 0) {
                val first = arr.optString(0)
                if (first.isNotBlank()) return first
            }
            o.optString("country").takeIf { it.isNotBlank() }?.let { return it }
        }
        runCatching {
            val s = probe(PROBE_IPAPI) ?: return@runCatching
            val o = JSONObject(s)
            if (o.optString("status") == "success") {
                o.optString("countryCode").takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return null
    }

    private fun probe(url: String): String? = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", UA).get().build()
        probeHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.string()
        }
    }.getOrNull()

    /** 接口不可用时的兜底：中文语言 + 亚洲时区视为中国大陆 */
    private fun guessCnByLocale(): Boolean {
        val l = Locale.getDefault()
        val zh = l.language.equals("zh", ignoreCase = true)
        val cnRegion = l.country.equals("CN", ignoreCase = true)
        val tz = TimeZone.getDefault().id
        return cnRegion || (zh && tz.startsWith("Asia/"))
    }

    /* ---------------- 下载 ---------------- */

    /**
     * 用系统 DownloadManager 下载 APK。
     * @return 下载任务 id；不支持后台下载（Android 9 及以下且无法写入）时返回 -1，调用方应改为打开浏览器。
     */
    fun downloadApk(context: Context, url: String, apkName: String): Long {
        if (url.isBlank()) return -1L
        val name = safeName(apkName)
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return -1L
        return runCatching {
            val req = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle("DNSSwitch 更新")
                setDescription(name)
                setMimeType("application/vnd.android.package-archive")
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                addRequestHeader("User-Agent", UA)
                if (Build.VERSION.SDK_INT >= 29) {
                    // 应用私有外部目录：Android 10+ 无需任何存储权限
                    setDestinationInExternalFilesDir(
                        context, Environment.DIRECTORY_DOWNLOADS, name
                    )
                } else {
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                }
            }
            dm.enqueue(req)
        }.getOrDefault(-1L)
    }

    /** 下载目录与文件名，供 FileProvider / 安装器定位 */
    fun apkFile(context: Context, apkName: String): java.io.File =
        java.io.File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir,
            safeName(apkName)
        )

    private fun safeName(n: String): String {
        val base = n.substringAfterLast('/').trim()
        return if (base.isBlank() || !base.endsWith(".apk", ignoreCase = true)) {
            "DNSSwitch-update.apk"
        } else base
    }
}
