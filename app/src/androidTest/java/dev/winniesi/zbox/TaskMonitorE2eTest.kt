package dev.winniesi.zbox

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.winniesi.zbox.platform.DeviceWebViewFactory
import dev.winniesi.zbox.platform.RemoteTaskMonitor
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 必须是 public 类：addJavascriptInterface 只暴露公开类的公开方法。 */
class RecordingBridge {
    val events = CopyOnWriteArrayList<String>()
    private val latch = CountDownLatch(4)

    @JavascriptInterface
    fun postTaskEvent(json: String?) {
        if (json != null) {
            events.add(json)
            latch.countDown()
        }
    }

    fun await() = latch.await(20, TimeUnit.SECONDS)
}

/**
 * 任务监听端到端验证（真实 WebView + 注入脚本 + JS 桥）：
 * MockWebServer 提供测试页与 WebSocket 服务，按官方协议的两种消息形态
 * （扁平 / 嵌套信封）与双层 JSON 字符串发送合成事件，断言桥收到的事件。
 */
@RunWith(AndroidJUnit4::class)
class TaskMonitorE2eTest {

    @Test
    fun injectedScriptScansWebsocketAndCallsBridge() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val bridge = RecordingBridge()

        lateinit var webSocketRef: WebSocket
        val wsOpened = CountDownLatch(1)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.headers?.get("Upgrade")?.contains("websocket", ignoreCase = true) == true) {
                    MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                            webSocketRef = webSocket
                            wsOpened.countDown()
                        }
                    })
                } else {
                    MockResponse().setBody(
                        """
                        <!doctype html><html><body><script>
                        window.ws = new WebSocket('ws://127.0.0.1:${server.port}/ws');
                        </script></body></html>
                        """.trimIndent(),
                    ).setHeader("Content-Type", "text/html")
                }
        }
        server.start()

        lateinit var webView: WebView
        instrumentation.runOnMainSync {
            webView = DeviceWebViewFactory.create(context, "test-mid")
            webView.settings.javaScriptEnabled = true
            webView.addJavascriptInterface(bridge, RemoteTaskMonitor.BRIDGE_NAME)
            webView.loadUrl("http://127.0.0.1:${server.port}/")
        }

        // 注入脚本在页面脚本执行前包裹 WebSocket，服务端此刻开始推协议消息
        assertTrue("ws 未建立", wsOpened.await(15, TimeUnit.SECONDS))
        val ws = webSocketRef

        // 诊断：页面里桥对象的真实状态
        val probe = CountDownLatch(1)
        var probeResult = "unset"
        instrumentation.runOnMainSync {
            webView.evaluateJavascript(
                "(function(){return typeof window.ZBoxNative + '|' + (window.ZBoxNative ? Object.getOwnPropertyNames(window.ZBoxNative).join(',') : 'null')})()",
            ) {
                probeResult = it
                probe.countDown()
            }
        }
        probe.await(5, TimeUnit.SECONDS)
        android.util.Log.i("ZBoxTaskMonitor", "probe=$probeResult")
        // 1. 扁平形态：{type, taskId} 同层
        ws.send("""{"type":"task_complete","taskId":"t-flat","workspacePath":"/home/u/projA"}""")
        // 2. 嵌套信封：taskId/workspacePath 在外层，event 内层（官方广播分发形态）
        ws.send(
            """{"workspacePath":"/home/u/projB","taskId":"t-nested","workspaceIdentity":"wi",""" +
                """"event":{"type":"permission_request"}}""",
        )
        // 3. 双层 JSON 字符串信封
        ws.send("""{"data":"{\"type\":\"elicitation_request\",\"taskId\":\"t-str\"}"}""")
        // 4. usage.delta 累积后随 task_complete 附带模型与 token 数
        ws.send("""{"kind":"usage.delta","modelId":"glm-x","inputTokens":100,"outputTokens":250}""")
        ws.send("""{"type":"task_complete","taskId":"t-usage","workspacePath":"/home/u/projC"}""")
        // 5. 重复事件：3s 去重窗口内不应再次上报
        ws.send("""{"type":"task_complete","taskId":"t-flat","workspacePath":"/home/u/projA"}""")

        assertTrue("20s 内未收齐 4 个桥事件，实际: ${bridge.events}", bridge.await())
        Thread.sleep(1000) // 等可能的重复事件暴露
        val joined = bridge.events.joinToString("\n")
        assertTrue(
            joined,
            joined.contains(
                """{"kind":"task_complete","taskId":"t-flat","workspacePath":"/home/u/projA","inputTokens":0,"outputTokens":0}""",
            ),
        )
        assertTrue(
            joined,
            joined.contains("""{"kind":"permission_request","taskId":"t-nested","workspacePath":"/home/u/projB"}"""),
        )
        assertTrue(
            joined,
            joined.contains("""{"kind":"elicitation_request","taskId":"t-str","workspacePath":""}"""),
        )
        assertTrue(
            joined,
            joined.contains(
                """{"kind":"task_complete","taskId":"t-usage","workspacePath":"/home/u/projC","model":"glm-x","inputTokens":100,"outputTokens":250}""",
            ),
        )
        assertTrue("重复事件未去重", bridge.events.count { it.contains("t-flat") } == 1)

        runCatching { ws.close(1000, "done") }
        instrumentation.runOnMainSync { webView.destroy() }
        server.shutdown()
    }

    @Test
    fun handleEventJsonPostsNotification() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        RemoteTaskMonitor.handleEventJson(
            context,
            "mid-ntf",
            """{"kind":"task_complete","taskId":"ntf-${System.currentTimeMillis()}","model":"glm-x","outputTokens":1234}""",
        )
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        var found = false
        repeat(30) {
            found = nm.activeNotifications.any {
                it.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE) == "✅ 任务完成"
            }
            if (found) return@repeat
            Thread.sleep(100)
        }
        assertTrue(
            "未找到任务完成通知，当前活动通知: " +
                nm.activeNotifications.joinToString { it.notification.extras.toString() },
            found,
        )
    }
}
