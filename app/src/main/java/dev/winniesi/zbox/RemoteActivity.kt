package dev.winniesi.zbox

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import dev.winniesi.zbox.core.DeviceRecord
import dev.winniesi.zbox.di.AppContainer
import dev.winniesi.zbox.platform.DeviceWebViewFactory
import dev.winniesi.zbox.platform.RemoteTaskMonitor
import dev.winniesi.zbox.ui.remote.RemoteFailure
import dev.winniesi.zbox.ui.remote.httpFailureOf
import dev.winniesi.zbox.ui.remote.webViewErrorFailureOf
import kotlinx.coroutines.launch

/**
 * 远程控制页：WebView 必须用原生 View 层级直挂（setContentView），
 * 不能放进 Compose AndroidView —— Compose 托管下此 WebView 版本的
 * vh/dvh 视口单位会解析为 0，官方页面（整页依赖 dvh）会渲染成黑屏。
 */
class RemoteActivity : ComponentActivity() {

    private lateinit var container: AppContainer
    private var mid = ""
    private var boundIssuedAt = 0L
    private var webView: WebView? = null

    private lateinit var titleView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var content: FrameLayout
    private lateinit var monitorButton: ImageView

    /** 首次开启任务提醒时申请通知权限；拒绝则维持关闭。 */
    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                applyMonitorEnabled(true)
            } else {
                Toast.makeText(this, "未授予通知权限，无法在任务完成时提醒", Toast.LENGTH_LONG).show()
                renderMonitorButton()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = (application as ZBoxApplication).container
        mid = intent.getStringExtra(EXTRA_MID).orEmpty()

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF101014.toInt())
            // targetSdk 35+ 强制边到边：让根布局自动加上状态栏/导航栏内边距
            fitsSystemWindows = true
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        val back = TextView(this).apply {
            text = "←"
            textSize = 20f
            setPadding(dp(16), 0, dp(16), 0)
            setOnClickListener { finish() }
        }
        titleView = TextView(this).apply {
            setTextColor(0xFFE4E2E6.toInt())
            textSize = 18f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val reload = TextView(this).apply {
            text = "刷新"
            textSize = 15f
            setPadding(dp(16), 0, dp(16), 0)
            setOnClickListener {
                hideOverlay()
                loadCurrent()
            }
        }
        monitorButton = ImageView(this).apply {
            val p = dp(12)
            setPadding(p, 0, p, 0)
            background = null
            contentDescription = "后台任务提醒开关"
            setColorFilter(0xFFE4E2E6.toInt())
            setOnClickListener { onMonitorToggle() }
        }
        renderMonitorButton()
        bar.addView(back)
        bar.addView(titleView)
        bar.addView(monitorButton)
        bar.addView(reload)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
        }
        content = FrameLayout(this).apply { setBackgroundColor(0xFF101014.toInt()) }

        root.addView(bar, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(
            progressBar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)),
        )
        root.addView(
            content,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        setContentView(root)

        lifecycleScope.launch {
            val record = container.repository.refresh().firstOrNull { it.mid == mid }
            if (record == null) {
                showMissing()
            } else {
                bind(record)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // 回到前台：页面自己可见即可感知任务状态，停掉后台保活服务
        RemoteMonitorService.stop(this)
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        // 重新扫码更新后（issuedAt 变化）自动换新钥匙重载
        lifecycleScope.launch {
            val record = container.repository.devices.value?.firstOrNull { it.mid == mid }
            if (record != null && boundIssuedAt != 0L && record.issuedAt != boundIssuedAt) {
                hideOverlay()
                loadCurrent()
            }
        }
    }

    override fun onPause() {
        // 开启任务提醒时保持 WebView 运行（ws + 注入脚本持续收事件），否则暂停省电
        if (!RemoteTaskMonitor.isEnabled(this)) {
            webView?.onPause()
        }
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        // 退后台且监听开启：前台服务防止进程被冻结，任务事件才能持续到达
        if (RemoteTaskMonitor.isEnabled(this)) {
            RemoteMonitorService.start(this)
        }
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    private suspend fun bind(record: DeviceRecord) {
        boundIssuedAt = record.issuedAt
        titleView.text = record.name
        container.repository.markOpened(record.mid)
        loadCurrent()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun loadCurrent() {
        val record = container.repository.devices.value?.firstOrNull { it.mid == mid } ?: return
        boundIssuedAt = record.issuedAt
        content.removeAllViews()
        webView?.destroy()
        val url = container.repository.plainUrl(record)
        webView = createWebView(this, record.host).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            loadUrl(url)
        }
        content.addView(webView)
    }

    private fun createWebView(context: Context, deviceHost: String): WebView {
        val web = DeviceWebViewFactory.create(context, mid)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.useWideViewPort = true
        web.settings.loadWithOverviewMode = true
        // 允许 chrome://inspect 远程调试排查页面问题（内部工具，常开）
        WebView.setWebContentsDebuggingEnabled(true)

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val target = request.url
                val scheme = target.scheme?.lowercase()
                return when {
                    scheme == "http" || scheme == "https" ->
                        if (target.host.equals(deviceHost, ignoreCase = true)) {
                            false // 本设备域名内留在 WebView
                        } else {
                            openExternal(context, target)
                        }

                    else -> openExternal(context, target)
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                Log.i(TAG, "page started: $url")
                showProgress()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                Log.i(TAG, "page finished: $url")
                hideProgress()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                Log.w(TAG, "error ${error.errorCode} ${error.description} for ${request.url}")
                if (request.isForMainFrame) {
                    showFailure(webViewErrorFailureOf(error.errorCode, error.description?.toString()))
                }
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                response: WebResourceResponse,
            ) {
                Log.w(TAG, "http ${response.statusCode} for ${request.url}")
                if (request.isForMainFrame) {
                    showFailure(httpFailureOf(response.statusCode))
                }
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.i(
                    TAG,
                    "[js:${message.messageLevel()}] ${message.message()} " +
                        "(${message.sourceId()}:${message.lineNumber()})",
                )
                return true
            }
        }
        // 任务监听：页面里的 WebSocket 观察脚本经此桥回传事件（见 RemoteTaskMonitor）
        web.addJavascriptInterface(
            RemoteTaskMonitor.JsBridge(applicationContext, mid),
            RemoteTaskMonitor.BRIDGE_NAME,
        )

        web.setDownloadListener { link, userAgent, contentDisposition, mimeType, _ ->
            runCatching { enqueueDownload(this@RemoteActivity, link, userAgent, contentDisposition, mimeType) }
        }
        return web
    }

    private fun showProgress() {
        progressBar.visibility = View.VISIBLE
    }

    private fun applyMonitorEnabled(enabled: Boolean) {
        RemoteTaskMonitor.setEnabled(this, enabled)
        renderMonitorButton()
        Toast.makeText(
            this,
            if (enabled) "已开启后台任务提醒（退到后台也会通知）" else "已关闭后台任务提醒",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun renderMonitorButton() {
        monitorButton.setImageResource(
            if (RemoteTaskMonitor.isEnabled(this)) {
                R.drawable.ic_notifications
            } else {
                R.drawable.ic_notifications_off
            },
        )
    }

    private fun onMonitorToggle() {
        if (RemoteTaskMonitor.isEnabled(this)) {
            applyMonitorEnabled(false)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            applyMonitorEnabled(true)
        }
    }

    private fun hideProgress() {
        progressBar.visibility = View.GONE
    }

    private fun hideOverlay() {
        content.children().filter { it.id == R.id.failure_overlay }.forEach { content.removeView(it) }
        hideProgress()
    }

    private fun ViewGroup.children(): List<View> =
        (0 until childCount).map { getChildAt(it) }

    private fun showMissing() {
        titleView.text = "远程控制"
        content.removeAllViews()
        webView = null
        val text = TextView(this).apply {
            text = "设备不存在或已删除"
            setTextColor(0xFFE4E2E6.toInt())
            gravity = Gravity.CENTER
        }
        content.addView(
            text,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
    }

    private fun showFailure(failure: RemoteFailure) {
        val keyInvalid = failure is RemoteFailure.KeyInvalid
        hideProgress()
        content.children().filter { it.id == R.id.failure_overlay }.forEach { content.removeView(it) }

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val overlay = LinearLayout(this).apply {
            id = R.id.failure_overlay
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
            setBackgroundColor(0xF2101014.toInt())
        }
        fun caption(text: String, size: Float, color: Int) = TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
        }
        overlay.addView(
            caption(
                when {
                    keyInvalid -> "🔑 钥匙已失效或无权限"
                    failure is RemoteFailure.Http -> "☁ 服务器错误（${failure.statusCode}）"
                    else -> "☁ 无法连接设备"
                },
                18f,
                0xFFE4E2E6.toInt(),
            ),
        )
        overlay.addView(
            caption(
                when {
                    keyInvalid -> "桌面端可能已刷新过二维码。重新扫码更新钥匙即可恢复访问。"
                    else -> "请确认桌面端 ZCode 正在运行、远程控制已开启，且手机网络可用。"
                },
                14f,
                0xFFC4C6D0.toInt(),
            ).apply { setPadding(0, dp(8), 0, 0) },
        )

        val retry = Button(this).apply { text = "重试" }
        val rescan = Button(this).apply { text = "重新扫码更新钥匙" }
        retry.setOnClickListener {
            hideOverlay()
            loadCurrent()
        }
        rescan.setOnClickListener {
            startActivity(Intent(this, ScanActivity::class.java).putExtra(ScanActivity.EXTRA_FOR_DEVICE, mid))
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, 0)
        }
        buttons.addView(rescan, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        buttons.addView(retry, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        overlay.addView(buttons, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        content.addView(
            overlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun openExternal(context: Context, uri: Uri): Boolean = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(true)

    private fun enqueueDownload(
        context: Context,
        link: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
    ) {
        val request = DownloadManager.Request(Uri.parse(link))
            .setMimeType(mimeType)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                fileNameOf(link, contentDisposition),
            )
        userAgent?.let { request.addRequestHeader("User-Agent", it) }
        CookieManager.getInstance().getCookie(link)?.let { request.addRequestHeader("Cookie", it) }
        (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
    }

    private fun fileNameOf(link: String, contentDisposition: String?): String {
        contentDisposition?.let { cd ->
            val m = Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(cd)
            if (m != null) return m.groupValues[1].trim()
        }
        return Uri.parse(link).lastPathSegment ?: "download"
    }

    companion object {
        private const val TAG = "ZBoxWebView"
        const val EXTRA_MID = "mid"

        fun intent(context: Context, mid: String): Intent =
            Intent(context, RemoteActivity::class.java).putExtra(EXTRA_MID, mid)
    }
}
