package com.dnsswitch

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
import com.dnsswitch.config.ConfigManager
import com.dnsswitch.config.PresetManager
import com.dnsswitch.dns.DnsStats
import com.dnsswitch.vpn.DnsVpnService
import com.dnsswitch.web.WebAppInterface
import org.json.JSONObject

/**
 * 唯一的 Activity：承载 WebView，加载 assets/www/index.html。
 * 负责 VPN 授权流程、通知权限申请、向 JS 推送状态与 UI 模式。
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private val handler = Handler(Looper.getMainLooper())
    private var paused = false
    private var pendingNotificationStart = false
    /** 预设 txt 导入：系统文件选择器回调 */
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val pushTask = object : Runnable {
        override fun run() {
            if (!paused) {
                val status = DnsVpnService.statusJson()
                webView.evaluateJavascript(
                    "window.onNativeStatus&&window.onNativeStatus(${JSONObject.quote(status)})",
                    null
                )
            }
            handler.postDelayed(this, PUSH_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConfigManager.init(applicationContext)
        PresetManager.init(applicationContext)

        webView = WebView(this)
        webView.setBackgroundColor(0xFFFFFFFF.toInt())
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                if (url.startsWith("file:///")) return false
                // 外链交给系统浏览器
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                return true
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                val intent = runCatching { params.createIntent() }.getOrNull()
                    ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "text/plain"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                return try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(
                        Intent.createChooser(intent, "选择 DNS 列表文件"),
                        REQ_FILE_CHOOSER
                    )
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }
        }
        webView.addJavascriptInterface(WebAppInterface(this), "NativeBridge")
        setContentView(webView)
        webView.loadUrl("file:///android_asset/www/index.html")
        handler.postDelayed(pushTask, PUSH_INTERVAL_MS)
    }

    /** 启动流程：通知权限（API 33+）→ VPN 授权 → 启动服务 */
    fun beginStartFlow() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingNotificationStart = true
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                REQ_NOTIFICATION
            )
            return
        }
        proceedStartVpn()
    }

    private fun proceedStartVpn() {
        if (DnsVpnService.isRunning) {
            emit("vpn_started")
            return
        }
        val consent = VpnService.prepare(this)
        if (consent != null) {
            @Suppress("DEPRECATION")
            startActivityForResult(consent, REQ_VPN_CONSENT)
            emit("vpn_consent_required")
        } else {
            startVpnService()
        }
    }

    fun startVpnService() {
        val intent = Intent(this, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_START)
        runCatching { ContextCompat.startForegroundService(this, intent) }
            .onFailure {
                // 例如 Android 12+ 的 ForegroundServiceStartNotAllowedException
                android.util.Log.e("DNSSwitch", "startForegroundService 失败", it)
                DnsStats.onVpnError("无法启动前台服务：" + (it.message ?: it.javaClass.simpleName))
            }
    }

    @Deprecated("使用 Activity Result API 需要额外依赖，minSdk 24 下传统方式完全可用")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN_CONSENT) {
            if (resultCode == RESULT_OK) {
                // 授权通过后只负责拉起服务；是否真的启动成功由状态轮询反馈（失败会显示原因）
                startVpnService()
            } else {
                emit("vpn_consent_denied")
            }
            return
        }
        if (requestCode == REQ_FILE_CHOOSER) {
            val cb = fileCallback
            fileCallback = null
            val uri = if (resultCode == RESULT_OK) data?.data else null
            cb?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
            if (uri != null) {
                val text = readTextFile(uri)
                if (text.isNullOrBlank()) {
                    emit("preset_file_empty")
                } else {
                    deliverPresetText(text)
                }
            }
        }
    }

    /** 读取选择的文件：UTF-8 优先，出现替换字符时回退 GBK（Windows 导出的中文 txt 常见） */
    private fun readTextFile(uri: Uri): String? {
        val bytes = runCatching {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return null
        var off = 0
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) off = 3
        val utf8 = String(bytes, off, bytes.size - off, Charsets.UTF_8)
        return if (utf8.contains('\uFFFD')) {
            runCatching { String(bytes, off, bytes.size - off, java.nio.charset.Charset.forName("GBK")) }
                .getOrDefault(utf8)
        } else utf8
    }

    private fun deliverPresetText(text: String) {
        webView.post {
            runCatching {
                webView.evaluateJavascript(
                    "window.onPresetFileLoaded&&window.onPresetFileLoaded(${JSONObject.quote(text)})",
                    null
                )
            }
        }
    }


    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATION && pendingNotificationStart) {
            pendingNotificationStart = false
            proceedStartVpn()
        }
    }

    override fun onResume() {
        super.onResume()
        paused = false
        emitUiMode()
    }

    override fun onPause() {
        paused = true
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        emitUiMode(newConfig)
    }

    private fun emitUiMode(config: Configuration? = null) {
        val c = config ?: resources.configuration
        val night = (c.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        emit(if (night) "ui_mode_dark" else "ui_mode_light")
    }

    private fun emit(event: String) {
        webView.post {
            runCatching {
                webView.evaluateJavascript(
                    "window.onNativeEvent&&window.onNativeEvent('$event')",
                    null
                )
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(pushTask)
        super.onDestroy()
    }

    companion object {
        private const val PUSH_INTERVAL_MS = 1200L
        private const val REQ_VPN_CONSENT = 1001
        private const val REQ_NOTIFICATION = 1002
        private const val REQ_FILE_CHOOSER = 1003
    }
}
