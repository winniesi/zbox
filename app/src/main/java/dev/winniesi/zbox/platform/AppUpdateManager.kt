package dev.winniesi.zbox.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import dev.winniesi.zbox.core.AppRelease
import dev.winniesi.zbox.core.AppUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Release 自更新：检查（手动/每日自动）→ 确认 → DownloadManager 下载 →
 * 下载完成广播里发通知并自动拉起系统安装器。
 *
 * 状态机经 [state] 暴露给 Compose；下载完成由 Application 注册的
 * ACTION_DOWNLOAD_COMPLETE 接收器转入 [onDownloadComplete]。
 */
class AppUpdateManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    sealed interface State {
        data object Idle : State
        data object Checking : State
        /** 发现新版本；manual=true 表示用户主动检查（不受「忽略此版本」影响）。 */
        data class Available(val release: AppRelease, val manual: Boolean) : State
        data class UpToDate(val manual: Boolean) : State
        data class Failed(val message: String, val manual: Boolean) : State
        data class Downloading(val release: AppRelease) : State
        data class Downloaded(val release: AppRelease, val file: File?) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)

    init {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "应用更新", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /** 冷启动时调用：距上次成功检查超过 24h 才静默检查一次。 */
    fun autoCheckIfDue() {
        val due = System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0L) > CHECK_INTERVAL_MS
        if (due) check(manual = false)
    }

    fun check(manual: Boolean) {
        if (_state.value is State.Checking || _state.value is State.Downloading) return
        _state.value = State.Checking
        scope.launch {
            _state.value = try {
                val release = fetchLatest()
                prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
                val current = installedVersion()
                when {
                    current == null -> State.Failed("无法读取当前版本号", manual)
                    !AppUpdate.isNewer(current, release.version) -> State.UpToDate(manual)
                    !manual && release.version == prefs.getString(KEY_IGNORED, null) -> State.Idle
                    else -> State.Available(release, manual)
                }
            } catch (e: Exception) {
                State.Failed(e.message ?: "网络错误", manual)
            }
        }
    }

    /** 「以后再说」：静默检查跳过该版本，手动检查不受影响。 */
    fun ignore(release: AppRelease) {
        prefs.edit().putString(KEY_IGNORED, release.version).apply()
        if (_state.value is State.Available) _state.value = State.Idle
    }

    /** 手动关闭「发现新版本」弹窗（不记住忽略）。 */
    fun dismissAvailable() {
        if (_state.value is State.Available) _state.value = State.Idle
    }

    /** 关闭「已下载」弹窗（通知仍保留，可稍后安装）。 */
    fun dismissDownloaded() {
        if (_state.value is State.Downloaded) _state.value = State.Idle
    }

    /** 从「已下载」状态再次拉起安装器（通知丢失时的兜底入口）。 */
    fun installDownloaded() {
        val downloaded = _state.value as? State.Downloaded ?: return
        val file = downloaded.file ?: return
        runCatching { context.startActivity(installIntent(file)) }
    }

    /** DownloadManager 下载 release APK 到 App 专属外部目录（无需存储权限）。 */
    fun download(release: AppRelease) {
        val url = release.apkUrl ?: run {
            _state.value = State.Failed("该 Release 没有 APK 资产", true)
            return
        }
        val name = release.apkName ?: "ZBox-${release.version}.apk"
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(name)
            .setDescription("ZBox ${release.version} 更新包")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, name)
        val id = (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        prefs.edit().putLong(KEY_DOWNLOAD_ID, id).apply()
        _state.value = State.Downloading(release)
    }

    /** Application 里注册的 ACTION_DOWNLOAD_COMPLETE 接收器回调。 */
    fun onDownloadComplete(id: Long) {
        if (id != prefs.getLong(KEY_DOWNLOAD_ID, -1L)) return
        val current = _state.value as? State.Downloading ?: return
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = dm.query(DownloadManager.Query().setFilterById(id))
        val file = cursor.use { c ->
            if (c.moveToFirst()) {
                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                        ?.let(Uri::parse)?.path?.let(::File)
                } else {
                    null
                }
            } else {
                null
            }
        }
        _state.value = State.Downloaded(current.release, file)
        if (file != null) {
            postInstallNotification(file, current.release)
            // 用户刚点了「下载并安装」，直接拉起安装器；失败时仍有通知兜底
            runCatching { context.startActivity(installIntent(file)) }
        }
    }

    private fun postInstallNotification(file: File, release: AppRelease) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pi = PendingIntent.getActivity(
            context,
            file.absolutePath.hashCode(),
            installIntent(file),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = androidx.core.app.NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("ZBox ${release.version} 已下载")
            .setContentText("点击安装更新")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        nm.notify(file.absolutePath.hashCode(), notification)
    }

    private fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** GitHub releases/latest；网络与解析异常向上抛，由 [check] 收敛为 Failed。 */
    private suspend fun fetchLatest(): AppRelease = withContext(Dispatchers.IO) {
        val conn = URL("https://api.github.com/repos/winniesi/zbox/releases/latest")
            .openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "ZBox-Android")
            if (conn.responseCode !in 200..299) throw RuntimeException("GitHub HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { AppUpdate.parseReleaseJson(it.readText()) }
                ?: throw RuntimeException("Release 响应解析失败（缺少 APK 资产？）")
        } finally {
            conn.disconnect()
        }
    }

    private fun installedVersion(): String? = try {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0)
        }
        info.versionName
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val CHANNEL_ID = "app_update"
        private const val KEY_LAST_CHECK = "last_check_ms"
        private const val KEY_IGNORED = "ignored_version"
        private const val KEY_DOWNLOAD_ID = "download_id"
        private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
    }
}
