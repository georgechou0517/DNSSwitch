package com.dnsswitch.config

import org.json.JSONObject

/** 分流策略 */
enum class Strategy(val id: String, val label: String) {
    ALL_TRADITIONAL("all_traditional", "全部走传统 DNS"),
    ALL_DOH("all_doh", "全部走 DoH"),
    ALL_DOT("all_dot", "全部走 DoT"),
    SMART_FAILOVER("smart_failover", "智能分流（故障转移）"),
    PARALLEL_RACE("parallel_race", "并行竞速");

    companion object {
        fun fromId(id: String?): Strategy = entries.firstOrNull { it.id == id } ?: SMART_FAILOVER
    }
}

/**
 * 传统 DNS 配置：IPv4 与 IPv6 地址可分别指定，端口与 UDP/TCP 完全自定义。
 *
 * - [server]：IPv4 地址，可为空（仅用 IPv6 时留空）
 * - [serverV6]：IPv6 地址，可为空（仅用 IPv4 时留空）
 * - 两者至少填一个；都填时先走 IPv4，失败自动改用 IPv6。
 */
data class TraditionalConfig(
    val enabled: Boolean,
    val server: String,
    val serverV6: String,
    val port: Int,
    val useTcp: Boolean
) {
    val hasV4: Boolean get() = server.isNotEmpty()
    val hasV6: Boolean get() = serverV6.isNotEmpty()

    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled)
        .put("server", server)
        .put("serverV6", serverV6)
        .put("port", port)
        .put("tcp", useTcp)

    companion object {
        val DEFAULT = TraditionalConfig(true, "223.5.5.5", "", 53, false)

        fun parse(o: JSONObject): TraditionalConfig? {
            val server = o.optString("server").trim()
            val serverV6 = o.optString("serverV6").trim()
            val port = o.optInt("port", 53)
            val enabled = o.optBoolean("enabled", true)
            if (enabled) {
                if (server.isNotEmpty() && !Validators.isIpv4(server)) return null
                if (serverV6.isNotEmpty() && !Validators.isIpv6(serverV6)) return null
                if (server.isEmpty() && serverV6.isEmpty()) return null
            }
            if (!Validators.isPort(port)) return null
            return TraditionalConfig(enabled, server, serverV6, port, o.optBoolean("tcp", false))
        }
    }
}

/** DoH 配置（URL 可带端口，主机名由 bootstrap 解析器解析） */
data class DohConfig(val enabled: Boolean, val url: String) {
    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled)
        .put("url", url)

    companion object {
        val DEFAULT = DohConfig(true, "https://dns.alidns.com/dns-query")

        fun parse(o: JSONObject): DohConfig? {
            val url = o.optString("url").trim()
            val enabled = o.optBoolean("enabled", true)
            if (enabled && !Validators.isHttpUrl(url)) return null
            return DohConfig(enabled, url)
        }
    }
}

/**
 * DoT 配置（主机名或 IP + 端口，默认 853）。
 *
 * - [server]：主机名（如 dns.alidns.com）或 IPv4 地址
 * - [serverV6]：IPv6 备用地址，可为空
 */
data class DotConfig(
    val enabled: Boolean,
    val server: String,
    val serverV6: String,
    val port: Int
) {
    val hasV6: Boolean get() = serverV6.isNotEmpty()

    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled)
        .put("server", server)
        .put("serverV6", serverV6)
        .put("port", port)

    companion object {
        val DEFAULT = DotConfig(false, "dns.alidns.com", "", 853)

        fun parse(o: JSONObject): DotConfig? {
            val server = o.optString("server").trim()
            val serverV6 = o.optString("serverV6").trim()
            val port = o.optInt("port", 853)
            val enabled = o.optBoolean("enabled", false)
            if (enabled) {
                if (server.isEmpty()) return null
                if (!Validators.isHostOrIp(server)) return null
                if (serverV6.isNotEmpty() && !Validators.isIpv6(serverV6)) return null
            }
            if (!Validators.isPort(port)) return null
            return DotConfig(enabled, server, serverV6, port)
        }
    }
}

data class AppConfig(
    val traditional: TraditionalConfig,
    val doh: DohConfig,
    val dot: DotConfig,
    val strategy: Strategy
) {
    fun toJson(): JSONObject = JSONObject()
        .put("traditional", traditional.toJson())
        .put("doh", doh.toJson())
        .put("dot", dot.toJson())
        .put("strategy", strategy.id)

    companion object {
        val DEFAULT = AppConfig(
            TraditionalConfig.DEFAULT,
            DohConfig.DEFAULT,
            DotConfig.DEFAULT,
            Strategy.SMART_FAILOVER
        )

        fun parse(o: JSONObject): AppConfig? {
            val traditional = TraditionalConfig.parse(o.optJSONObject("traditional") ?: JSONObject())
                ?: return null
            val doh = DohConfig.parse(o.optJSONObject("doh") ?: JSONObject()) ?: return null
            val dot = DotConfig.parse(o.optJSONObject("dot") ?: JSONObject()) ?: return null
            return AppConfig(traditional, doh, dot, Strategy.fromId(o.optString("strategy")))
        }
    }
}

/** 配置校验 */
object Validators {

    fun isIpv4(s: String): Boolean {
        if (s.isEmpty()) return false
        val parts = s.split('.')
        if (parts.size != 4) return false
        return parts.all { p ->
            p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() } && p.toInt() <= 255
        }
    }

    fun isIpv6(s: String): Boolean {
        if (s.isEmpty() || s.length > 45) return false
        if (!s.contains(':')) return false
        val allowed: (Char) -> Boolean = {
            it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.'
        }
        if (!s.all(allowed)) return false
        val group: (String) -> Boolean = { g ->
            g.isNotEmpty() && g.length <= 4 && g.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        }
        // 末组为点分 IPv4（如 ::ffff:1.2.3.4）时，它占 2 个 16 位组
        fun splitTail(parts: List<String>): Pair<List<String>, Int>? {
            if (parts.isEmpty()) return parts to 0
            val last = parts.last()
            if (!last.contains('.')) return parts to 0
            if (!isIpv4(last)) return null
            return parts.dropLast(1) to 2
        }

        // 含 "::" 压缩：两段合计最多 7 组
        val idx = s.indexOf("::")
        if (idx >= 0) {
            if (s.indexOf("::", idx + 1) >= 0) return false // 只允许一处压缩
            val left = if (idx == 0) emptyList() else s.substring(0, idx).split(':')
            val rightRaw = if (idx + 2 == s.length) emptyList() else s.substring(idx + 2).split(':')
            val (right, tailGroups) = splitTail(rightRaw) ?: return false
            if (left.size + right.size + tailGroups > 7) return false
            return (left + right).all(group)
        }
        // 无压缩：必须正好 8 组
        val (parts, tailGroups) = splitTail(s.split(':')) ?: return false
        if (parts.size + tailGroups != 8) return false
        return parts.all(group)
    }

    /** IPv4 或 IPv6 */
    fun isIpAddress(s: String): Boolean =
        if (s.contains(':')) isIpv6(s) else isIpv4(s)

    /** 域名主机名（如 dns.alidns.com） */
    fun isHostname(s: String): Boolean {
        if (s.isEmpty() || s.length > 253) return false
        val labels = s.split('.')
        if (labels.size < 2) return false
        return labels.all { lbl ->
            lbl.isNotEmpty() && lbl.length <= 63 &&
                lbl.all { it.isLetterOrDigit() || it == '-' } &&
                !lbl.startsWith("-") && !lbl.endsWith("-")
        }
    }

    /** 主机名或 IP（DoT 服务器两种都可填） */
    fun isHostOrIp(s: String): Boolean = isIpAddress(s) || isHostname(s)

    fun isPort(p: Int): Boolean = p in 1..65535

    fun isHttpUrl(s: String): Boolean =
        s.startsWith("https://") || s.startsWith("http://")
}
