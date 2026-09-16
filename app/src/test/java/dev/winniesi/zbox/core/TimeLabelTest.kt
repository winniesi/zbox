package dev.winniesi.zbox.core

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeLabelTest {

    private val now = 1_800_000_000_000L

    private fun label(msAgo: Long): String = relativeTime(now - msAgo, now)

    @Test
    fun `future time is recent`() {
        assertEquals("刚刚", relativeTime(now + 5_000, now))
    }

    @Test
    fun `under a minute`() {
        assertEquals("刚刚", label(0))
        assertEquals("刚刚", label(59_999))
    }

    @Test
    fun `minutes`() {
        assertEquals("1 分钟前", label(60_000))
        assertEquals("59 分钟前", label(3_599_999))
    }

    @Test
    fun `hours`() {
        assertEquals("1 小时前", label(3_600_000))
        assertEquals("23 小时前", label(86_400_000 - 1))
    }

    @Test
    fun `days`() {
        assertEquals("1 天前", label(86_400_000))
        assertEquals("29 天前", label(30L * 86_400_000 - 1))
    }

    @Test
    fun `beyond a month falls back to date`() {
        val text = relativeTime(now - 31L * 86_400_000, now, ZoneId.of("UTC"))
        assertTrue(text.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
    }

    @Test
    fun `absolute datetime formats in utc`() {
        assertEquals("1970-01-01 00:00", formatDateTime(0, ZoneId.of("UTC")))
    }
}
