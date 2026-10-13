package com.dnsswitch.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** PacketParser：IPv4/UDP/TCP 解析边界 */
class PacketParserTest {

    private fun ipv4(proto: Int, payload: ByteArray, version: Int = 4, frag: Int = 0, ihlNibble: Int = 5): ByteArray {
        val ip = ByteArray(20 + payload.size)
        ip[0] = ((version shl 4) or ihlNibble).toByte()
        ip[2] = (ip.size shr 8).toByte(); ip[3] = ip.size.toByte()
        ip[4] = 0x12.toByte(); ip[5] = 0x34.toByte()
        ip[6] = (frag shr 8).toByte(); ip[7] = frag.toByte()
        ip[8] = 64; ip[9] = proto.toByte()
        byteArrayOf(10, 0, 0, 5).copyInto(ip, 12)
        byteArrayOf(10, 111, 222.toByte(), 2).copyInto(ip, 16)
        payload.copyInto(ip, 20)
        return ip
    }

    private fun udp(srcPort: Int, dstPort: Int, data: ByteArray): ByteArray {
        val u = ByteArray(8 + data.size)
        u[0] = (srcPort shr 8).toByte(); u[1] = srcPort.toByte()
        u[2] = (dstPort shr 8).toByte(); u[3] = dstPort.toByte()
        u[4] = (u.size shr 8).toByte(); u[5] = u.size.toByte()
        data.copyInto(u, 8)
        return u
    }

    @Test
    fun udp53_parsed() {
        val dns = byteArrayOf(0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 7, 'e'.code.toByte(), 'x'.code.toByte())
        val pkt = PacketParser.parseIpv4(ipv4(PacketParser.PROTO_UDP, udp(12345, 53, dns)), 20 + 8 + dns.size)
        require(pkt != null)
        assertEquals(PacketParser.PROTO_UDP, pkt!!.protocol)
        assertEquals(12345, pkt.srcPort)
        assertEquals(53, pkt.dstPort)
        assertArrayEquals(dns, pkt.dnsPayload)
        assertArrayEquals(byteArrayOf(10, 0, 0, 5), pkt.srcIp)
        assertArrayEquals(byteArrayOf(10, 111, 222.toByte(), 2), pkt.dstIp)
    }

    @Test
    fun udpNon53_stillParsed_portFilterIsServiceLayer() {
        val data = byteArrayOf(1, 2, 3, 4)
        val pkt = PacketParser.parseIpv4(ipv4(PacketParser.PROTO_UDP, udp(12345, 5353, data)), 20 + 8 + data.size)
        require(pkt != null)
        assertEquals(5353, pkt!!.dstPort)
    }

    @Test
    fun tcp_parsedSeq() {
        val tcp = ByteArray(20)
        tcp[0] = (12345 shr 8).toByte(); tcp[1] = 12345.toByte()
        tcp[2] = 0; tcp[3] = 53
        // seq = 0x00000064
        tcp[7] = 0x64
        tcp[12] = 0x50
        val pkt = PacketParser.parseIpv4(ipv4(PacketParser.PROTO_TCP, tcp), 40)
        require(pkt != null)
        assertEquals(PacketParser.PROTO_TCP, pkt!!.protocol)
        assertEquals(100L, pkt.tcpSeq)
        assertEquals(0, pkt.dnsPayload.size)
    }

    @Test
    fun ipv6_rejected() {
        val dns = byteArrayOf(1)
        val buf = ipv4(PacketParser.PROTO_UDP, udp(12345, 53, dns), version = 6)
        assertNull(PacketParser.parseIpv4(buf, buf.size))
    }

    @Test
    fun fragmented_rejected() {
        val dns = byteArrayOf(1)
        val buf = ipv4(PacketParser.PROTO_UDP, udp(12345, 53, dns), frag = 0x2001) // MF + 偏移 1
        assertNull(PacketParser.parseIpv4(buf, buf.size))
    }

    @Test
    fun truncated_rejected() {
        assertNull(PacketParser.parseIpv4(ByteArray(10), 10))
    }

    @Test
    fun nonUdpTcp_rejected() {
        val icmp = ByteArray(8)
        val buf = ipv4(1, icmp)
        assertNull(PacketParser.parseIpv4(buf, buf.size))
    }
}
