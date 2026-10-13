package com.dnsswitch.dns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** DnsMessage：域名解析 / 响应嫁接 / SERVFAIL / A 记录提取 */
class DnsMessageTest {

    @Test
    fun questionDomain_emptyOnShortInput() {
        assertEquals("", DnsMessage.questionDomain(ByteArray(5)))
    }

    @Test
    fun questionLength_rejectsCompressionPointer() {
        // 12 字节头 + 0xC0 0x0C（压缩指针）不应被当作合法问题区
        val msg = ByteArray(14)
        msg[12] = 0xC0.toByte()
        msg[13] = 0x0C
        assertEquals(-1, DnsMessage.questionLength(msg))
    }

    @Test
    fun adoptResponse_replacesIdAndQuestion() {
        val query = testQuery()
        val resp = buildResponse(
            id = 0xABCD,
            question = testQuery().copyOfRange(12, 29),
            answer = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        )
        val out = DnsMessage.adoptResponse(resp, query)

        // ID 使用原查询的
        assertEquals(idOf(query), idOf(out))
        // flags / counts 来自响应
        assertEquals(0x81, out[2].toInt() and 0xFF)
        assertEquals(0x80, out[3].toInt() and 0xFF)
        // 问题区与原查询一致
        assertArrayEquals(query.copyOfRange(12, 29), out.copyOfRange(12, 29))
        // 应答段透传（完整 RR：压缩指针名 + TYPE/CLASS/TTL/RDLENGTH + RDATA）
        val rr = byteArrayOf(
            0xC0.toByte(), 0x0C, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4,
            0x01, 0x02, 0x03, 0x04
        )
        assertEquals(45, out.size)
        assertArrayEquals(rr, out.copyOfRange(29, 45))
    }

    @Test
    fun adoptResponse_withoutQuestionSection_keepsTail() {
        val query = testQuery()
        val resp = ByteArray(16)
        resp[0] = 0x77.toByte(); resp[1] = 0x88.toByte() // 响应 ID（应被替换）
        resp[2] = 0x81.toByte(); resp[3] = 0x80.toByte()
        // QDCOUNT=0，仅有附加段数据
        resp[10] = 0; resp[11] = 4 // ARCOUNT=4
        for (i in 0 until 4) resp[12 + i] = (0xA0 + i).toByte()

        val out = DnsMessage.adoptResponse(resp, query)
        assertEquals(idOf(query), idOf(out))
        assertArrayEquals(byteArrayOf(0xA0.toByte(), 0xA1.toByte(), 0xA2.toByte(), 0xA3.toByte()), out.copyOfRange(12, 16))
    }

    @Test
    fun adoptResponse_shortResponse_returnsServfail() {
        val query = testQuery()
        val out = DnsMessage.adoptResponse(ByteArray(6), query)
        // SERVFAIL：flags = 0x8182
        assertEquals(0x81, out[2].toInt() and 0xFF)
        assertEquals(0x82, out[3].toInt() and 0xFF)
        assertEquals("example.com", DnsMessage.questionDomain(out))
    }

    @Test
    fun buildServfail_keepsIdAndQuestion() {
        val query = testQuery()
        val out = DnsMessage.buildServfail(query)
        assertEquals(idOf(query), idOf(out))
        assertEquals(2, out[3].toInt() and 0x0F)
        assertEquals(29, out.size)
        assertArrayEquals(query.copyOfRange(12, 29), out.copyOfRange(12, 29))
    }

    @Test
    fun firstAnswerIpv4_extractsCompressedARecord() {
        val query = testQuery()
        val resp = buildResponse(
            id = 0x1234,
            question = query.copyOfRange(12, 29),
            answer = byteArrayOf(0x5D, 0xB8.toByte(), 0xD8.toByte(), 0x22) // 93.184.216.34
        )
        assertEquals("93.184.216.34", DnsMessage.firstAnswerIpv4(resp))
    }

    @Test
    fun firstAnswerIpv4_noAnswer_returnsNull() {
        val query = testQuery()
        val resp = buildResponse(id = 0x1234, question = query.copyOfRange(12, 29), answer = null)
        assertNull(DnsMessage.firstAnswerIpv4(resp))
    }

    @Test
    fun buildQuery_shapeAndQtype() {
        val a = DnsMessage.buildQuery("dns.alidns.com", DnsMessage.TYPE_A)
        assertEquals(1, DnsMessage.qdCount(a))
        assertEquals(0, DnsMessage.anCount(a))
        assertEquals("dns.alidns.com", DnsMessage.questionDomain(a))
        val qa = DnsMessage.questionLength(a)
        assertEquals(
            DnsMessage.TYPE_A,
            ((a[12 + qa - 4].toInt() and 0xFF) shl 8) or (a[13 + qa - 4].toInt() and 0xFF)
        )

        // 结尾的点号应被忽略
        val aaaa = DnsMessage.buildQuery("dns.alidns.com.", DnsMessage.TYPE_AAAA)
        assertEquals("dns.alidns.com", DnsMessage.questionDomain(aaaa))
        val q6 = DnsMessage.questionLength(aaaa)
        assertEquals(
            DnsMessage.TYPE_AAAA,
            ((aaaa[12 + q6 - 4].toInt() and 0xFF) shl 8) or (aaaa[13 + q6 - 4].toInt() and 0xFF)
        )
    }

    @Test
    fun firstAnswerIpv4_ignoresOtherTypes() {
        val resp = buildTypedResponse(
            "dns.alidns.com", DnsMessage.TYPE_AAAA,
            byteArrayOf(0x24, 0x00, 0x32, 0x00, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
        )
        assertNull(DnsMessage.firstAnswerIpv4(resp))
    }

    @Test
    fun formatIpv6_compressesZeroRuns() {
        assertEquals(
            "2400:3200::1",
            DnsMessage.formatIpv6(
                byteArrayOf(0x24, 0x00, 0x32, 0x00, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
            )
        )
        assertEquals(
            "::1",
            DnsMessage.formatIpv6(
                byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
            )
        )
        assertEquals(
            "2001:db8::1",
            DnsMessage.formatIpv6(
                byteArrayOf(0x20, 0x01, 0x0d, 0xb8.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
            )
        )
        // 只有单个 0 组时不压缩（:: 至少要代表两组）
        assertEquals(
            "2001:db8:0:1:1:1:1:1",
            DnsMessage.formatIpv6(
                byteArrayOf(
                    0x20, 0x01, 0x0d, 0xb8.toByte(), 0, 0, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1
                )
            )
        )
    }

    private fun testQuery(): ByteArray = DnsMessage.buildQuery("example.com", DnsMessage.TYPE_A)

    private fun idOf(m: ByteArray): Int = ((m[0].toInt() and 0xFF) shl 8) or (m[1].toInt() and 0xFF)

    /** 构造一个带问题区与单条 A 记录的响应；answer 为 null 时 ANCOUNT=0 */
    private fun buildResponse(id: Int, question: ByteArray, answer: ByteArray?): ByteArray {
        val head = ByteArray(12)
        head[0] = (id shr 8).toByte(); head[1] = id.toByte()
        head[2] = 0x81.toByte(); head[3] = 0x80.toByte()
        head[4] = 0; head[5] = 1 // QDCOUNT=1
        if (answer != null) { head[6] = 0; head[7] = 1 } // ANCOUNT=1
        val body = ByteArrayOutputStreamSink(question.size + 12 + (answer?.size ?: 0))
        // 名称压缩指针指向问题区起点（偏移 12 = 0xC00C）
        body.b(0xC0.toByte()); body.b(0x0C)
        body.b(0); body.b(DnsMessage.TYPE_A.toByte()) // TYPE=A
        body.b(0); body.b(1) // CLASS=IN
        body.b(0); body.b(0); body.b(0); body.b(60) // TTL=60
        body.b(0); body.b(4) // RDLENGTH=4
        answer?.let { body.raw(it) }
        return head + question + body.bytes()
    }

    /** 构造指定 TYPE 的单条应答响应（名称用压缩指针） */
    private fun buildTypedResponse(host: String, type: Int, rdata: ByteArray): ByteArray {
        val q = DnsMessage.buildQuery(host, type)
        val qlen = DnsMessage.questionLength(q)
        val question = q.copyOfRange(12, 12 + qlen)
        val head = ByteArray(12)
        head[2] = 0x81.toByte(); head[3] = 0x80.toByte()
        head[4] = 0; head[5] = 1 // QDCOUNT=1
        head[6] = 0; head[7] = 1 // ANCOUNT=1
        val body = ByteArrayOutputStreamSink(12 + rdata.size)
        body.b(0xC0.toByte()); body.b(0x0C) // 压缩指针 → 偏移 12
        body.b((type shr 8).toByte()); body.b((type and 0xFF).toByte())
        body.b(0); body.b(1) // CLASS=IN
        body.b(0); body.b(0); body.b(0); body.b(60) // TTL
        body.b((rdata.size shr 8).toByte()); body.b((rdata.size and 0xFF).toByte())
        body.raw(rdata)
        return head + question + body.bytes()
    }

    private class ByteArrayOutputStreamSink(cap: Int) {
        private val buf = ByteArray(cap + 64)
        private var n = 0
        fun b(v: Byte) { buf[n++] = v }
        fun raw(a: ByteArray) { a.copyInto(buf, n); n += a.size }
        fun bytes() = buf.copyOf(n)
    }
}
