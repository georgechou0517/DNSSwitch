package com.dnsswitch.web

import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.provider.Settings
import android.webkit.JavascriptInterface
import com.dnsswitch.MainActivity
import com.dnsswitch.config.ConfigManager
import com.dnsswitch.config.DnsPreset
import com.dnsswitch.config.PresetManager
import com.dnsswitch.dns.ChannelType
import com.dnsswitch.dns.DnsStats
import com.dnsswitch.update.UpdateManager
import com.dnsswitch.vpn.DnsVpnService
import org.json.JSONObject

/**
 * WebView ↔ 原生 桥接层。
 * JS 通过 window.NativeBridge.xxx() 调用，方法全部返回字符串（结构化数据为 JSON）。
 */
class WebAppInterface(private val activity: MainActivity) {

    @JavascriptInterface
    fun getConfig(): String = ConfigManager.toJson().toString()

    @JavascriptInterface
    fun saveConfig(json: String): String = try {
        ConfigManager.update(JSONObject(json))
        "ok"
    } catch (e: Exception) {
        "error:" + (e.message ?: "配置校验失败")
    }

    /** 返回 already_running / need_consent / starting */
    @JavascriptInterface
    fun startVpn(): String {
        if (DnsVpnService.isRunning) return "already_running"
        val consent = runCatching { VpnService.prepare(activity) }.getOrNull()
        return if (consent != null) {
            android.util.Log.i("DNSSwitch", "startVpn: 需要 VPN 授权（need_consent）")
            "need_consent"
        } else {
            android.util.Log.i("DNSSwitch", "startVpn: 授权已就绪，拉起服务（starting）")
            DnsStats.clearLastError()
            activity.runOnUiThread {
                runCatching { activity.startVpnService() }.onFailure {
                    DnsStats.onVpnError("无法启动前台服务：" + (it.message ?: it.javaClass.simpleName))
                }
            }
            "starting"
        }
    }

    /** 用户在引导页点击"去授权"后调用：走完整启动链（通知权限 → VPN 授权 → 启动） */
    @JavascriptInterface
    fun requestVpnConsent(): String {
        activity.runOnUiThread { activity.beginStartFlow() }
        return "launched"
    }

    @JavascriptInterface
    fun stopVpn(): String {
        DnsStats.clearLastError()
        activity.startService(
            Intent(activity, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_STOP)
        )
        return "stopped"
    }

    @JavascriptInterface
    fun getStatus(): String = DnsVpnService.statusJson()

    @JavascriptInterface
    fun getLogs(): String = DnsStats.logsJson().toString()

    @JavascriptInterface
    fun clearLogs(): String {
        DnsStats.clearLogs()
        return "ok"
    }

    /** 单通道测试：traditional / doh / dot */
    @JavascriptInterface
    fun testChannel(type: String): String {
        val t = when (type) {
            "traditional" -> ChannelType.TRADITIONAL
            "doh" -> ChannelType.DOH
            "dot" -> ChannelType.DOT
            else -> return """{"ok":false,"error":"未知通道"}"""
        }
        return DnsVpnService.testChannel(t)
    }

    @JavascriptInterface
    fun getUiMode(): String {
        val night = (activity.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        return if (night) "dark" else "light"
    }

    @JavascriptInterface
    fun openBatterySettings() {
        activity.runOnUiThread {
            runCatching {
                activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }.onFailure {
                runCatching {
                    activity.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + activity.packageName)
                        )
                    )
                }
            }
        }
    }

    /* ---------------- 自定义预设 ---------------- */

    /** 全部自定义预设（JSON 数组） */
    @JavascriptInterface
    fun getPresets(): String = PresetManager.toJsonArray().toString()

    /**
     * 导入 txt 文本。每行格式：`<地址> <名字> <分类>`，分类为行末 dns/doh/dot（不区分大小写）。
     * 返回 {"added":n,"merged":n,"invalid":n,"message":"...","samples":[...]}
     */
    @JavascriptInterface
    fun importPresetText(text: String): String = try {
        PresetManager.importText(text).toJsonObject().toString()
    } catch (e: Exception) {
        JSONObject().put("added", 0).put("merged", 0).put("invalid", 0)
            .put("message", "导入失败：" + (e.message ?: "未知错误")).toString()
    }

    /** 单条「存为预设」，JSON：{"name":..,"type":..,"v4":..,"v6":..,"host":..,"url":..} */
    @JavascriptInterface
    fun addPreset(json: String): String = try {
        val p = DnsPreset.parse(JSONObject(json))
            ?: return JSONObject().put("ok", false).put("message", "预设内容无效").toString()
        PresetManager.add(p).toJsonObject().apply { put("ok", true) }.toString()
    } catch (e: Exception) {
        JSONObject().put("ok", false).put("message", "保存失败：" + (e.message ?: "未知错误")).toString()
    }

    @JavascriptInterface
    fun deletePreset(id: String): String =
        if (PresetManager.delete(id)) "ok" else "not_found"

    @JavascriptInterface
    fun getAppVersion(): String = try {
        val pi = activity.packageManager.getPackageInfo(activity.packageName, 0)
        pi.versionName ?: "1.0.0"
    } catch (e: Exception) {
        "1.0.0"
    }

    /* ---------------- 检查更新 ---------------- */

    /** 异步检查，结果经 window.onUpdateResult(json) 回传；这里立即返回 "checking" 避免阻塞 JS */
    @JavascriptInterface
    fun checkUpdate(): String {
        UpdateManager.checkAsync(activity.applicationContext) { json ->
            activity.runOnUiThread { activity.deliverUpdateResult(json) }
        }
        return "checking"
    }

    /** 当前下载源：auto / github / gitee */
    @JavascriptInterface
    fun getUpdateSource(): String = UpdateManager.getSource()

    /** 手动切换下载源；未识别的值一律落到 auto */
    @JavascriptInterface
    fun setUpdateSource(source: String): String {
        UpdateManager.setSource(source)
        return UpdateManager.getSource()
    }

    /**
     * 下载 APK。Android 10+ 交给 DownloadManager（后台下载 + 通知，完成后自动调起安装）；
     * 老设备写不了应用私有目录，返回 "browser" 让前端改用浏览器打开下载链接。
     */
    @JavascriptInterface
    fun downloadUpdate(url: String, apkName: String): String =
        if (UpdateManager.downloadApk(activity, url, apkName) > 0) "downloading" else "browser"
}
