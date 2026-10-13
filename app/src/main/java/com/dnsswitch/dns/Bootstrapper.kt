package com.dnsswitch.dns

import com.dnsswitch.config.ConfigManager
import com.dnsswitch.config.Validators
import okhttp3.Dns
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

/**
 * 引导解析器（bootstrap resolver）。
 *
 * 为什么需要它：VPN 启动后系统 DNS 已被 DNSSwitch 自身接管，如果 DoH / DoT 上游
 * 填的是**主机名**（如 dns.alidns.com），再去用系统解析器解析它就会形成
 * 「解析服务器域名 → 查询进入 TUN → 又要解析服务器域名」的死循环。
 *
 * 因此这里绕过系统解析器，直接用 protected socket 向传统 DNS 服务器
 * （用户配置的 IPv4/IPv6 地址，缺省回落公共 DNS）发一条原生 A / AAAA 查询。
 * 引导服务器本身是 IP 字面量，不需要再解析，环路由此断开。
 */
class Bootstrapper(
    private val socketProtector: SocketProtector = NO_OP_SOCKET_PROTECTOR,
    private val datagramProtector: DatagramProtector = NO_OP_DATAGRAM_PROTECTOR
) {

    private data class Entry(val addrs: List<String>, val expireAt: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    /** 解析 host 的 A（wantV6=false）或 AAAA（wantV6=true）记录，失败返回空列表 */
    fun lookup(host: String, wantV6: Boolean = false): List<String> {
        val key = host.lowercase() + if (wantV6) "#6" else "#4"
        val now = System.currentTimeMillis()
        cache[key]?.let { if (it.expireAt > now) return it.addrs }

        val type = if (wantV6) DnsMessage.TYPE_AAAA else DnsMessage.TYPE_A
        val query = DnsMessage.buildQuery(host, type)
        val port = bootstrapPort()
        for (srv in bootstrapServers()) {
            val resp = queryUdp(srv, port, query) ?: continue
            if ((resp[3].toInt() and 0x0F) != 0) continue // 非 NOERROR
            val list = DnsMessage.rdataOfType(resp, type).mapNotNull { rd ->
                if (wantV6) {
                    if (rd.size == 16) DnsMessage.formatIpv6(rd) else null
                } else {
                    if (rd.size == 4) DnsMessage.formatIpv4(rd) else null
                }
            }
            if (list.isNotEmpty()) {
                cache[key] = Entry(list, now + TTL_MS)
                return list
            }
        }
        return emptyList()
    }

    /** 供 OkHttp / SSLSocket 使用：优先 IPv4，无结果时退回 IPv6 */
    fun lookupAddresses(host: String): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        val v4 = lookup(host, false)
        val v6 = if (v4.isEmpty()) lookup(host, true) else emptyList()
        (v4 + v6).forEach { runCatching { out += InetAddress.getByName(it) } }
        return out
    }

    private fun bootstrapServers(): List<String> {
        val cfg = ConfigManager.config.traditional
        val out = LinkedHashSet<String>()
        if (cfg.hasV4 && Validators.isIpv4(cfg.server)) out += cfg.server
        if (cfg.hasV6 && Validators.isIpv6(cfg.serverV6)) out += cfg.serverV6
        out += FALLBACK_V4
        out += FALLBACK_V4_SECOND
        return out.toList()
    }

    private fun bootstrapPort(): Int =
        ConfigManager.config.traditional.port.takeIf { Validators.isPort(it) } ?: 53

    private fun queryUdp(server: String, port: Int, query: ByteArray): ByteArray? {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket()
            datagramProtector.protect(socket)
            socket.soTimeout = TIMEOUT_MS
            socket.send(DatagramPacket(query, query.size, InetAddress.getByName(server), port))
            val buf = ByteArray(2048)
            val resp = DatagramPacket(buf, buf.size)
            socket.receive(resp)
            if (resp.length < 12) null else buf.copyOf(resp.length)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { socket?.close() }
        }
    }

    companion object {
        private const val TTL_MS = 60_000L
        private const val TIMEOUT_MS = 1500
        private const val FALLBACK_V4 = "223.5.5.5"
        private const val FALLBACK_V4_SECOND = "119.29.29.29"
    }
}

/** 让 OkHttp 走 bootstrap 解析，避免 VPN 接管系统 DNS 后无法解析 DoH 主机名 */
class BootstrapDns(private val bootstrapper: Bootstrapper) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val r = bootstrapper.lookupAddresses(hostname)
        if (r.isNotEmpty()) return r
        throw UnknownHostException("bootstrap 解析失败：$hostname")
    }
}
