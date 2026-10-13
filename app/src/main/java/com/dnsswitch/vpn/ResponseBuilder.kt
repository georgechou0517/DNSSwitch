package com.dnsswitch.vpn

import java.util.concurrent.atomic.AtomicInteger

/**
 * 构造写回 TUN 的响应数据包：
 * - UDP DNS 响应（源 = 虚拟 DNS 服务器 10.111.222.2），超过 MTU 时自动 IP 分片；
 * - TCP 53 的 RST（v1 不支持 TCP DNS，让 App 快速失败回退 UDP）。
 */
object ResponseBuilder {

    private val ipIdSeq = AtomicInteger((Math.random() * 65535).toInt())

    private fun nextIpId(): Int = ipIdSeq.updateAndGet { (it + 1) and 0xFFFF }

    fun buildUdpResponse(
        q: PacketParser.ParsedPacket,
        dns: ByteArray,
        fakeDns: ByteArray,
        mtu: Int
    ): List<ByteArray> {
        val udpLen = 8 + dns.size
        val out = ArrayList<ByteArray>()
        if (20 + udpLen <= mtu) {
            val payload = ByteArray(udpLen)
            writeUdpHeader(payload, 0, DNS_PORT, q.srcPort, udpLen, fakeDns, q.srcIp, dns)
            dns.copyInto(payload, 8)
            out.add(buildIp(q, fakeDns, PacketParser.PROTO_UDP, payload, 0, 0, nextIpId()))
            return out
        }
        // 大于 MTU：IP 分片（每个分片 ≤ MTU，偏移量为 8 字节倍数）
        val maxChunk = (mtu - 20) / 8 * 8
        val ipId = nextIpId()
        val udpHeader = ByteArray(8)
        writeUdpHeader(udpHeader, 0, DNS_PORT, q.srcPort, udpLen, fakeDns, q.srcIp, dns)
        var offset = 0
        var first = true
        while (offset < dns.size) {
            // 首分片要额外容纳 8 字节 UDP 头，数据少一个头的量，保证每个 IP 包 ≤ MTU
            val limit = if (first) maxChunk - 8 else maxChunk
            val end = minOf(offset + limit, dns.size)
            val chunkLen = end - offset
            val payload = ByteArray(if (first) 8 + chunkLen else chunkLen)
            if (first) {
                udpHeader.copyInto(payload, 0)
                dns.copyInto(payload, 8, offset, end)
            } else {
                dns.copyInto(payload, 0, offset, end)
            }
            val mf = end < dns.size
            out.add(
                buildIp(
                    q, fakeDns, PacketParser.PROTO_UDP, payload,
                    if (mf) MF_FLAG else 0, offset / 8, ipId
                )
            )
            first = false
            offset = end
        }
        return out
    }

    /** 对 TCP 53 连接回 RST+ACK（行为与"端口关闭"一致，App 会快速失败并回退 UDP） */
    fun buildTcpRst(q: PacketParser.ParsedPacket, fakeDns: ByteArray): ByteArray {
        val tcp = ByteArray(20)
        tcp[0] = (DNS_PORT shr 8).toByte()
        tcp[1] = DNS_PORT.toByte()
        tcp[2] = (q.srcPort shr 8).toByte()
        tcp[3] = q.srcPort.toByte()
        // seq = 0（与关闭端口回 RST 的行为一致）
        tcp[4] = 0; tcp[5] = 0; tcp[6] = 0; tcp[7] = 0
        val ack = (q.tcpSeq + 1) and 0xFFFFFFFFL
        tcp[8] = (ack shr 24).toByte()
        tcp[9] = (ack shr 16).toByte()
        tcp[10] = (ack shr 8).toByte()
        tcp[11] = ack.toByte()
        tcp[12] = 0x50.toByte() // data offset = 5（20 字节头）
        tcp[13] = 0x14.toByte() // ACK + RST
        val csum = pseudoChecksum(fakeDns, q.srcIp, PacketParser.PROTO_TCP, 20, tcp, 0, 20, ByteArray(0))
        tcp[16] = (csum shr 8).toByte()
        tcp[17] = csum.toByte()
        return buildIp(q, fakeDns, PacketParser.PROTO_TCP, tcp, 0, 0, nextIpId())
    }

    private const val DNS_PORT = 53
    private const val MF_FLAG = 0x2000

    private fun writeUdpHeader(
        buf: ByteArray, off: Int, srcPort: Int, dstPort: Int, udpLen: Int,
        srcIp: ByteArray, dstIp: ByteArray, fullData: ByteArray
    ) {
        buf[off] = (srcPort shr 8).toByte()
        buf[off + 1] = srcPort.toByte()
        buf[off + 2] = (dstPort shr 8).toByte()
        buf[off + 3] = dstPort.toByte()
        buf[off + 4] = (udpLen shr 8).toByte()
        buf[off + 5] = udpLen.toByte()
        buf[off + 6] = 0
        buf[off + 7] = 0
        val csum = pseudoChecksum(srcIp, dstIp, PacketParser.PROTO_UDP, udpLen, buf, off, 8, fullData)
        buf[off + 6] = (csum shr 8).toByte()
        buf[off + 7] = csum.toByte()
    }

    /** 伪头部 + 报文段 + 完整数据的校验和（UDP/TCP 通用） */
    private fun pseudoChecksum(
        srcIp: ByteArray, dstIp: ByteArray, proto: Int, segLen: Int,
        segment: ByteArray, segOff: Int, segLenBytes: Int, data: ByteArray
    ): Int {
        val buf = ByteArray(12 + segLenBytes + data.size)
        srcIp.copyInto(buf, 0)
        dstIp.copyInto(buf, 4)
        buf[9] = proto.toByte()
        buf[10] = (segLen shr 8).toByte()
        buf[11] = segLen.toByte()
        segment.copyInto(buf, 12, segOff, segOff + segLenBytes)
        data.copyInto(buf, 12 + segLenBytes)
        var sum = Checksum.compute(buf, 0, buf.size)
        if (sum == 0) sum = 0xFFFF // 0 表示"无校验和"，避免歧义
        return sum
    }

    private fun buildIp(
        q: PacketParser.ParsedPacket, srcIp: ByteArray, proto: Int, payload: ByteArray,
        fragFlags: Int, fragOffset: Int, ipId: Int
    ): ByteArray {
        val total = 20 + payload.size
        val pkt = ByteArray(total)
        pkt[0] = 0x45.toByte() // version=4, IHL=5
        pkt[1] = 0
        pkt[2] = (total shr 8).toByte()
        pkt[3] = total.toByte()
        pkt[4] = (ipId shr 8).toByte()
        pkt[5] = ipId.toByte()
        val fragField = (fragFlags and 0xE000) or (fragOffset and 0x1FFF)
        pkt[6] = (fragField shr 8).toByte()
        pkt[7] = fragField.toByte()
        pkt[8] = 64 // TTL
        pkt[9] = proto.toByte()
        srcIp.copyInto(pkt, 12)
        q.srcIp.copyInto(pkt, 16)
        val csum = Checksum.compute(pkt, 0, 20)
        pkt[10] = (csum shr 8).toByte()
        pkt[11] = csum.toByte()
        payload.copyInto(pkt, 20)
        return pkt
    }
}
