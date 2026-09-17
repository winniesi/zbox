package dev.winniesi.zbox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * 远程页后台监听的前台服务：App 退到后台时保住进程不被缓存冻结，
 * 远程页 WebView 的 WebSocket（与注入的观察脚本）才能持续收到任务事件。
 * 回到远程页或页面销毁时停止。进程被用户/系统杀死后监听自然结束。
 */
class RemoteMonitorService : Service() {

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "后台监听", NotificationManager.IMPORTANCE_LOW),
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("ZBox 后台监听中")
            .setContentText("远程任务完成时会通知你")
            .setOngoing(true)
            .build()
        runCatching { startForeground(NOTIFICATION_ID, notification) }
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    companion object {
        private const val CHANNEL_ID = "task_monitor_service"
        private const val NOTIFICATION_ID = 41

        /** 从 RemoteActivity.onStop 调用（离开前台前的短暂窗口内允许启动前台服务）。 */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, RemoteMonitorService::class.java))
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RemoteMonitorService::class.java))
        }
    }
}
