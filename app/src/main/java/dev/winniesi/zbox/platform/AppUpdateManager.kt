package dev.winniesi.zbox.platform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import dev.winniesi.zbox.core.AppRelease
import dev.winniesi.zbox.core.AppUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Release 自更新：检查（手动/每日自动）→ 弹窗确认 → 跳转浏览器到
 * GitHub Release 页手动下载安装。
 *
 * 不做应用内下载：DownloadManager 直连 GitHub 的 APK 资产在部分网络环境
 * 不可用，浏览器下载交给用户自己的网络手段更可靠。
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
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)

    /** 冷启动时调用：距上次成功检查超过 24h 才静默检查一次。 */
    fun autoCheckIfDue() {
        val due = System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0L) > CHECK_INTERVAL_MS
        if (due) check(manual = false)
    }

    fun check(manual: Boolean) {
        if (_state.value is State.Checking) return
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

    /** 跳转浏览器打开该版本的 GitHub Release 页，由用户手动下载安装。 */
    fun openReleasePage(release: AppRelease) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(releasePageUrl(release)))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        dismissAvailable()
    }

    private fun releasePageUrl(release: AppRelease): String =
        "https://github.com/winniesi/zbox/releases/tag/${release.tag}"

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
        private const val KEY_LAST_CHECK = "last_check_ms"
        private const val KEY_IGNORED = "ignored_version"
        private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
    }
}
