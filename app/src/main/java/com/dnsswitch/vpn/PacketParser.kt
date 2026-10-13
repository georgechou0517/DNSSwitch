package com.dnsswitch.vpn

/**
 * 从 TUN 网卡读到的 IPv4 数据包解析。
 * 只关心 DNS 流量（UDP/TCP 53 端口），其余直接丢弃。
 */
class PacketParser {

    class ParsedPacket(
        val protocol: Int,
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        /** UDP 场景下的 DNS 报文（已拷贝，可安全异步使用） */
        val dnsPayload: ByteArray,
        /** TCP 场景下的序列号（构造 RST 用） */
        val tcpSeq: Long
    )

    companion object {
        const val PROTO_TCP = 6
        const val PROTO_UDP = 17

        fun parseIpv4(buf: ByteArray, len: Int): ParsedPacket? {
            if (len < 20) return null
            if (((buf[0].toInt() shr 4) and 0xF) != 4) return null // 仅处理 IPv4
            val ihl = (buf[0].toInt() and 0x0F) * 4
            if (ihl < 20 || ihl > len) return null
            val totalLen = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
            if (totalLen < ihl || totalLen > len) return null
            val fragField = ((buf[6].toInt() and 0xFF) shl 8) or (buf[7].toInt() and 0xFF)
            if ((fragField and 0x3FFF) != 0) return null // 分片包丢弃（DNS 查询极小，不会分片）
            val proto = buf[9].toInt() and 0xFF
            val srcIp = buf.copyOfRange(12, 16)
            val dstIp = buf.copyOfRange(16, 20)
            return when (proto) {
                PROTO_UDP -> {
                    if (totalLen < ihl + 8) return null
                    val srcPort = portAt(buf, ihl)
                    val dstPort = portAt(buf, ihl + 2)
                    val udpLen = ((buf[ihl + 4].toInt() and 0xFF) shl 8) or (buf[ihl + 5].toInt() and 0xFF)
                    val payloadStart = ihl + 8
                    val payloadLen = minOf(udpLen - 8, totalLen - payloadStart)
                    if (payloadLen < 0) return null
                    ParsedPacket(
                        PROTO_UDP, srcIp, dstIp, srcPort, dstPort,
                        buf.copyOfRange(payloadStart, payloadStart + payloadLen), 0L
                    )
                }
                PROTO_TCP -> {
                    if (totalLen < ihl + 20) return null
                    val srcPort = portAt(buf, ihl)
                    val dstPort = portAt(buf, ihl + 2)
                    var seq = 0L
                    for (k in 0 until 4) {
                        seq = (seq shl 8) or (buf[ihl + 4 + k].toLong() and 0xFF)
                    }
                    ParsedPacket(
                        PROTO_TCP, srcIp, dstIp, srcPort, dstPort,
                        ByteArray(0), seq
                    )
                }
                else -> null
            }
        }

        private fun portAt(buf: ByteArray, off: Int): Int =
            ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)
    }
}
