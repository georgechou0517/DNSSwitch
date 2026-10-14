package com.dnsswitch.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * APK 下载完成后自动调起系统安装界面。
 *
 * DownloadManager 完成广播是显式的（带包名），Android 8+ 的隐式广播限制不影响静态注册。
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (id < 0L) return

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return
        if (!isSuccessful(dm, id)) return

        val uri = runCatching { dm.getUriForDownloadedFile(id) }.getOrNull() ?: return
        val install = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(install) }
    }

    private fun isSuccessful(dm: DownloadManager, id: Long): Boolean = runCatching {
        val q = DownloadManager.Query().setFilterById(id)
        dm.query(q)?.use { c ->
            if (c.moveToFirst()) {
                c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) ==
                        DownloadManager.STATUS_SUCCESSFUL
            } else false
        } ?: false
    }.getOrDefault(false)
}
