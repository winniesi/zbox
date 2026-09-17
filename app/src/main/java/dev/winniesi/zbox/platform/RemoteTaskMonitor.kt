package dev.winniesi.zbox.platform

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.webkit.WebView
import androidx.core.app.NotificationCompat
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.winniesi.zbox.RemoteActivity
import dev.winniesi.zbox.core.TaskEvent
import dev.winniesi.zbox.core.TaskEventJson

/**
 * 远程任务监听：向官方远程页注入 document-start 脚本，包裹页面 WebSocket，
 * 对中继消息做只读深扫描，提取任务生命周期事件（task_complete / task_error /
 * task_warning / permission_request / elicitation_request）与 usage.delta 里的
 * 模型、token 用量，经 addJavascriptInterface 回传原生层发本地通知。
 *
 * 会提醒的事件：完成 / 出错 / 等待审批（工具权限）/ 等待回答（agent 向用户
 * 提问）。task_warning 等其余事件只记日志，避免打扰。
 *
 * 防兼容性原则（README「不解析协议」的让步说明）：脚本对所有消息只读、
 * 深度受限、任何解析失败静默忽略；只识别已知事件类型，ZCode 升级新增
 * 字段/事件不影响页面本身运行，最坏情况是「收不到通知」。
 *
 * 后台可行性：App 退后台后 WebView 不调用 onPause（保持 JS 与 ws 活着），
 * 配合前台服务 [RemoteMonitorService] 防止进程被缓存冻结——进程被杀则
 * 监听自然终止（产品上的既定约束）。
 */
object RemoteTaskMonitor {

    const val BRIDGE_NAME = "ZBoxNative"

    private const val TAG = "ZBoxTaskMonitor"
    private const val PREFS = "task_monitor"
    private const val KEY_ENABLED = "enabled"
    private const val CHANNEL_EVENTS = "task_events"
    private const val DEDUP_WINDOW_MS = 60_000L

    /** 注入页面的 WebSocket 观察脚本。机制见类注释；只在内存里统计，不改写任何消息。 */
    val START_SCRIPT = """
(function () {
  if (window.__ZBOX_TASK_MONITOR__) return;
  window.__ZBOX_TASK_MONITOR__ = true;
  var Native = window.ZBoxNative;
  if (!Native || !Native.postTaskEvent) return;
  var TASK_KINDS = {
    task_complete: 1, task_error: 1, task_warning: 1,
    permission_request: 1, elicitation_request: 1
  };
  var recent = {};
  var usage = { model: '', inputTokens: 0, outputTokens: 0 };
  function send(obj) { try { Native.postTaskEvent(JSON.stringify(obj)); } catch (e) {} }
  function scan(node, depth, ctx) {
    if (!node || typeof node !== 'object' || depth > 6) return;
    if (Array.isArray(node)) {
      for (var i = 0; i < node.length && i < 64; i++) scan(node[i], depth + 1, ctx);
      return;
    }
    var kind = node.type || node.kind;
    if (typeof kind === 'string') {
      // taskId / workspacePath 可能在外层信封上：向下递归时继承最近一次出现
      if (typeof node.workspacePath === 'string') ctx = { ws: node.workspacePath, task: ctx.task };
      if (typeof node.taskId === 'string' && node.taskId) ctx = { ws: ctx.ws, task: node.taskId };
      if (kind === 'usage.delta' && typeof node.outputTokens === 'number') {
        if (typeof node.modelId === 'string' && node.modelId) usage.model = node.modelId;
        usage.inputTokens += node.inputTokens | 0;
        usage.outputTokens += node.outputTokens | 0;
      } else if (TASK_KINDS[kind]) {
        var id = node.taskId || node.sessionId || (ctx && ctx.task) || node.requestId;
        if (typeof id === 'string' && id) {
          var t = Date.now();
          if (t - (recent[kind + ':' + id] || 0) > 3000) {
            recent[kind + ':' + id] = t;
            var payload = { kind: kind, taskId: id, workspacePath: (ctx && ctx.ws) || '' };
            if (kind === 'task_error' || kind === 'task_warning') payload.error = String(node.error || node.warning || node.detail || '');
            if (kind === 'task_complete') {
              if (usage.model) payload.model = usage.model;
              payload.inputTokens = usage.inputTokens;
              payload.outputTokens = usage.outputTokens;
              usage.inputTokens = 0; usage.outputTokens = 0;
            }
            send(payload);
          }
        }
      }
    }
    for (var k in node) {
      if (Object.prototype.hasOwnProperty.call(node, k)) scan(node[k], depth + 1, ctx);
    }
  }
  var NativeWS = window.WebSocket;
  function HookedWS(url, protocols) {
    var ws = protocols === undefined ? new NativeWS(url) : new NativeWS(url, protocols);
    ws.addEventListener('message', function (ev) {
      try {
        if (typeof ev.data !== 'string' || ev.data.length > 2000000) return;
        scan(JSON.parse(ev.data), 0, { ws: '', task: '' });
      } catch (e) {}
    });
    return ws;
  }
  try {
    HookedWS.prototype = NativeWS.prototype;
    HookedWS.CONNECTING = NativeWS.CONNECTING;
    HookedWS.OPEN = NativeWS.OPEN;
    HookedWS.CLOSING = NativeWS.CLOSING;
    HookedWS.CLOSED = NativeWS.CLOSED;
    window.WebSocket = HookedWS;
  } catch (e) { window.__ZBOX_TASK_MONITOR__ = false; }
})();
    """.trimIndent()

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, value).apply()
    }

    /**
     * 给 WebView 装上 document-start 脚本（页面脚本执行前生效）。
     * 必须在首次 loadUrl 前调用；API 不可用时静默降级为「无监听」。
     */
    fun installStartScript(webView: WebView) {
        runCatching {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                Log.w(TAG, "DOCUMENT_START_SCRIPT unsupported, task monitor disabled")
                return
            }
            WebViewCompat.addDocumentStartJavaScript(webView, START_SCRIPT, setOf("*"))
        }.onFailure { Log.w(TAG, "install start script failed: ${it.message}") }
    }

    /** 页面通过 JS 桥回传的事件入口；mid 用于通知点击跳回对应远程页。 */
    fun handleEventJson(context: Context, mid: String, raw: String) {
        val event = TaskEventJson.parse(raw) ?: return
        Log.i(TAG, "task event mid=$mid $raw")
        when (event.kind) {
            "task_complete" -> notify(context, mid, event)
            "task_error" -> notify(context, mid, event)
            "permission_request" -> notify(context, mid, event)
            "elicitation_request" -> notify(context, mid, event)
            else -> Unit // task_warning 等只记日志，避免打扰
        }
    }

    /** 暴露给页面的 JS 桥（addJavascriptInterface，仅此一个方法可见）。 */
    class JsBridge(private val appContext: Context, private val mid: String) {

        @android.webkit.JavascriptInterface
        fun postTaskEvent(json: String?) {
            if (json.isNullOrBlank()) return
            runCatching { handleEventJson(appContext, mid, json) }
        }
    }

    private val recentNotified = HashMap<String, Long>()

    private fun notify(context: Context, mid: String, event: TaskEvent) {
        val now = System.currentTimeMillis()
        val key = "${event.kind}:${event.taskId}"
        synchronized(recentNotified) {
            if (now - (recentNotified[key] ?: 0) < DEDUP_WINDOW_MS) return
            recentNotified[key] = now
            if (recentNotified.size > 256) recentNotified.clear()
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "notification permission not granted, skip")
            return
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_EVENTS, "远程任务提醒", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val tap = PendingIntent.getActivity(
            context,
            (mid + event.taskId).hashCode(),
            RemoteActivity.intent(context, mid),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val workspace = event.workspacePath?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        val title: String
        val text: String
        when (event.kind) {
            "task_error" -> {
                title = "❌ 任务出错"
                text = event.error?.take(120)?.trim().orEmpty().ifBlank { "远程任务执行失败" }
            }
            "permission_request" -> {
                title = "⏸ 任务等待审批"
                text = "有任务请求权限，批准后才能继续"
            }
            "elicitation_request" -> {
                title = "❓ 任务等待回答"
                text = "有任务在等你回复，回去继续对话"
            }
            else -> {
                title = "✅ 任务完成"
                val detail = buildList {
                    workspace?.let { add(it) }
                    event.model?.let { add(it) }
                    if (event.outputTokens > 0) add("输出 ${formatTokens(event.outputTokens)} tokens")
                }
                text = detail.joinToString(" · ").ifBlank { "远程任务已完成" }
            }
        }
        val notification: Notification = NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        nm.notify(event.taskId.hashCode(), notification)
    }

    private fun formatTokens(v: Long): String =
        if (v >= 10_000) "%.1fk".format(v / 1000f) else v.toString()
}
