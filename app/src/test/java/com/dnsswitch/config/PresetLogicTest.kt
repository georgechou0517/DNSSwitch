package com.dnsswitch.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** txt 预设导入：行解析、按「分类 + 名字」合并、非法行忽略 */
class PresetLogicTest {

    private val sample = """
        223.6.6.6 阿里云 DNS
        2400:3200::1 阿里云 DNS
        dns.alidns.com 阿里云 DoT
        https://dns.alidns.com/dns-query 阿里云 DoH
    """.trimIndent()

    @Test
    fun parse_ipv4Line() {
        val p = PresetLogic.parseLine("223.6.6.6 阿里云 DNS")
        requireNotNull(p)
        assertEquals(PresetType.DNS, p.type)
        assertEquals("阿里云", p.name)
        assertEquals("223.6.6.6", p.v4)
        assertEquals("", p.v6)
    }

    @Test
    fun parse_ipv6Line() {
        val p = PresetLogic.parseLine("2400:3200::1 阿里云 DNS")
        requireNotNull(p)
        assertEquals(PresetType.DNS, p.type)
        assertEquals("阿里云", p.name)
        assertEquals("", p.v4)
        assertEquals("2400:3200::1", p.v6)
    }

    @Test
    fun parse_categoryIsCaseInsensitive() {
        // 每种分类配一个合法地址：传统 DNS=IP、DoH=URL、DoT=主机名
        val cases = listOf(
            "223.5.5.5" to PresetType.DNS,
            "https://x.cn/dns-query" to PresetType.DOH,
            "dns.alidns.com" to PresetType.DOT
        )
        cases.forEach { (addr, expect) ->
            listOf(expect.id, expect.id.uppercase(), expect.id.replaceFirstChar { it.uppercase() })
                .forEach { cat ->
                    val p = PresetLogic.parseLine("$addr 测试 $cat")
                    assertNotNull("分类 $cat 应被识别", p)
                    assertEquals("分类 $cat 类型错误", expect, p!!.type)
                    assertEquals("测试", p.name)
                }
        }
    }

    @Test
    fun parse_wrongAddressForCategoryIsRejected() {
        // 分类明确但地址形态不符 → 该行无效（而不是猜一个类型）
        assertNull(PresetLogic.parseLine("223.5.5.5 测试 DOH"))
        assertNull(PresetLogic.parseLine("https://x.cn/dns-query 测试 DNS"))
    }

    @Test
    fun parse_hostnameGoesToDot() {
        val p = PresetLogic.parseLine("dns.alidns.com 阿里云 DoT")
        requireNotNull(p)
        assertEquals(PresetType.DOT, p.type)
        assertEquals("dns.alidns.com", p.host)
        assertEquals("阿里云", p.name)
    }

    @Test
    fun parse_urlGoesToDoh() {
        val p = PresetLogic.parseLine("https://dns.alidns.com/dns-query 阿里云 DoH")
        requireNotNull(p)
        assertEquals(PresetType.DOH, p.type)
        assertEquals("https://dns.alidns.com/dns-query", p.url)
    }

    @Test
    fun parse_multiWordName() {
        val p = PresetLogic.parseLine("1.1.1.1 Cloudflare 家庭版 DNS")
        requireNotNull(p)
        assertEquals("Cloudflare 家庭版", p.name)
        assertEquals("1.1.1.1", p.v4)
    }

    @Test
    fun parse_missingCategoryIsInferred() {
        // 无分类词：URL → DoH，IP → 传统 DNS，其他主机名 → DoT
        assertEquals(PresetType.DOH, PresetLogic.parseLine("https://dns.google/dns-query Google")!!.type)
        assertEquals(PresetType.DNS, PresetLogic.parseLine("8.8.8.8 Google")!!.type)
        assertEquals(PresetType.DOT, PresetLogic.parseLine("dns.google Google")!!.type)
        assertEquals("Google", PresetLogic.parseLine("8.8.8.8 Google")!!.name)
    }

    @Test
    fun parse_invalidLinesRejected() {
        assertNull(PresetLogic.parseLine(""))
        assertNull(PresetLogic.parseLine("# 注释"))
        assertNull(PresetLogic.parseLine("// 注释"))
        assertNull(PresetLogic.parseLine("999.999.999.999 坏地址 DNS"))
        assertNull(PresetLogic.parseLine("dns.alidns.com 名字 DNS")) // 主机名不能当传统 DNS
    }

    @Test
    fun import_mergesV4AndV6ByNameAndType() {
        val (list, report) = PresetLogic.importText(emptyList(), sample)
        assertEquals(3, list.size) // 阿里云 DNS / DoT / DoH
        assertEquals(4, report.added + report.merged)
        assertEquals(0, report.invalid.size)

        val dns = list.first { it.type == PresetType.DNS }
        assertEquals("223.6.6.6", dns.v4)
        assertEquals("2400:3200::1", dns.v6)
    }

    @Test
    fun import_secondRunMergesIntoExisting() {
        val (first) = PresetLogic.importText(emptyList(), sample)
        val (second, report) = PresetLogic.importText(first, sample)
        assertEquals(first.size, second.size) // 重复导入不产生新条目
        assertEquals(0, report.added)
        assertEquals(4, report.merged)
    }

    @Test
    fun import_differentTypeSameNameKeptSeparate() {
        val (list) = PresetLogic.importText(emptyList(), sample)
        assertEquals(3, list.count { it.name == "阿里云" })
    }

    @Test
    fun import_countsInvalidLines() {
        val (list, report) = PresetLogic.importText(
            emptyList(),
            "223.5.5.5 好的 DNS\n这不是地址 坏的 DNS\n\n# 注释行\n119.29.29.29 腾讯 DNS"
        )
        assertEquals(2, list.size)
        assertEquals(1, report.invalid.size)
        assertEquals("这不是地址 坏的 DNS", report.invalid[0])
        assertTrue(report.message().contains("忽略 1 条"))
    }

    @Test
    fun preset_jsonRoundTrip() {
        val p = DnsPreset(name = "阿里云", type = PresetType.DNS, v4 = "223.6.6.6", v6 = "2400:3200::1")
        val back = DnsPreset.parse(p.toJson())
        requireNotNull(back)
        assertEquals(p.id, back.id)
        assertEquals(p.v4, back.v4)
        assertEquals(p.v6, back.v6)
        assertEquals(PresetType.DNS, back.type)
    }

    @Test
    fun preset_rejectsInconsistentFields() {
        assertNull(DnsPreset.parse(DnsPreset(name = "x", type = PresetType.DNS).toJson())) // v4/v6 全空
        assertNull(
            DnsPreset.parse(
                DnsPreset(name = "x", type = PresetType.DNS, v6 = "2400:3200::1").toJson()
                    .put("v6", "not-ipv6")
            )
        )
        assertNull(DnsPreset.parse(DnsPreset(name = "x", type = PresetType.DOH).toJson())) // url 非法
    }
}
