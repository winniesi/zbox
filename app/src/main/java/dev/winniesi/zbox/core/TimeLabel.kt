package dev.winniesi.zbox.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** "2026-09-01 14:30" 形式的本地时间，用于钥匙生成时间等。 */
fun formatDateTime(unixMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone).format(Instant.ofEpochMilli(unixMs))

/** 相对时间文案（"刚刚 / 5 分钟前 / 3 小时前 / 2 天前 / 2026-09-01"），纯 JVM 可测。 */
fun relativeTime(unixMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    if (unixMs > nowMs) return "刚刚"
    val diffMs = nowMs - unixMs
    val second = 1000L
    val minute = 60L * second
    val hour = 60L * minute
    val day = 24L * hour
    return when {
        diffMs < minute -> "刚刚"
        diffMs < hour -> "${diffMs / minute} 分钟前"
        diffMs < day -> "${diffMs / hour} 小时前"
        diffMs < 30L * day -> "${diffMs / day} 天前"
        else -> DateTimeFormatter.ISO_LOCAL_DATE.withZone(zone).format(Instant.ofEpochMilli(unixMs))
    }
}
