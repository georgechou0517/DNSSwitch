package com.dnsswitch.dns

import com.dnsswitch.config.AppConfig
import com.dnsswitch.config.ConfigManager
import com.dnsswitch.config.Strategy
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * DNS 解析调度器：按用户配置的策略把查询分发给各通道。
 *
 * 策略：
 * - ALL_TRADITIONAL / ALL_DOH / ALL_DOT：全部走单条通道；
 * - SMART_FAILOVER（默认）：按优先级 DoH → DoT → 传统 DNS 依次尝试，失败自动切换；
 * - PARALLEL_RACE：查询同时发往所有已启用通道，取最快响应（延迟最低）。
 *
 * 关键点：多条通道可同时启用并同时参与工作，不是二选一。
 */
class DnsResolver(
    private val client: OkHttpClient,
    private val socketProtector: SocketProtector = NO_OP_SOCKET_PROTECTOR,
    private val datagramProtector: DatagramProtector = NO_OP_DATAGRAM_PROTECTOR,
    private val bootstrapper: Bootstrapper? = null,
    private val ioExecutor: ExecutorService = Executors.newCachedThreadPool()
) {

    fun resolve(query: ByteArray, domain: String): ByteArray? {
        val cfg = ConfigManager.config
        val channels = buildChannels(cfg)
        if (channels.isEmpty()) {
            DnsStats.recordServfail(domain, 0)
            return null
        }
        return if (cfg.strategy == Strategy.PARALLEL_RACE) {
            race(channels, query, domain)
        } else {
            failover(channels, query, domain)
        }
    }

    /** 通道优先级：DoH → DoT → 传统 DNS（对应规格"优先走 DoH，失败回退传统 DNS"） */
    private fun buildChannels(cfg: AppConfig): List<DnsChannel> {
        return when (cfg.strategy) {
            Strategy.ALL_TRADITIONAL -> listOfNotNull(channelOf(ChannelType.TRADITIONAL, cfg))
            Strategy.ALL_DOH -> listOfNotNull(channelOf(ChannelType.DOH, cfg))
            Strategy.ALL_DOT -> listOfNotNull(channelOf(ChannelType.DOT, cfg))
            else -> listOfNotNull(
                channelOf(ChannelType.DOH, cfg),
                channelOf(ChannelType.DOT, cfg),
                channelOf(ChannelType.TRADITIONAL, cfg)
            )
        }
    }

    private fun channelOf(type: ChannelType, cfg: AppConfig): DnsChannel? = when (type) {
        ChannelType.TRADITIONAL -> cfg.traditional.takeIf { it.enabled }
            ?.let { TraditionalDnsChannel(it, socketProtector, datagramProtector) }
        ChannelType.DOH -> cfg.doh.takeIf { it.enabled }?.let { DohChannel(it, client) }
        ChannelType.DOT -> cfg.dot.takeIf { it.enabled }
            ?.let { DotChannel(it, socketProtector, bootstrapper) }
    }

    private fun failover(channels: List<DnsChannel>, query: ByteArray, domain: String): ByteArray? {
        for (ch in channels) {
            val t0 = System.currentTimeMillis()
            val r = try {
                ch.query(query, ch.defaultTimeoutMs)
            } catch (e: Exception) {
                null
            }
            val ms = System.currentTimeMillis() - t0
            if (r != null && r.size >= 12) {
                DnsStats.recordQuery(ch.type, domain, ms, true)
                return r
            }
            DnsStats.recordQuery(ch.type, domain, ms, false)
        }
        DnsStats.recordServfail(domain, 0)
        return null
    }

    private fun race(channels: List<DnsChannel>, query: ByteArray, domain: String): ByteArray? {
        val result = AtomicReference<ByteArray?>(null)
        val latch = CountDownLatch(1)
        val t0 = System.currentTimeMillis()
        for (ch in channels) {
            ioExecutor.execute {
                val s = System.currentTimeMillis()
                val r = try {
                    ch.query(query, ch.defaultTimeoutMs)
                } catch (e: Exception) {
                    null
                }
                val ms = System.currentTimeMillis() - s
                val ok = r != null && r.size >= 12
                DnsStats.recordQuery(ch.type, domain, ms, ok)
                if (ok && result.compareAndSet(null, r)) latch.countDown()
            }
        }
        val overall = channels.maxOf { it.defaultTimeoutMs } + 500
        latch.await(overall, TimeUnit.MILLISECONDS)
        val r = result.get()
        if (r == null) DnsStats.recordServfail(domain, System.currentTimeMillis() - t0)
        return r
    }

    /** 单通道测试：example.com A 记录 */
    fun test(type: ChannelType): String {
        val cfg = ConfigManager.config
        val ch = channelOf(type, cfg)
            ?: return json(false, "通道未启用或配置无效", 0, null)
        val q = DnsMessage.buildQuery("example.com", DnsMessage.TYPE_A)
        val t0 = System.currentTimeMillis()
        val r = try {
            ch.query(q, ch.defaultTimeoutMs)
        } catch (e: Exception) {
            null
        }
        val ms = System.currentTimeMillis() - t0
        if (r == null || r.size < 12) return json(false, "查询失败或超时", ms, null)
        val rcode = r[3].toInt() and 0x0F
        if (rcode != 0) return json(true, "服务器返回 RCODE=$rcode", ms, null)
        return json(true, null, ms, DnsMessage.firstAnswerIpv4(r))
    }

    private fun json(ok: Boolean, error: String?, ms: Long, ip: String?): String {
        val o = JSONObject()
        o.put("ok", ok)
        o.put("ms", ms)
        if (error != null) o.put("error", error)
        if (ip != null) o.put("ip", ip)
        return o.toString()
    }

    companion object {
        /** 未启动 VPN 时的通道测试（无 TUN，无需 protect） */
        fun testChannel(type: ChannelType): String {
            val client = OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.SECONDS)
                .followRedirects(false)
                .build()
            return DnsResolver(client).test(type)
        }
    }
}
