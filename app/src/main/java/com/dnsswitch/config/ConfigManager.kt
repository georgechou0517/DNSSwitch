package com.dnsswitch.config

import android.content.Context
import org.json.JSONObject

/**
 * 配置持久化：SharedPreferences 存 JSON，内存中持有当前配置。
 * 校验失败会抛 IllegalArgumentException，由桥接层回传给 JS 提示用户。
 */
object ConfigManager {

    private const val PREFS = "dnsswitch_config"
    private const val KEY_JSON = "config_json"

    @Volatile
    private var prefs: android.content.SharedPreferences? = null

    @Volatile
    private var cfg: AppConfig = AppConfig.DEFAULT

    val config: AppConfig get() = cfg

    @Synchronized
    fun init(context: Context) {
        if (prefs == null) {
            val p = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs = p
            cfg = parseQuietly(p.getString(KEY_JSON, null)) ?: AppConfig.DEFAULT
        }
    }

    @Synchronized
    fun update(obj: JSONObject) {
        val parsed = AppConfig.parse(obj) ?: throw IllegalArgumentException("配置校验失败")
        prefs?.edit()?.putString(KEY_JSON, parsed.toJson().toString())?.apply()
        cfg = parsed
    }

    fun toJson(): JSONObject = cfg.toJson()

    private fun parseQuietly(s: String?): AppConfig? {
        if (s.isNullOrEmpty()) return null
        return runCatching { AppConfig.parse(JSONObject(s)) }.getOrNull()
    }
}
