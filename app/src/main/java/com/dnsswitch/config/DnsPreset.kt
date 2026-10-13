package com.dnsswitch.config

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 预设分类（与 txt 导入文件末尾的分类词对应，不区分大小写） */
enum class PresetType(val id: String, val label: String) {
    DNS("dns", "传统 DNS"),
    DOH("doh", "DoH"),
    DOT("dot", "DoT");

    companion object {
        fun fromId(id: String?): PresetType? =
            if (id == null) null else entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

/**
 * 一条 DNS 预设。按类型只使用其中若干字段：
 * - DNS：[v4] IPv4 地址、[v6] IPv6 地址（两者至少一个非空）
 * - DoT：[host] 主机名或 IP、[v6] 可选 IPv6 备用地址
 * - DoH：[url] 完整 URL
 */
data class DnsPreset(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: PresetType,
    val v4: String = "",
    val v6: String = "",
    val host: String = "",
    val url: String = ""
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("type", type.id)
        .put("v4", v4)
        .put("v6", v6)
        .put("host", host)
        .put("url", url)

    companion object {
        fun parse(o: JSONObject): DnsPreset? {
            val type = PresetType.fromId(o.optString("type")) ?: return null
            val name = o.optString("name").trim()
            if (name.isEmpty()) return null
            val v4 = o.optString("v4").trim()
            val v6 = o.optString("v6").trim()
            val host = o.optString("host").trim()
            val url = o.optString("url").trim()
            val valid = when (type) {
                PresetType.DNS -> (v4.isEmpty() || Validators.isIpv4(v4)) &&
                    (v6.isEmpty() || Validators.isIpv6(v6)) &&
                    (v4.isNotEmpty() || v6.isNotEmpty())
                PresetType.DOT -> host.isNotEmpty() && Validators.isHostOrIp(host) &&
                    (v6.isEmpty() || Validators.isIpv6(v6))
                PresetType.DOH -> Validators.isHttpUrl(url)
            }
            if (!valid) return null
            return DnsPreset(
                id = o.optString("id").takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString(),
                name = name, type = type, v4 = v4, v6 = v6, host = host, url = url
            )
        }
    }
}

/** 导入结果统计 */
data class ImportReport(
    var added: Int = 0,
    var merged: Int = 0,
    val invalid: MutableList<String> = mutableListOf()
) {
    fun message(): String = when {
        added == 0 && merged == 0 && invalid.isEmpty() -> "未识别到有效条目"
        else -> buildString {
            append("新增 ").append(added).append(" 条")
            if (merged > 0) append("，合并 ").append(merged).append(" 条")
            if (invalid.isNotEmpty()) append("，忽略 ").append(invalid.size).append(" 条")
        }
    }

    fun toJsonObject(): JSONObject = JSONObject()
        .put("added", added)
        .put("merged", merged)
        .put("invalid", invalid.size)
        .put("message", message())
        .put("samples", JSONArray().apply { invalid.take(3).forEach { put(it) } })
}

/**
 * 预设导入的纯逻辑层（不依赖 Android，便于 JVM 单测）。
 *
 * 支持的行格式：
 * ```
 * <地址> <名字...> <分类>
 * ```
 * 分类为行**末尾**的 token，取值 dns / doh / dot（不区分大小写）；
 * 名字是中间的所有 token（可含空格）；地址是第一个 token。
 *
 * 分类省略时按地址形态推断：http(s):// → DoH；IP → 传统 DNS；其他主机名 → DoT。
 *
 * 合并规则：同「分类 + 名字」视为同一条预设。因此
 * ```
 * 223.6.6.6 阿里云 DNS
 * 2400:3200::1 阿里云 DNS
 * ```
 * 会合并成一条同时带 IPv4 与 IPv6 的「阿里云」预设。
 */
object PresetLogic {

    /** 解析单行，返回 null 表示该行无效（空行 / 注释 / 地址不合法） */
    fun parseLine(line: String): DnsPreset? {
        val raw = line.trim()
        if (raw.isEmpty() || raw.startsWith("#") || raw.startsWith("//")) return null
        val tokens = raw.split(Regex("\\s+"))
        if (tokens.isEmpty()) return null
        val addr = tokens[0]
        if (addr.isEmpty()) return null

        val tail = tokens.last()
        val declared = PresetType.fromId(tail)
        val nameTokens = if (declared != null) tokens.subList(1, tokens.size - 1) else tokens.subList(1, tokens.size)
        val type = declared ?: guessType(addr)
        val name = nameTokens.joinToString(" ").trim().ifEmpty { addr }

        return when (type) {
            PresetType.DNS -> when {
                Validators.isIpv4(addr) -> DnsPreset(name = name, type = type, v4 = addr)
                Validators.isIpv6(addr) -> DnsPreset(name = name, type = type, v6 = addr)
                else -> null
            }
            PresetType.DOT -> if (Validators.isHostOrIp(addr)) {
                DnsPreset(name = name, type = type, host = addr)
            } else null
            PresetType.DOH -> if (Validators.isHttpUrl(addr)) {
                DnsPreset(name = name, type = type, url = addr)
            } else null
        }
    }

    private fun guessType(addr: String): PresetType = when {
        Validators.isHttpUrl(addr) -> PresetType.DOH
        Validators.isIpAddress(addr) -> PresetType.DNS
        else -> PresetType.DOT
    }

    private fun key(p: DnsPreset): String = p.type.id + "\u0000" + p.name.lowercase()

    /** 把解析出的条目合并进已有列表，返回新列表与统计 */
    fun merge(existing: List<DnsPreset>, incoming: List<DnsPreset>): Pair<List<DnsPreset>, ImportReport> {
        val map = LinkedHashMap<String, DnsPreset>()
        existing.forEach { map[key(it)] = it }
        val report = ImportReport()
        for (p in incoming) {
            val k = key(p)
            val old = map[k]
            if (old == null) {
                map[k] = p
                report.added++
            } else {
                map[k] = old.mergeWith(p)
                report.merged++
            }
        }
        return map.values.toList() to report
    }

    /** 导入整段文本：逐行解析 + 合并，非法行记入 report.invalid */
    fun importText(existing: List<DnsPreset>, text: String): Pair<List<DnsPreset>, ImportReport> {
        val report = ImportReport()
        val parsed = ArrayList<DnsPreset>()
        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) return@forEach
            val p = parseLine(trimmed)
            if (p == null) report.invalid.add(trimmed) else parsed += p
        }
        val (list, m) = merge(existing, parsed)
        report.added = m.added
        report.merged = m.merged
        return list to report
    }

    private fun DnsPreset.mergeWith(other: DnsPreset): DnsPreset = copy(
        v4 = other.v4.ifEmpty { v4 },
        v6 = other.v6.ifEmpty { v6 },
        host = other.host.ifEmpty { host },
        url = other.url.ifEmpty { url }
    )
}
