package com.dnsswitch.dns

import com.dnsswitch.config.TraditionalConfig
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 传统 DNS 通道：UDP/TCP，服务器 IPv4 / IPv6 与端口完全自定义。
 *
 * 同时填了 IPv4 与 IPv6 时先走 IPv4，失败自动改用 IPv6（与「智能分流」的通道级
 * 故障转移是两层：这里解决同一通道内部的地址级回退）。
 */
class TraditionalDnsChannel(
    private val cfg: TraditionalConfig,
    private val socketProtector: SocketProtector,
    private val datagramProtector: DatagramProtector
) : DnsChannel {

    override val type = ChannelType.TRADITIONAL
    override val defaultTimeoutMs = if (cfg.useTcp) 2500L else 2000L

    override fun query(query: ByteArray, timeoutMs: Long): ByteArray? {
        val targets = ArrayList<String>(2)
        if (cfg.hasV4) targets += cfg.server
        if (cfg.hasV6) targets += cfg.serverV6
        if (targets.isEmpty()) return null
        for (t in targets) {
            val r = queryOne(t, query, timeoutMs)
            if (r == null || r.size < 12) continue
            // 响应被截断（TC 位）时改用同一地址的 TCP 重试
            if (!cfg.useTcp && isTruncated(r)) {
                val tcp = queryTcp(t, query, timeoutMs)
                if (tcp != null && tcp.size >= 12) return tcp
            }
            return r
        }
        return null
    }

    private fun queryOne(server: String, query: ByteArray, timeoutMs: Long): ByteArray? =
        if (cfg.useTcp) queryTcp(server, query, timeoutMs) else queryUdp(server, query, timeoutMs)

    private fun queryUdp(server: String, query: ByteArray, timeoutMs: Long): ByteArray? {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket()
            datagramProtector.protect(socket)
            socket.soTimeout = timeoutMs.toInt().coerceIn(500, 10000)
            val addr = InetAddress.getByName(server)
            socket.send(DatagramPacket(query, query.size, addr, cfg.port))
            val buf = ByteArray(4096)
            val resp = DatagramPacket(buf, buf.size)
            socket.receive(resp)
            if (resp.length < 12) return null
            buf.copyOf(resp.length)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { socket?.close() }
        }
    }

    private fun queryTcp(server: String, query: ByteArray, timeoutMs: Long): ByteArray? {
        var socket: Socket? = null
        return try {
            socket = Socket()
            socketProtector.protect(socket)
            val t = timeoutMs.toInt().coerceIn(500, 10000)
            socket.connect(InetSocketAddress(InetAddress.getByName(server), cfg.port), t)
            socket.soTimeout = t
            val out = socket.getOutputStream()
            out.write(byteArrayOf((query.size shr 8).toByte(), query.size.toByte()))
            out.write(query)
            out.flush()
            val inp = socket.getInputStream()
            val lenBuf = readFully(inp, 2) ?: return null
            val rlen = ((lenBuf[0].toInt() and 0xFF) shl 8) or (lenBuf[1].toInt() and 0xFF)
            if (rlen < 12 || rlen > 65535) return null
            readFully(inp, rlen)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { socket?.close() }
        }
    }

    private fun isTruncated(msg: ByteArray): Boolean = (msg[2].toInt() and 0x02) != 0

    companion object {
        fun readFully(input: InputStream, n: Int): ByteArray? {
            val buf = ByteArray(n)
            var read = 0
            while (read < n) {
                val r = input.read(buf, read, n - read)
                if (r < 0) return null
                read += r
            }
            return buf
        }
    }
}
