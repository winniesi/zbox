package dev.winniesi.zbox.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskEventJsonTest {

    @Test
    fun `解析完整事件`() {
        val event = TaskEventJson.parse(
            """{"kind":"task_complete","taskId":"t-1","workspacePath":"/home/x/proj",
               "model":"glm-4.7","inputTokens":100,"outputTokens":2345,"extra":true}""",
        )
        assertEquals("task_complete", event?.kind)
        assertEquals("t-1", event?.taskId)
        assertEquals("/home/x/proj", event?.workspacePath)
        assertEquals("glm-4.7", event?.model)
        assertEquals(2345L, event?.outputTokens)
    }

    @Test
    fun `未知字段忽略且可选字段缺省`() {
        val event = TaskEventJson.parse("""{"kind":"permission_request","taskId":"t-2","foo":1}""")
        assertEquals("permission_request", event?.kind)
        assertEquals("t-2", event?.taskId)
        assertNull(event?.workspacePath)
        assertEquals(0L, event?.outputTokens)
    }

    @Test
    fun `非法输入返回 null`() {
        assertNull(TaskEventJson.parse("not json"))
        assertNull(TaskEventJson.parse("""{"kind":"","taskId":"t"}"""))
        assertNull(TaskEventJson.parse("""{"kind":"task_complete"}"""))
        assertNull(TaskEventJson.parse("null"))
    }
}
