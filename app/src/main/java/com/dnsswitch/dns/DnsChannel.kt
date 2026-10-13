package com.dnsswitch.dns

import java.net.DatagramSocket
import java.net.Socket

/** DNS 通道类型 */
enum class ChannelType(val id: String, val label: String) {
    TRADITIONAL("traditional", "传统 DNS"),
    DOH("doh", "DoH"),
    DOT("dot", "DoT")
}

/**
 * VpnService.protect(Socket) 的注入点。
 * 上游连接必须绕过自身 TUN，否则发送给上游 DNS 服务器的流量会环路回虚拟网卡。
 */
fun interface SocketProtector {
    fun protect(socket: Socket): Boolean
}

fun interface DatagramProtector {
    fun protect(socket: DatagramSocket): Boolean
}

val NO_OP_SOCKET_PROTECTOR = SocketProtector { false }
val NO_OP_DATAGRAM_PROTECTOR = DatagramProtector { false }

/** 一条 DNS 解析通道（传统 DNS / DoH / DoT） */
interface DnsChannel {
    val type: ChannelType
    val defaultTimeoutMs: Long

    /** 执行一次 DNS 查询，返回原始 DNS 响应报文；失败返回 null */
    fun query(query: ByteArray, timeoutMs: Long = defaultTimeoutMs): ByteArray?
}
