package com.dnsswitch.dns

import org.json.JSONArray
import org.json.JSONObject

/** 运行统计与查询日志（内存环形缓冲，不上传、不落盘） */
object DnsStats {

    private const val MAX_LOG = 200

    private val lock = Any()
    private val stats = LinkedHashMap<ChannelType, MutableStat>()
    private val logs = ArrayList<LogEntry>()

    @Volatile
    private var total = 0

    @Volatile
    private var vpnStartedAt = 0L

    /** 最近一次致命错误（启动失败 / 隧道中断）。UI 依据它显示失败原因，避免「点了没反应」 */
    @Volatile
    var lastError: String? = null
        private set

    class MutableStat(var queries: Int = 0, var failures: Int = 0, var totalMs: Long = 0) {
        val avgMs: Long
            get() = if (queries == 0) 0 else totalMs / queries
    }

    class LogEntry(
        val timeMs: Long,
        val domain: String,
        val channel: String,
        val ms: Long,
        val ok: Boolean
    )

    fun onVpnStart() {
        vpnStartedAt = System.currentTimeMillis()
        lastError = null
    }

    /** 系统事件留痕（非查询）：启动链路各步骤都会记录，在「日志」页以「系统」通道展示，便于定位断点 */
    fun system(msg: String) {
        synchronized(lock) {
            addLog(LogEntry(System.currentTimeMillis(), msg, "系统", 0, true))
        }
    }

    /** 记录致命错误：既存到 lastError（推送给 UI），也在日志里留一条系统记录 */
    fun onVpnError(msg: String) {
        lastError = msg
        synchronized(lock) {
            addLog(LogEntry(System.currentTimeMillis(), msg, "系统", 0, false))
        }
    }

    fun clearLastError() {
        lastError = null
    }

    fun onVpnStop() {
        vpnStartedAt = 0L
    }

    fun vpnStartedAt(): Long = vpnStartedAt

    fun totalQueries(): Int = total

    fun recordQuery(type: ChannelType, domain: String, ms: Long, ok: Boolean) {
        synchronized(lock) {
            val s = stats.getOrPut(type) { MutableStat() }
            s.queries++
            s.totalMs += ms
            if (!ok) s.failures++
            total++
            addLog(LogEntry(System.currentTimeMillis(), domain, type.label, ms, ok))
        }
    }

    fun recordServfail(domain: String, ms: Long) {
        synchronized(lock) {
            total++
            addLog(LogEntry(System.currentTimeMillis(), domain, "全部通道失败", ms, false))
        }
    }

    private fun addLog(e: LogEntry) {
        logs.add(e)
        if (logs.size > MAX_LOG) logs.subList(0, logs.size - MAX_LOG).clear()
    }

    fun statOf(type: ChannelType): MutableStat =
        synchronized(lock) { stats.getOrPut(type) { MutableStat() } }

    fun logsJson(): JSONArray = synchronized(lock) {
        JSONArray().apply {
            logs.forEach { e ->
                put(
                    JSONObject()
                        .put("t", e.timeMs)
                        .put("d", e.domain)
                        .put("c", e.channel)
                        .put("ms", e.ms)
                        .put("ok", e.ok)
                )
            }
        }
    }

    fun clearLogs() {
        synchronized(lock) { logs.clear() }
    }
}
