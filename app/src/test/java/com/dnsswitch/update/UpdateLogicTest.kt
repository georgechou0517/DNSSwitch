package com.dnsswitch.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateLogicTest {

    @Test
    fun normalize_stripsPrefixAndSuffix() {
        assertEquals("26.10.1", UpdateLogic.normalizeVersion("v26.10.1"))
        assertEquals("26.10.1", UpdateLogic.normalizeVersion("26.10.1-debug"))
        assertEquals("26.10.1", UpdateLogic.normalizeVersion("DNSSwitch-26.10.1-release"))
    }

    @Test
    fun normalize_blankOrNoNumber() {
        assertEquals("", UpdateLogic.normalizeVersion(null))
        assertEquals("", UpdateLogic.normalizeVersion(""))
        assertEquals("", UpdateLogic.normalizeVersion("latest"))
    }

    /** 版本号按数值逐段比较，不能按字典序：26.10.10 应大于 26.10.2 */
    @Test
    fun compare_numericNotLexical() {
        assertTrue(UpdateLogic.compareVersion("26.10.10", "26.10.2") > 0)
        assertTrue(UpdateLogic.compareVersion("26.9.1", "26.10.1") < 0)
        assertTrue(UpdateLogic.compareVersion("27.1", "26.10.1") > 0)
        assertEquals(0, UpdateLogic.compareVersion("26.10.1", "26.10.1"))
        assertEquals(0, UpdateLogic.compareVersion("26.10", "26.10.0"))
    }

    @Test
    fun isNewer_handlesDebugSuffix() {
        assertTrue(UpdateLogic.isNewer("v26.11.1", "26.10.1-debug"))
        assertFalse(UpdateLogic.isNewer("26.10.1", "26.10.1-debug"))
        assertFalse(UpdateLogic.isNewer("26.9.1", "26.10.1"))
        assertFalse(UpdateLogic.isNewer("latest", "26.10.1"))
    }

    @Test
    fun parseGitHub_picksReleaseApkNotDebug() {
        val json = """{"tag_name":"26.11.1","name":"DNSSwitch 26.11.1","body":"更新说明",
          "html_url":"https://github.com/georgechou0517/DNSSwitch/releases/tag/26.11.1",
          "assets":[
            {"name":"DNSSwitch-26.11.1-debug.apk","browser_download_url":"https://x/debug.apk"},
            {"name":"DNSSwitch-26.11.1-release.apk","browser_download_url":"https://x/release.apk"},
            {"name":"DNSSwitch-26.11.1-source.zip","browser_download_url":"https://x/src.zip"}
          ]}"""
        val info = UpdateLogic.parseGitHub(json)!!
        assertEquals("26.11.1", info.version)
        assertEquals("26.11.1", info.rawTag)
        assertEquals("DNSSwitch 26.11.1", info.name)
        assertEquals("更新说明", info.notes)
        assertEquals("https://x/release.apk", info.apkUrl)
        assertEquals("DNSSwitch-26.11.1-release.apk", info.apkName)
    }

    /** Gitee 仓库尚未上传附件时，APK 直链为空，页面地址回退到 Gitee Release 页 */
    @Test
    fun parseGitee_fallsBackToPageWhenNoApk() {
        val json = """{"tag_name":"26.11.1","name":"26.11.1","body":"","assets":[]}"""
        val info = UpdateLogic.parseGitee(json)!!
        assertEquals("26.11.1", info.version)
        assertEquals("", info.apkUrl)
        assertEquals(UpdateLogic.PAGE_GITEE, info.pageUrl)
    }

    @Test
    fun parseRelease_invalidReturnsNull() {
        assertNull(UpdateLogic.parseGitHub("not-json"))
        assertNull(UpdateLogic.parseGitHub("""{"name":"latest"}"""))
    }
}
