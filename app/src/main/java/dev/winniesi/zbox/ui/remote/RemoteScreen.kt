package dev.winniesi.zbox.ui.remote

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.KeyOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import dev.winniesi.zbox.core.Freshness
import dev.winniesi.zbox.core.relativeTime
import dev.winniesi.zbox.di.LocalAppContainer

/**
 * M1 远程控制页：用系统 WebView 加载官方远程页面，协议兼容性完全交给官方前端。
 * 失败时按原因分类给出引导（钥匙失效 → 重新扫码；网络类 → 重试 / 检查桌面端在线）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(
    mid: String,
    onRescan: (String) -> Unit,
    onBack: () -> Unit,
) {
    val container = LocalAppContainer.current
    val devices by container.repository.devices.collectAsState()
    val record = devices?.firstOrNull { it.mid == mid }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(record?.name ?: "远程控制") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        when {
            devices == null -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            record == null -> {
                Column(
                    Modifier.fillMaxSize().padding(padding).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(48.dp))
                    Text("设备不存在或已删除", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = onBack) { Text("返回设备列表") }
                }
            }

            else -> RemoteWebView(
                mid = record.mid,
                onRescan = onRescan,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
}

private sealed interface RemoteUiState {
    data object Loading : RemoteUiState
    data object Loaded : RemoteUiState
    data class Failed(val failure: RemoteFailure) : RemoteUiState
}

@Composable
private fun RemoteWebView(
    mid: String,
    onRescan: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val repository = container.repository
    val context = LocalContext.current
    val devices by repository.devices.collectAsState()
    val record = devices?.firstOrNull { it.mid == mid } ?: return

    var uiState by remember { mutableStateOf<RemoteUiState>(RemoteUiState.Loading) }
    var reloadKey by remember { mutableStateOf(0) }
    val nowMs = remember { System.currentTimeMillis() }
    val stale = record.freshness(nowMs) == Freshness.LIKELY_STALE

    // 链接内容（issuedAt 变化 = 重新扫码更新）或"重载"动作变化时重建 WebView
    val webView = remember(mid, record.issuedAt, reloadKey) {
        val url = repository.plainUrl(record)
        createWebView(
            context = context,
            deviceHost = record.host,
            onState = { uiState = it },
        ).apply { loadUrl(url) }
    }

    Column(modifier) {
        if (uiState is RemoteUiState.Loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (stale) {
            StaleKeyBanner(
                issuedLabel = relativeTime(record.issuedAt, nowMs),
                onRescan = { onRescan(mid) },
            )
        }
        Box(Modifier.weight(1f)) {
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
            val failed = (uiState as? RemoteUiState.Failed)?.failure
            if (failed != null) {
                FailureOverlay(
                    failure = failed,
                    onRetry = { reloadKey += 1 },
                    onRescan = { onRescan(mid) },
                )
            }
        }
    }

    BackHandler {
        if (webView.canGoBack()) webView.goBack() else onBack()
    }

    androidx.compose.runtime.DisposableEffect(webView) {
        onDispose { webView.destroy() }
    }
}

/** 记录"最近一次状态"，供 onPageFinished 判断是否被失败覆盖。 */
private class UiTracker {
    var last: RemoteUiState = RemoteUiState.Loading
}

@SuppressLint("SetJavaScriptEnabled")
private fun createWebView(
    context: Context,
    deviceHost: String,
    onState: (RemoteUiState) -> Unit,
): WebView {
    val tracker = UiTracker()
    fun update(state: RemoteUiState) {
        tracker.last = state
        onState(state)
    }
    return WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true

        webViewClient = object : WebViewClient() {
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
                            openExternal(context, target) // 其余链接交给系统浏览器
                        }

                    else -> openExternal(context, target) // mailto / intent 等交给系统
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                update(RemoteUiState.Loading)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (tracker.last !is RemoteUiState.Failed) update(RemoteUiState.Loaded)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (request.isForMainFrame) {
                    update(
                        RemoteUiState.Failed(
                            webViewErrorFailureOf(error.errorCode, error.description?.toString()),
                        ),
                    )
                }
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                response: WebResourceResponse,
            ) {
                if (request.isForMainFrame) {
                    update(RemoteUiState.Failed(httpFailureOf(response.statusCode)))
                }
            }
        }

        setDownloadListener { link, userAgent, contentDisposition, mimeType, _ ->
            runCatching { enqueueDownload(context, link, userAgent, contentDisposition, mimeType) }
        }
    }
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

@Composable
private fun FailureOverlay(
    failure: RemoteFailure,
    onRetry: () -> Unit,
    onRescan: () -> Unit,
) {
    val keyInvalid = failure is RemoteFailure.KeyInvalid
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.93f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                if (keyInvalid) Icons.Outlined.KeyOff else Icons.Outlined.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                when {
                    keyInvalid -> "钥匙已失效或无权限"
                    failure is RemoteFailure.Http -> "服务器错误（${failure.statusCode}）"
                    else -> "无法连接设备"
                },
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    keyInvalid -> "桌面端可能已刷新过二维码。重新扫码更新钥匙即可恢复访问。"
                    else -> "请确认桌面端 ZCode 正在运行、远程控制已开启，且手机网络可用。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            if (keyInvalid) {
                Button(onClick = onRescan) { Text("重新扫码更新钥匙") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onRetry) { Text("重试") }
            } else {
                Button(onClick = onRetry) { Text("重试") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onRescan) { Text("重新扫码更新钥匙") }
            }
        }
    }
}

@Composable
private fun StaleKeyBanner(issuedLabel: String, onRescan: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "钥匙生成于 $issuedLabel，可能已被桌面端刷新作废",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRescan) { Text("重新扫码") }
        }
    }
}
