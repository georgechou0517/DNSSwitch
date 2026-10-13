package com.dnsswitch.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 配置校验与 JSON 序列化 */
class AppConfigTest {

    @Test
    fun validators_ipAddress() {
        assertTrue(Validators.isIpAddress("223.5.5.5"))
        assertTrue(Validators.isIpAddress("0.0.0.0"))
        assertTrue(Validators.isIpAddress("255.255.255.255"))
        assertTrue(Validators.isIpAddress("2001:db8::1"))
        assertFalse(Validators.isIpAddress("256.1.1.1"))
        assertFalse(Validators.isIpAddress("1.2.3"))
        assertFalse(Validators.isIpAddress("a.b.c.d"))
        assertFalse(Validators.isIpAddress(""))
        assertFalse(Validators.isIpAddress("1.2.3.4.5"))
    }

    @Test
    fun validators_port() {
        assertTrue(Validators.isPort(1))
        assertTrue(Validators.isPort(53))
        assertTrue(Validators.isPort(853))
        assertTrue(Validators.isPort(65535))
        assertFalse(Validators.isPort(0))
        assertFalse(Validators.isPort(65536))
        assertFalse(Validators.isPort(-1))
    }

    @Test
    fun validators_url() {
        assertTrue(Validators.isHttpUrl("https://dns.alidns.com/dns-query"))
        assertTrue(Validators.isHttpUrl("https://dns.alidns.com:8443/dns-query"))
        assertTrue(Validators.isHttpUrl("http://1.2.3.4/dns-query"))
        assertFalse(Validators.isHttpUrl("ftp://x/dns-query"))
        assertFalse(Validators.isHttpUrl("dns.alidns.com"))
        assertFalse(Validators.isHttpUrl(""))
    }

    @Test
    fun parse_defaultRoundTrip() {
        val c = AppConfig.parse(AppConfig.DEFAULT.toJson())
        require(c != null)
        assertEquals(Strategy.SMART_FAILOVER, c!!.strategy)
        assertTrue(c.traditional.enabled)
        assertTrue(c.doh.enabled)
        assertFalse(c.dot.enabled)
        assertEquals(53, c.traditional.port)
        assertEquals(853, c.dot.port)
    }

    @Test
    fun parse_rejectsBadIpWhenEnabled() {
        val json = AppConfig.DEFAULT.toJson()
        json.getJSONObject("traditional").put("server", "300.1.1.1")
        assertNull(AppConfig.parse(json))
    }

    @Test
    fun parse_rejectsBadPort() {
        val json = AppConfig.DEFAULT.toJson()
        json.getJSONObject("dot").put("enabled", true)
        json.getJSONObject("dot").put("port", 70000)
        assertNull(AppConfig.parse(json))
    }

    @Test
    fun parse_rejectsBadDohUrlWhenEnabled() {
        val json = AppConfig.DEFAULT.toJson()
        json.getJSONObject("doh").put("url", "not-a-url")
        assertNull(AppConfig.parse(json))
    }

    @Test
    fun parse_skipsValidationWhenDisabled() {
        val json = AppConfig.DEFAULT.toJson()
        json.getJSONObject("traditional").put("enabled", false)
        json.getJSONObject("traditional").put("server", "whatever")
        val c = AppConfig.parse(json)
        require(c != null)
        assertFalse(c!!.traditional.enabled)
    }

    @Test
    fun parse_unknownStrategyFallsBack() {
        val json = AppConfig.DEFAULT.toJson()
        json.put("strategy", "not_exist")
        val c = AppConfig.parse(json)
        require(c != null)
        assertEquals(Strategy.SMART_FAILOVER, c!!.strategy)
    }

    @Test
    fun parse_customPortPreserved() {
        val json = AppConfig.DEFAULT.toJson()
        json.getJSONObject("traditional").put("port", 5353)
        json.getJSONObject("traditional").put("tcp", true)
        val c = AppConfig.parse(json)
        require(c != null)
        assertEquals(5353, c!!.traditional.port)
        assertTrue(c.traditional.useTcp)
    }

    @Test
    fun validators_ipv6() {
        assertTrue(Validators.isIpv6("2400:3200::1"))
        assertTrue(Validators.isIpv6("::1"))
        assertTrue(Validators.isIpv6("2001:db8:0:0:0:0:0:1"))
        assertTrue(Validators.isIpv6("fe80::1"))
        assertFalse(Validators.isIpv6("2400:3200::1::2")) // 两处压缩
        assertFalse(Validators.isIpv6("gggg::1"))
        assertFalse(Validators.isIpv6("223.5.5.5"))
        assertFalse(Validators.isIpv6(""))
        // 无压缩时必须是 8 组
        assertFalse(Validators.isIpv6("2001:db8:1"))
    }

    @Test
    fun validators_hostname() {
        assertTrue(Validators.isHostname("dns.alidns.com"))
        assertTrue(Validators.isHostname("one.one.one.one"))
        assertFalse(Validators.isHostname("localhost")) // 单标签
        assertFalse(Validators.isHostname("-bad.example.com"))
        assertFalse(Validators.isHostname("dns.alidns.com "))
        assertTrue(Validators.isHostOrIp("223.5.5.5"))
        assertTrue(Validators.isHostOrIp("2400:3200::1"))
        assertTrue(Validators.isHostOrIp("dns.alidns.com"))
        assertFalse(Validators.isHostOrIp("dns alidns com"))
    }

    @Test
    fun traditional_ipv4AndIpv6Coexist() {
        val json = AppConfig.DEFAULT.toJson()
        json.getJSONObject("traditional").put("serverV6", "2400:3200::1")
        val c = AppConfig.parse(json)
        require(c != null)
        assertEquals("223.5.5.5", c!!.traditional.server)
        assertEquals("2400:3200::1", c.traditional.serverV6)
        assertTrue(c.traditional.hasV4)
        assertTrue(c.traditional.hasV6)
    }

    @Test
    fun traditional_onlyIpv6IsAllowed() {
        val json = AppConfig.DEFAULT.toJson()
        json.getJSONObject("traditional").put("server", "")
        json.getJSONObject("traditional").put("serverV6", "2400:3200::1")
        val c = AppConfig.parse(json)
        require(c != null)
        assertEquals("", c!!.traditional.server)
        assertEquals("2400:3200::1", c.traditional.serverV6)
    }

    @Test
    fun traditional_rejectsEmptyBothWhenEnabled() {
        val json = AppConfig.DEFAULT.toJson()
        json.getJSONObject("traditional").put("server", "")
        json.getJSONObject("traditional").put("serverV6", "")
        assertNull(AppConfig.parse(json))
    }

    @Test
    fun traditional_rejectsBadIpv6() {
        val json = AppConfig.DEFAULT.toJson()
        json.getJSONObject("traditional").put("serverV6", "2400:3200::gg")
        assertNull(AppConfig.parse(json))
    }

    @Test
    fun dot_acceptsHostnameAndIpv6Backup() {
        val json = AppConfig.DEFAULT.toJson()
        json.getJSONObject("dot").put("enabled", true)
        json.getJSONObject("dot").put("server", "dns.alidns.com")
        json.getJSONObject("dot").put("serverV6", "2400:3200::1")
        val c = AppConfig.parse(json)
        require(c != null)
        assertEquals("dns.alidns.com", c!!.dot.server)
        assertEquals("2400:3200::1", c.dot.serverV6)
    }

    @Test
    fun dot_rejectsInvalidServerWhenEnabled() {
        val json = AppConfig.DEFAULT.toJson()
        json.getJSONObject("dot").put("enabled", true)
        json.getJSONObject("dot").put("server", "dns alidns com")
        assertNull(AppConfig.parse(json))
    }

    @Test
    fun parse_missingServerV6BackwardCompatible() {
        // 旧版本配置没有 serverV6 字段，应视为空串而非解析失败
        val json = AppConfig.DEFAULT.toJson()
        json.getJSONObject("traditional").remove("serverV6")
        json.getJSONObject("dot").remove("serverV6")
        val c = AppConfig.parse(json)
        require(c != null)
        assertEquals("", c!!.traditional.serverV6)
        assertEquals("", c.dot.serverV6)
    }
}
