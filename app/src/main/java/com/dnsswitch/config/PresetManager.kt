package com.dnsswitch.config

import android.content.Context
import org.json.JSONArray

/**
 * 用户自定义预设的持久化：SharedPreferences 存 JSON 数组。
 * 与 [ConfigManager] 分开存放，避免预设增删影响当前生效配置。
 */
object PresetManager {

    private const val PREFS = "dnsswitch_presets"
    private const val KEY_JSON = "presets_json"

    @Volatile
    private var prefs: android.content.SharedPreferences? = null

    @Volatile
    private var items: List<DnsPreset> = emptyList()

    @Synchronized
    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            items = parseQuietly(prefs!!.getString(KEY_JSON, null))
        }
    }

    @Synchronized
    fun all(): List<DnsPreset> = items

    @Synchronized
    fun toJsonArray(): JSONArray = JSONArray().apply {
        items.forEach { put(it.toJson()) }
    }

    /** 导入文本，返回统计报告 */
    @Synchronized
    fun importText(text: String): ImportReport {
        val (list, report) = PresetLogic.importText(items, text)
        persist(list)
        return report
    }

    /** 追加 / 合并单条预设（「存为预设」按钮） */
    @Synchronized
    fun add(preset: DnsPreset): ImportReport {
        val (list, report) = PresetLogic.merge(items, listOf(preset))
        persist(list)
        return report
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val next = items.filterNot { it.id == id }
        if (next.size == items.size) return false
        persist(next)
        return true
    }

    private fun persist(list: List<DnsPreset>) {
        items = list
        prefs?.edit()?.putString(KEY_JSON, toJsonArray().toString())?.apply()
    }

    private fun parseQuietly(s: String?): List<DnsPreset> {
        if (s.isNullOrEmpty()) return emptyList()
        return runCatching {
            val arr = JSONArray(s)
            val out = ArrayList<DnsPreset>()
            for (i in 0 until arr.length()) {
                DnsPreset.parse(arr.optJSONObject(i) ?: continue)?.let { out += it }
            }
            out
        }.getOrDefault(emptyList())
    }
}
