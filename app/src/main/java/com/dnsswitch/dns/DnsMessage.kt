package com.dnsswitch.dns

import java.io.ByteArrayOutputStream
import java.util.Random

/**
 * 轻量 DNS 报文工具：解析查询域名、构造查询、嫁接上游响应。
 * 只处理本项目用到的字段，不依赖 dnsjava。
 */
object DnsMessage {

    const val TYPE_A = 1
    const val TYPE_AAAA = 28

    private val RND = Random()

    /** 从查询报文中解析 QNAME（如 "www.example.com"） */
    fun questionDomain(query: ByteArray): String {
        if (query.size < 13) return ""
        val sb = StringBuilder()
        var i = 12
        while (i < query.size) {
            val l = query[i].toInt() and 0xFF
            if (l == 0) break
            if (l > 63 || i + 1 + l > query.size) return sb.toString()
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(query, i + 1, l, Charsets.US_ASCII))
            i += 1 + l
        }
        return sb.toString()
    }

    fun qdCount(msg: ByteArray): Int =
        if (msg.size < 6) 0 else ((msg[4].toInt() and 0xFF) shl 8) or (msg[5].toInt() and 0xFF)

    fun anCount(msg: ByteArray): Int =
        if (msg.size < 8) 0 else ((msg[6].toInt() and 0xFF) shl 8) or (msg[7].toInt() and 0xFF)

    /** 问题区长度（QNAME + QTYPE + QCLASS，自偏移 12 起算）；无法解析返回 -1 */
    fun questionLength(msg: ByteArray): Int {
        if (msg.size < 13) return -1
        var i = 12
        while (i < msg.size) {
            val l = msg[i].toInt() and 0xFF
            if (l == 0) {
                i += 1
                break
            }
            if (l >= 0xC0) return -1 // 压缩指针：问题区不会出现，按异常处理
            if (l > 63 || i + 1 + l >= msg.size) return -1
            i += 1 + l
        }
        val end = i + 4
        return if (end in 13..msg.size) end - 12 else -1
    }

    /** 所有通道都失败时，构造 SERVFAIL 响应 */
    fun buildServfail(query: ByteArray): ByteArray {
        val qlen = questionLength(query).coerceAtLeast(0)
        val out = ByteArray(12 + qlen)
        out[0] = query.getOrElse(0) { 0 }
        out[1] = query.getOrElse(1) { 0 }
        out[2] = 0x81.toByte() // QR=1, RD=1, RA=1
        out[3] = 0x82.toByte() // RCODE=2 (Server failure)
        out[4] = query.getOrElse(4) { 0 }
        out[5] = query.getOrElse(5) { 0 }
        if (qlen > 0) query.copyInto(out, 12, 12, 12 + qlen)
        return out
    }

    /**
     * 把上游响应"嫁接"回被拦截的查询：
     * 1. 事务 ID 替换为原查询的 ID；
     * 2. 问题区替换为原查询的问题区（上游一般会回显，双保险）。
     * 应答/授权/附加段原样透传（含 EDNS OPT）。
     */
    fun adoptResponse(response: ByteArray, originalQuery: ByteArray): ByteArray {
        if (response.size < 12) return buildServfail(originalQuery)
        val qlenOrig = questionLength(originalQuery)
        if (qlenOrig < 0) return buildServfail(originalQuery)
        val out = ByteArrayOutputStream(12 + qlenOrig + response.size)
        out.write(originalQuery, 0, 2)
        out.write(response, 2, 10)
        if (qdCount(response) > 0) {
            val qlenResp = questionLength(response)
            if (qlenResp >= 0 && 12 + qlenResp <= response.size) {
                out.write(originalQuery, 12, qlenOrig)
                out.write(response, 12 + qlenResp, response.size - 12 - qlenResp)
                return out.toByteArray()
            }
        }
        // 响应不带问题区：仅替换 ID，其余透传
        out.write(response, 12, response.size - 12)
        return out.toByteArray()
    }

    /** 构造一条标准查询报文（QDCOUNT=1，RD=1），type 用 [TYPE_A] / [TYPE_AAAA] */
    fun buildQuery(name: String, type: Int): ByteArray {
        val bos = ByteArrayOutputStream()
        name.trim().trimEnd('.').split('.').filter { it.isNotEmpty() }.forEach { l ->
            bos.write(l.length)
            bos.write(l.toByteArray(Charsets.US_ASCII))
        }
        bos.write(0)
        val qname = bos.toByteArray()
        val q = ByteArray(12 + qname.size + 4)
        val id = RND.nextInt(0x10000)
        q[0] = (id shr 8).toByte()
        q[1] = (id and 0xFF).toByte()
        q[2] = 0x01 // RD
        q[3] = 0x00
        q[5] = 1 // QDCOUNT=1
        qname.copyInto(q, 12)
        val off = 12 + qname.size
        q[off] = (type shr 8).toByte()
        q[off + 1] = (type and 0xFF).toByte()
        q[off + 2] = 0
        q[off + 3] = 1 // IN
        return q
    }

    /** 提取响应中第一条 A 记录的 IPv4 地址（用于测试反馈），没有则返回 null */
    fun firstAnswerIpv4(response: ByteArray): String? =
        rdataOfType(response, TYPE_A).firstOrNull { it.size == 4 }?.let { formatIpv4(it) }

    /** 提取响应中指定 TYPE 的全部 RDATA（已处理名称压缩指针） */
    fun rdataOfType(response: ByteArray, type: Int): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        if (response.size < 12) return out
        if (qdCount(response) != 1) return out
        val qlen = questionLength(response)
        if (qlen < 0) return out
        var i = 12 + qlen
        repeat(anCount(response)) {
            if (i >= response.size) return out
            if ((response[i].toInt() and 0xC0) == 0xC0) {
                i += 2 // 名称压缩指针
            } else {
                while (i < response.size) {
                    val l = response[i].toInt() and 0xFF
                    if (l == 0) {
                        i += 1
                        break
                    }
                    if (l > 63) return out
                    i += 1 + l
                }
            }
            if (i + 10 > response.size) return out
            val t = ((response[i].toInt() and 0xFF) shl 8) or (response[i + 1].toInt() and 0xFF)
            val rdlen = ((response[i + 8].toInt() and 0xFF) shl 8) or (response[i + 9].toInt() and 0xFF)
            i += 10
            if (i + rdlen > response.size) return out
            if (t == type && rdlen > 0) out += response.copyOfRange(i, i + rdlen)
            i += rdlen
        }
        return out
    }

    fun formatIpv4(b: ByteArray): String = buildString {
        for (i in 0..3) {
            if (i > 0) append('.')
            append(b[i].toInt() and 0xFF)
        }
    }

    /** 带 :: 压缩的标准 IPv6 文本形式 */
    fun formatIpv6(b: ByteArray): String {
        if (b.size != 16) return ""
        val groups = IntArray(8) { ((b[it * 2].toInt() and 0xFF) shl 8) or (b[it * 2 + 1].toInt() and 0xFF) }
        var bestStart = -1
        var bestLen = 0
        var curStart = -1
        var curLen = 0
        for (i in 0..7) {
            if (groups[i] == 0) {
                if (curStart < 0) curStart = i
                curLen++
                if (curLen > bestLen) {
                    bestLen = curLen
                    bestStart = curStart
                }
            } else {
                curStart = -1
                curLen = 0
            }
        }
        return if (bestLen >= 2) {
            val left = (0 until bestStart).joinToString(":") { groups[it].toString(16) }
            val right = (bestStart + bestLen until 8).joinToString(":") { groups[it].toString(16) }
            "$left::$right"
        } else {
            groups.joinToString(":") { it.toString(16) }
        }
    }
}
