package com.dnsswitch.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

/** Internet 校验和：RFC 1071 经典向量 + 自校验性质 */
class ChecksumTest {

    @Test
    fun rfc1071_vector() {
        // RFC 1071 §3：00 01 F2 03 F4 F5 F6 F7 → 校验和 0x220D
        val data = byteArrayOf(
            0x00, 0x01, 0xF2.toByte(), 0x03,
            0xF4.toByte(), 0xF5.toByte(), 0xF6.toByte(), 0xF7.toByte()
        )
        assertEquals(0x220D, Checksum.compute(data, 0, data.size))
    }

    @Test
    fun headerWithChecksum_verifiesToZero() {
        val data = byteArrayOf(
            0x00, 0x01, 0xF2.toByte(), 0x03,
            0xF4.toByte(), 0xF5.toByte(), 0xF6.toByte(), 0xF7.toByte()
        )
        val sum = Checksum.compute(data, 0, data.size)
        val withCsum = data + byteArrayOf((sum shr 8).toByte(), sum.toByte())
        // 填入校验和后整体再算一遍应为 0
        assertEquals(0, Checksum.compute(withCsum, 0, withCsum.size))
    }

    @Test
    fun oddLength_lastBytePaddedHigh() {
        // 单字节 0x01：视为 0x0100，取反 = 0xFEFF
        assertEquals(0xFEFF, Checksum.compute(byteArrayOf(0x01), 0, 1))
    }

    @Test
    fun allZero_checksumIsFFFF() {
        assertEquals(0xFFFF, Checksum.compute(ByteArray(20), 0, 20))
    }
}
