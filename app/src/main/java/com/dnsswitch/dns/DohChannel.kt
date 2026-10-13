package com.dnsswitch.dns

import com.dnsswitch.config.DohConfig
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * DoH 通道（RFC 8484）。
 * 优先 POST（application/dns-message），失败回退 GET（?dns=base64url），兼容只支持 GET 的服务器。
 * URL 可自定义，允许带端口，如 https://dns.alidns.com:8443/dns-query。
 */
class DohChannel(
    private val cfg: DohConfig,
    private val client: OkHttpClient
) : DnsChannel {

    override val type = ChannelType.DOH
    override val defaultTimeoutMs = 2500L

    override fun query(query: ByteArray, timeoutMs: Long): ByteArray? {
        val url = cfg.url.trim()
        if (url.isEmpty()) return null
        post(query, url)?.let { return it }
        return get(query, url)
    }

    private fun post(query: ByteArray, url: String): ByteArray? = try {
        val body: RequestBody = query.toRequestBody(MEDIA_TYPE)
        val req = Request.Builder()
            .url(url)
            .post(body)
            .header("Accept", DNS_MESSAGE)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.bytes()?.takeIf { it.size >= 12 }
        }
    } catch (e: Exception) {
        null
    }

    private fun get(query: ByteArray, url: String): ByteArray? = try {
        val b64 = android.util.Base64.encodeToString(
            query,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
        )
        val sep = if (url.contains('?')) "&" else "?"
        val req = Request.Builder()
            .url(url + sep + "dns=" + b64)
            .header("Accept", DNS_MESSAGE)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.bytes()?.takeIf { it.size >= 12 }
        }
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val DNS_MESSAGE = "application/dns-message"
        private val MEDIA_TYPE: MediaType = DNS_MESSAGE.toMediaType()
    }
}
