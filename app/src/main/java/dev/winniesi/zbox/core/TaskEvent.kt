package dev.winniesi.zbox.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 远程页 WebSocket 里扫到的任务生命周期事件（由注入脚本从协议消息中提取，
 * 字段做尽力而为的可选处理——桌面端协议演进时未知字段直接忽略）。
 */
@Serializable
data class TaskEvent(
    /** task_complete / task_error / task_warning / permission_request */
    val kind: String,
    val taskId: String,
    val workspacePath: String? = null,
    val error: String? = null,
    val model: String? = null,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
)

object TaskEventJson {

    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String): TaskEvent? = try {
        val event = json.decodeFromString<TaskEvent>(raw)
        if (event.kind.isBlank() || event.taskId.isBlank()) null else event
    } catch (_: Exception) {
        null
    }
}
