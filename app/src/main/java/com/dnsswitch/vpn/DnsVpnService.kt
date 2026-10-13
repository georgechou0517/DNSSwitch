package com.dnsswitch.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.dnsswitch.MainActivity
import com.dnsswitch.R
import com.dnsswitch.config.ConfigManager
import com.dnsswitch.config.PresetManager
import com.dnsswitch.dns.BootstrapDns
import com.dnsswitch.dns.Bootstrapper
import com.dnsswitch.dns.ChannelType
import com.dnsswitch.dns.DatagramProtector
import com.dnsswitch.dns.DnsMessage
import com.dnsswitch.dns.DnsResolver
import com.dnsswitch.dns.DnsStats
import com.dnsswitch.dns.SocketProtector
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * DNS 接管服务。
 *
 * 工作原理：建立虚拟网卡（TUN），把自己声明为系统 DNS 服务器（10.111.222.2），
 * 所有 App 的 DNS 查询都会进入 TUN；解析后把响应包写回 TUN。
 * 上游连接通过 protect() 绕过自身 TUN，避免环路。
 */
class DnsVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.dnsswitch.action.START"
        const val ACTION_STOP = "com.dnsswitch.action.STOP"

        /**
         * 虚拟网卡地址 / 虚拟 DNS 服务器地址（仅本机内部使用）。
         *
         * 注意：DNS 服务器地址必须落在接口网段内——接口用 10.111.222.1/24、路由 10.111.222.0/24。
         * 早期版本用「/32 接口地址 + 指向 10.111.222.2 的 /32 路由」，路由目标不在接口网段内，
         * 部分 ROM 会拒绝该路由，导致 establish() 失败、服务静默退出（表现为点了启动没反应）。
         */
        const val TUN_ADDRESS = "10.111.222.1"
        const val FAKE_DNS = "10.111.222.2"
        const val DNS_SUBNET = "10.111.222.0"
        const val TUN_PREFIX = 24
        const val MTU = 1500

        private const val TAG = "DNSSwitch"

        /** START_STICKY 被系统重启后最多自动恢复次数（避免配置非法时无限重试） */
        private const val MAX_AUTO_RESTARTS = 3

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "dnsswitch_status"
        private const val DNS_PORT = 53

        @Volatile
        var isRunning = false
            private set

        /** 推送给 WebView 的状态 JSON */
        fun statusJson(): String {
            val cfg = ConfigManager.config
            val o = JSONObject()
            o.put("running", isRunning)
            o.put(
                "state",
                if (isRunning) "running" else if (DnsStats.lastError != null) "error" else "stopped"
            )
            o.put("lastError", DnsStats.lastError ?: "")
            o.put("strategy", cfg.strategy.id)
            o.put("strategyLabel", cfg.strategy.label)
            o.put("totalQueries", DnsStats.totalQueries())
            val started = DnsStats.vpnStartedAt()
            o.put("vpnStartedAt", started)
            o.put("uptimeSec", if (started > 0) (System.currentTimeMillis() - started) / 1000 else 0)
            val channels = JSONArray()
            for (t in ChannelType.entries) {
                val s = DnsStats.statOf(t)
                val c = JSONObject()
                c.put("type", t.id)
                c.put(
                    "enabled", when (t) {
                        ChannelType.TRADITIONAL -> cfg.traditional.enabled
                        ChannelType.DOH -> cfg.doh.enabled
                        ChannelType.DOT -> cfg.dot.enabled
                    }
                )
                c.put("queries", s.queries)
                c.put("failures", s.failures)
                c.put("avgMs", s.avgMs)
                channels.put(c)
            }
            o.put("channels", channels)
            val servers = JSONArray()
            if (cfg.traditional.enabled) {
                val proto = if (cfg.traditional.useTcp) " (TCP)" else " (UDP)"
                if (cfg.traditional.hasV4) {
                    servers.put(cfg.traditional.server + ":" + cfg.traditional.port + proto)
                }
                if (cfg.traditional.hasV6) {
                    servers.put("[" + cfg.traditional.serverV6 + "]:" + cfg.traditional.port + proto)
                }
            }
            if (cfg.doh.enabled) servers.put(cfg.doh.url)
            if (cfg.dot.enabled) servers.put(cfg.dot.server + ":" + cfg.dot.port + " (DoT)")
            o.put("activeServers", servers)
            return o.toString()
        }

        fun testChannel(type: ChannelType): String = DnsResolver.testChannel(type)
    }

    private var tunInterface: ParcelFileDescriptor? = null
    private var outStream: FileOutputStream? = null
    private var autoRestarts = 0
    private val writeLock = Any()
    private val executor = Executors.newFixedThreadPool(16)
    private lateinit var resolver: DnsResolver

    /** 主机名型上游的引导解析器（VPN 运行期间生效） */
    private var bootstrapper: Bootstrapper? = null

    private val fakeDnsBytes: ByteArray by lazy {
        val p = FAKE_DNS.split(".")
        byteArrayOf(p[0].toInt().toByte(), p[1].toInt().toByte(), p[2].toInt().toByte(), p[3].toInt().toByte())
    }

    /** 让 OkHttp 创建的 socket 绕过自身 TUN，防止上游流量环路 */
    private class ProtectedSocketFactory(
        private val delegate: SocketFactory,
        private val service: VpnService
    ) : SocketFactory() {
        override fun createSocket(): Socket = delegate.createSocket().also { service.protect(it) }
        override fun createSocket(host: String, port: Int): Socket =
            delegate.createSocket(host, port).also { service.protect(it) }

        override fun createSocket(address: java.net.InetAddress, port: Int): Socket =
            delegate.createSocket(address, port).also { service.protect(it) }

        override fun createSocket(
            address: java.net.InetAddress,
            port: Int,
            localAddress: java.net.InetAddress?,
            localPort: Int
        ): Socket = delegate.createSocket(address, port, localAddress, localPort)
            .also { service.protect(it) }

        override fun createSocket(
            host: String,
            port: Int,
            localAddress: java.net.InetAddress?,
            localPort: Int
        ): Socket = delegate.createSocket(host, port, localAddress, localPort)
            .also { service.protect(it) }
    }

    override fun onCreate() {
        super.onCreate()
        ConfigManager.init(applicationContext)
        PresetManager.init(applicationContext)
        // 主机名型 DoH / DoT 上游的引导解析器：绕过已被自身接管的系统 DNS
        bootstrapper = Bootstrapper(
            socketProtector = SocketProtector { s -> runCatching { protect(s) }.getOrDefault(false) },
            datagramProtector = DatagramProtector { s -> runCatching { protect(s) }.getOrDefault(false) }
        )
        val client = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .followRedirects(false)
            .retryOnConnectionFailure(true)
            .dns(BootstrapDns(bootstrapper!!))
            .socketFactory(ProtectedSocketFactory(SocketFactory.getDefault(), this))
            .build()
        resolver = DnsResolver(
            client = client,
            socketProtector = SocketProtector { s -> runCatching { protect(s) }.getOrDefault(false) },
            datagramProtector = DatagramProtector { s -> runCatching { protect(s) }.getOrDefault(false) },
            bootstrapper = bootstrapper
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: action=${intent?.action} running=$isRunning")
        when (intent?.action) {
            ACTION_START -> {
                autoRestarts = 0
                DnsStats.system("收到启动指令")
                startVpn()
            }
            ACTION_STOP -> stopVpn()
            else -> {
                // 系统按 START_STICKY 重启服务时 intent 为 null：仅在仍持有授权时自动恢复
                if (!isRunning) {
                    if (autoRestarts < MAX_AUTO_RESTARTS && VpnService.prepare(this) == null) {
                        autoRestarts++
                        DnsStats.system("服务被系统终止后自动恢复（第 $autoRestarts 次）")
                        startVpn()
                    } else {
                        stopSelf()
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (isRunning) return
        Log.i(TAG, "startVpn: 进入前台服务")
        // 先转前台：startForegroundService() 要求 5 秒内调用 startForeground()，
        // 若把 establish() 放在前面，一旦它耗时过长会被系统判定为前台服务启动异常。
        try {
            startForegroundInternal()
        } catch (e: Exception) {
            Log.e(TAG, "startForeground 失败", e)
            DnsStats.onVpnError("前台服务启动失败：" + (e.message ?: e.javaClass.simpleName))
            runCatching { stopSelf() }
            return
        }
        DnsStats.system("前台服务已就绪")
        var iface: ParcelFileDescriptor? = null
        var err: String? = null
        try {
            val builder = Builder()
                .setSession("DNSSwitch")
                .addAddress(TUN_ADDRESS, TUN_PREFIX)
                .addDnsServer(FAKE_DNS)
                .addRoute(DNS_SUBNET, TUN_PREFIX)
                .setMtu(MTU)
                // 官方文档：establish() 返回的 fd 默认是非阻塞的——空读会立即抛 EAGAIN，
                // 我们的读循环按阻塞语义设计，必须显式开启阻塞模式。
                // （曾因此在部分设备上表现为：接口建立成功 → 读循环秒退 → 服务无反应地停止）
                .setBlocking(true)
                .setConfigureIntent(
                    PendingIntent.getActivity(
                        this, 0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE
                    )
                )
            iface = builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "establish 异常", e)
            err = "建立 VPN 接口异常：" + (e.message ?: e.javaClass.simpleName)
        }
        Log.i(TAG, "establish 结果：${iface != null}（${iface ?: "null"}）")
        if (iface == null) {
            // 不要静默退出：把原因写进统计层，UI 会显示「启动失败：<原因>」
            DnsStats.onVpnError(
                err ?: "建立 VPN 接口失败：授权可能已被撤销，或设备上已有其他 VPN 正在运行"
            )
            stopVpn()
            return
        }
        tunInterface = iface
        outStream = FileOutputStream(iface.fileDescriptor)
        isRunning = true
        DnsStats.onVpnStart()
        DnsStats.system("VPN 接口已建立，开始接管 DNS（$TUN_ADDRESS/$TUN_PREFIX → $FAKE_DNS）")
        Thread({ packetLoop(iface) }, "dns-tun-read").start()
    }

    private fun startForegroundInternal() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.vpn_notification_title),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val stopIntent = PendingIntent.getService(
            this, 2,
            Intent(this, DnsVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 3,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(getString(R.string.vpn_notification_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_notification, getString(R.string.vpn_notification_stop), stopIntent)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ 必须显式指定前台服务类型（清单中声明为 specialUse）
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun packetLoop(iface: ParcelFileDescriptor) {
        val inStream = FileInputStream(iface.fileDescriptor)
        val buffer = ByteArray(32767)
        var failures = 0
        var firstFailReason: String? = null
        var giveUp = false

        /** 非瞬时异常的计数与上报：连续 20 次才判定为不可恢复并留痕退出 */
        fun failOnce(reason: String) {
            failures++
            if (firstFailReason == null) {
                firstFailReason = reason
                DnsStats.system("读隧道失败：$reason（自动重试中）")
            }
            if (failures >= 20) {
                DnsStats.onVpnError("读隧道连续失败（已重试 $failures 次）：$reason")
                giveUp = true
            }
        }

        try {
            while (isRunning && !giveUp) {
                val len = try {
                    inStream.read(buffer)
                } catch (e: Exception) {
                    if (!isRunning) break
                    val reason = e.message ?: e.javaClass.simpleName
                    Log.e(TAG, "读隧道异常：$reason")
                    // 个别 ROM 可能无视 setBlocking(true)：EAGAIN 表示“暂无数据”，退避重试而非退出
                    if (reason.contains("EAGAIN", ignoreCase = true) ||
                        reason.contains("Try again", ignoreCase = true)
                    ) {
                        Thread.sleep(50)
                    } else {
                        failOnce(reason)
                        Thread.sleep(200)
                    }
                    continue
                }
                if (len < 0) {
                    failOnce("read() 返回 $len")
                    Thread.sleep(200)
                    continue
                }
                if (len == 0) {
                    // TUN 正常不应返回 0，按瞬时抖动处理
                    Thread.sleep(50)
                    continue
                }
                failures = 0
                firstFailReason = null
                try {
                    dispatch(buffer, len)
                } catch (e: Exception) {
                    // 单个数据包异常不应中断隧道
                }
            }
        } finally {
            Log.e(TAG, "packetLoop 退出：${firstFailReason ?: "服务被停止"}")
            DnsStats.system("隧道读循环退出：${firstFailReason ?: "服务被停止"}")
            stopVpn()
        }
    }

    private fun dispatch(buffer: ByteArray, len: Int) {
        val parsed = PacketParser.parseIpv4(buffer, len) ?: return
        when (parsed.protocol) {
            PacketParser.PROTO_UDP -> {
                if (parsed.dstPort != DNS_PORT) return
                val query = parsed.dnsPayload
                if (query.size < 12) return
                val domain = DnsMessage.questionDomain(query)
                executor.execute {
                    val upstream = try {
                        resolver.resolve(query, domain)
                    } catch (e: Exception) {
                        null
                    }
                    val response = upstream?.let { DnsMessage.adoptResponse(it, query) }
                        ?: DnsMessage.buildServfail(query)
                    val packets = ResponseBuilder.buildUdpResponse(parsed, response, fakeDnsBytes, MTU)
                    for (p in packets) writePacket(p)
                }
            }
            PacketParser.PROTO_TCP -> {
                // v1 不支持 TCP DNS：回 RST 让 App 快速失败并回退 UDP
                if (parsed.dstPort != DNS_PORT) return
                writePacket(ResponseBuilder.buildTcpRst(parsed, fakeDnsBytes))
            }
        }
    }

    private fun writePacket(packet: ByteArray) {
        synchronized(writeLock) {
            try {
                outStream?.write(packet)
            } catch (e: Exception) {
                // 隧道写入失败意味着服务已不可用，把原因留给 UI，避免静默失效
                if (isRunning) {
                    Log.e(TAG, "writePacket 失败", e)
                    DnsStats.onVpnError("隧道写入失败：" + (e.message ?: e.javaClass.simpleName))
                }
                isRunning = false
            }
        }
    }

    private fun stopVpn() {
        isRunning = false
        try {
            tunInterface?.close()
        } catch (e: Exception) {
        }
        tunInterface = null
        outStream = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
        DnsStats.onVpnStop()
    }

    override fun onRevoke() {
        // 用户在系统设置中关闭 VPN，或其他 VPN 应用接管
        Log.w(TAG, "onRevoke：系统撤销了 VPN")
        DnsStats.onVpnError("系统撤销了 VPN：可能有其他 VPN 接管，或授权被系统/用户收回")
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        isRunning = false
        executor.shutdownNow()
        super.onDestroy()
    }
}
