package com.dnsswitch.vpn

import com.dnsswitch.dns.DnsMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ResponseBuilder：UDP 响应构造 / IP 分片 / TCP RST */
class ResponseBuilderTest {

    private val fakeDns = byteArrayOf(10, 111, 222.toByte(), 2)

    /** 构造一条发往 10.111.222.2:53 的 UDP DNS 查询包（源 10.0.0.5:12345） */
    private fun udpQueryPacket(dns: ByteArray): PacketParser.ParsedPacket {
        val udp = ByteArray(8 + dns.size)
        udp[0] = (12345 shr 8).toByte(); udp[1] = 12345.toByte()
        udp[2] = 0; udp[3] = 53
        udp[4] = (udp.size shr 8).toByte(); udp[5] = udp.size.toByte()
        dns.copyInto(udp, 8)
        val ip = ByteArray(20 + udp.size)
        ip[0] = 0x45
        ip[2] = (ip.size shr 8).toByte(); ip[3] = ip.size.toByte()
        ip[8] = 64; ip[9] = PacketParser.PROTO_UDP.toByte()
        byteArrayOf(10, 0, 0, 5).copyInto(ip, 12)
        fakeDns.copyInto(ip, 16)
        val csum = Checksum.compute(ip, 0, 20)
        ip[10] = (csum shr 8).toByte(); ip[11] = csum.toByte()
        udp.copyInto(ip, 20)

        val parsed = PacketParser.parseIpv4(ip, ip.size)
        return requireNotNull(parsed)
    }

    private fun requireNotNull(v: PacketParser.ParsedPacket?): PacketParser.ParsedPacket {
        assertTrue("测试构造的查询包应能被解析", v != null)
        return v!!
    }

    @Test
    fun udpResponse_singlePacket_fieldsAndChecksums() {
        val dns = DnsMessage.buildQuery("example.com", DnsMessage.TYPE_A)
        val q = udpQueryPacket(dns)
        val pkts = ResponseBuilder.buildUdpResponse(q, dns, fakeDns, 1500)

        assertEquals(1, pkts.size)
        val pkt = pkts[0]
        // IP 头
        assertEquals(0x45, pkt[0].toInt() and 0xFF)
        assertEquals(PacketParser.PROTO_UDP.toLong(), (pkt[9].toInt() and 0xFF).toLong())
        assertEquals(20 + 8 + dns.size, pkt.size)
        // 源 = 虚拟 DNS，目的 = 查询源
        assertArrayEquals(fakeDns, pkt.copyOfRange(12, 16))
        assertArrayEquals(q.srcIp, pkt.copyOfRange(16, 20))
        // IP 校验和自校验为 0
        assertEquals(0, Checksum.compute(pkt, 0, 20))
        // 分片字段为 0
        assertEquals(0, ((pkt[6].toInt() and 0xFF) shl 8) or (pkt[7].toInt() and 0xFF))
        // UDP 头：53 → 12345
        assertEquals(53, ((pkt[20].toInt() and 0xFF) shl 8) or (pkt[21].toInt() and 0xFF))
        assertEquals(12345, ((pkt[22].toInt() and 0xFF) shl 8) or (pkt[23].toInt() and 0xFF))
        // UDP 负载原样透传
        assertArrayEquals(dns, pkt.copyOfRange(28, pkt.size))

        // UDP 校验和：重建伪头部 + 校验和清零的报文段，重算应相等
        val udpLen = 8 + dns.size
        val seg = pkt.copyOfRange(20, 20 + udpLen)
        seg[6] = 0; seg[7] = 0
        val pseudo = ByteArray(12 + udpLen)
        fakeDns.copyInto(pseudo, 0)
        q.srcIp.copyInto(pseudo, 4)
        pseudo[9] = PacketParser.PROTO_UDP.toByte()
        pseudo[10] = (udpLen shr 8).toByte(); pseudo[11] = udpLen.toByte()
        seg.copyInto(pseudo, 12)
        val expect = Checksum.compute(pseudo, 0, pseudo.size)
        assertEquals(expect, ((pkt[26].toInt() and 0xFF) shl 8) or (pkt[27].toInt() and 0xFF))    }

    @Test
    fun udpResponse_fragments_allWithinMtuAndReassemblable() {
        val dns = ByteArray(1000) { (it % 251).toByte() }
        val q = udpQueryPacket(dns)
        val mtu = 1500
        val pkts = ResponseBuilder.buildUdpResponse(q, dns, fakeDns, mtu)

        assertTrue("1000 字节负载在 1500 MTU 下不应分片（20+8+1000=1028 ≤ 1500）", pkts.size == 1)

        // 用小 MTU 触发分片
        val smallMtu = 128
        val frags = ResponseBuilder.buildUdpResponse(q, dns, fakeDns, smallMtu)
        assertTrue("应产生多个分片", frags.size > 1)

        var expectOffset = 0
        frags.forEachIndexed { idx, pkt ->
            // 每个分片的 IP 总长 ≤ MTU
            val total = ((pkt[2].toInt() and 0xFF) shl 8) or (pkt[3].toInt() and 0xFF)
            assertTrue("分片 $idx 超 MTU: $total", total <= smallMtu)
            // IP 校验和自校验
            assertEquals("分片 $idx IP 校验和", 0, Checksum.compute(pkt, 0, 20))
            val fragField = ((pkt[6].toInt() and 0xFF) shl 8) or (pkt[7].toInt() and 0xFF)
            val mf = (fragField and 0x2000) != 0
            val off = fragField and 0x1FFF
            assertEquals("分片 $idx 偏移", expectOffset, off)
            if (idx < frags.size - 1) assertTrue("非末分片应置 MF", mf)
            else assertTrue("末分片应清 MF", !mf)
            // 非首分片数据长度必须为 8 的倍数
            val dataLen = total - 20 - if (idx == 0) 8 else 0
            if (idx < frags.size - 1) assertEquals("分片 $idx 数据长度", 0, dataLen % 8)
            expectOffset += dataLen / 8
        }

        // 重组验证：拼接各分片数据段 == 原始 DNS
        val reassembled = ArrayList<Byte>()
        frags.forEachIndexed { idx, pkt ->
            val total = ((pkt[2].toInt() and 0xFF) shl 8) or (pkt[3].toInt() and 0xFF)
            val dataLen = total - 20 - if (idx == 0) 8 else 0
            val from = if (idx == 0) 28 else 20
            for (i in 0 until dataLen) reassembled.add(pkt[from + i])
        }
        assertArrayEquals(dns, reassembled.toByteArray())
    }

    @Test
    fun tcpRst_flagsAndAck() {
        val tcpQuery = tcpQueryPacket(seq = 1000L)
        val pkt = ResponseBuilder.buildTcpRst(tcpQuery, fakeDns)

        assertEquals(PacketParser.PROTO_TCP.toLong(), (pkt[9].toInt() and 0xFF).toLong())
        assertEquals(0, Checksum.compute(pkt, 0, 20))
        val tcpOff = 20
        // 源端口 53，目的端口回源端口
        assertEquals(53, ((pkt[tcpOff].toInt() and 0xFF) shl 8) or (pkt[tcpOff + 1].toInt() and 0xFF))
        assertEquals(12345, ((pkt[tcpOff + 2].toInt() and 0xFF) shl 8) or (pkt[tcpOff + 3].toInt() and 0xFF))
        // seq = 0，ack = 原 seq + 1
        assertEquals(0L, pkt.copyOfRange(tcpOff + 4, tcpOff + 8).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) })
        val ack = pkt.copyOfRange(tcpOff + 8, tcpOff + 12).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
        assertEquals(1001L, ack)
        // RST + ACK
        assertEquals(0x14, pkt[tcpOff + 13].toInt() and 0xFF)
    }

    private fun tcpQueryPacket(seq: Long): PacketParser.ParsedPacket {
        val tcp = ByteArray(20)
        tcp[0] = (12345 shr 8).toByte(); tcp[1] = 12345.toByte()
        tcp[2] = 0; tcp[3] = 53
        for (k in 0 until 4) tcp[4 + k] = (seq shr (24 - 8 * k)).toByte()
        tcp[12] = 0x50.toByte() // data offset 5
        tcp[13] = 0x02.toByte() // SYN
        val ip = ByteArray(40)
        ip[0] = 0x45
        ip[2] = 0; ip[3] = 40
        ip[8] = 64; ip[9] = PacketParser.PROTO_TCP.toByte()
        byteArrayOf(10, 0, 0, 5).copyInto(ip, 12)
        fakeDns.copyInto(ip, 16)
        val csum = Checksum.compute(ip, 0, 20)
        ip[10] = (csum shr 8).toByte(); ip[11] = csum.toByte()
        tcp.copyInto(ip, 20)
        val parsed = PacketParser.parseIpv4(ip, ip.size)
        return requireNotNull(parsed)
    }
}
