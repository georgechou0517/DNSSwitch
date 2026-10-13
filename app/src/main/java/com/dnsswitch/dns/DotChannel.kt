package com.dnsswitch.dns

import com.dnsswitch.config.DotConfig
import com.dnsswitch.config.Validators
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SNIServerName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * DoT 通道（RFC 7858）：TLS 之上跑长度前缀 framing 的 DNS over TCP。
 *
 * 说明：规格中提到"基于 OkHttp + TLS"，但 OkHttp 只能承载 HTTP 语义，
 * 无法直接发送裸 DNS-over-TCP 字节流，因此这里直接用 SSLSocket 实现。
 *
 * 服务器可填**主机名**（如 dns.alidns.com）或 IP：
 * - 填主机名时经 [Bootstrapper] 解析（VPN 已接管系统 DNS，不能走系统解析器），
 *   并设置 SNI + 证书主机名校验；
 * - 填 IP 时不设 SNI，也跳过主机名校验（证书通常不含 IP SAN）。
 * 另可填 IPv6 备用地址，主地址失败时自动改用。
 */
class DotChannel(
    private val cfg: DotConfig,
    private val socketProtector: SocketProtector,
    private val bootstrapper: Bootstrapper? = null
) : DnsChannel {

    override val type = ChannelType.DOT
    override val defaultTimeoutMs = 2500L

    override fun query(query: ByteArray, timeoutMs: Long): ByteArray? {
        for ((addr, peer) in targets()) {
            val r = runCatching { queryOnce(addr, peer, query, timeoutMs) }.getOrNull()
            if (r != null && r.size >= 12) return r
        }
        return null
    }

    /** 返回 (连接地址, 证书校验用的对端名) 列表，按优先级排列 */
    private fun targets(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>(3)
        val s = cfg.server.trim()
        if (s.isEmpty()) return out
        val isName = !Validators.isIpAddress(s)
        if (!isName) {
            out += s to s
        } else {
            resolve(s).forEach { out += it to s }
        }
        if (cfg.hasV6) out += cfg.serverV6 to (if (isName) s else cfg.serverV6)
        return out
    }

    private fun resolve(host: String): List<String> {
        bootstrapper?.let { b ->
            val v4 = b.lookup(host, false)
            if (v4.isNotEmpty()) return v4
            val v6 = b.lookup(host, true)
            if (v6.isNotEmpty()) return v6
        }
        return runCatching { InetAddress.getAllByName(host) }
            .getOrDefault(emptyArray())
            .mapNotNull { it.hostAddress }
    }

    private fun queryOnce(
        addr: String,
        peer: String,
        query: ByteArray,
        timeoutMs: Long
    ): ByteArray? {
        var socket: Socket? = null
        return try {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val plain = Socket()
            socket = plain
            socketProtector.protect(plain)
            val t = timeoutMs.toInt().coerceIn(500, 10000)
            plain.connect(InetSocketAddress(InetAddress.getByName(addr), cfg.port), t)
            plain.soTimeout = t
            val ssl = factory.createSocket(plain, peer, cfg.port, true) as SSLSocket
            ssl.soTimeout = t
            val isName = !Validators.isIpAddress(peer)
            if (isName) setSni(ssl, peer)
            ssl.startHandshake()
            if (isName && !verifyHostname(peer, ssl)) return null
            val out = ssl.getOutputStream()
            out.write(byteArrayOf((query.size shr 8).toByte(), query.size.toByte()))
            out.write(query)
            out.flush()
            val inp = ssl.getInputStream()
            val lenBuf = TraditionalDnsChannel.readFully(inp, 2) ?: return null
            val rlen = ((lenBuf[0].toInt() and 0xFF) shl 8) or (lenBuf[1].toInt() and 0xFF)
            if (rlen < 12 || rlen > 65535) return null
            TraditionalDnsChannel.readFully(inp, rlen)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { socket?.close() }
        }
    }

    private fun setSni(ssl: SSLSocket, host: String) {
        runCatching {
            val p = ssl.sslParameters
            p.serverNames = mutableListOf<SNIServerName>(SNIHostName(host))
            ssl.sslParameters = p
        }
    }

    private fun verifyHostname(host: String, ssl: SSLSocket): Boolean =
        runCatching { HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session) }
            .getOrDefault(false)
}
