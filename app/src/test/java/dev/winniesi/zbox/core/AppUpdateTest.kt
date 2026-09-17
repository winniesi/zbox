package dev.winniesi.zbox.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {

    @Test
    fun `tag 解析去掉 v 前缀`() {
        assertEquals("0.1.3", AppUpdate.parseTagVersion("v0.1.3"))
        assertEquals("0.1.3", AppUpdate.parseTagVersion("V0.1.3"))
        assertEquals("0.1.3", AppUpdate.parseTagVersion("0.1.3"))
    }

    @Test
    fun `版本比较逐段数字比较`() {
        assertTrue(AppUpdate.isNewer("0.1.2", "0.1.3"))
        assertTrue(AppUpdate.isNewer("0.1.2", "0.2.0"))
        assertTrue(AppUpdate.isNewer("0.9.9", "1.0.0"))
        assertTrue(AppUpdate.isNewer("1.0", "1.0.1")) // 缺失段补 0
        assertFalse(AppUpdate.isNewer("0.1.2", "0.1.2"))
        assertFalse(AppUpdate.isNewer("0.1.3", "0.1.2"))
        assertFalse(AppUpdate.isNewer("1.0.0", "1.0")) // 补 0 后相等
    }

    @Test
    fun `资产挑选优先 release 排除 debug`() {
        val assets = listOf(
            "ZBox-v0.1.3-debug.apk" to "https://x/debug.apk",
            "sources.jar" to "https://x/sources.jar",
            "ZBox-v0.1.3-release.apk" to "https://x/release.apk",
        )
        assertEquals(
            "ZBox-v0.1.3-release.apk" to "https://x/release.apk",
            AppUpdate.pickApkAsset(assets),
        )
    }

    @Test
    fun `资产挑选没有 release 时取普通 apk`() {
        val assets = listOf(
            "notes.txt" to "https://x/notes.txt",
            "ZBox-v0.1.3.apk" to "https://x/plain.apk",
        )
        assertEquals("ZBox-v0.1.3.apk" to "https://x/plain.apk", AppUpdate.pickApkAsset(assets))
    }

    @Test
    fun `Release JSON 解析`() {
        val json = """
            {
              "tag_name": "v0.1.3",
              "name": "ZBox v0.1.3",
              "body": "修复若干问题",
              "published_at": "2026-09-17T00:00:00Z",
              "assets": [
                {"name": "ZBox-v0.1.3-release.apk", "browser_download_url": "https://x/apk"},
                {"name": "notes.txt", "browser_download_url": "https://x/notes"}
              ],
              "unknown_field": 1
            }
        """.trimIndent()
        val release = AppUpdate.parseReleaseJson(json)
        assertEquals("0.1.3", release?.version)
        assertEquals("https://x/apk", release?.apkUrl)
        assertEquals("ZBox-v0.1.3-release.apk", release?.apkName)
        assertEquals("修复若干问题", release?.notes)
    }

    @Test
    fun `非法 JSON 返回 null`() {
        assertNull(AppUpdate.parseReleaseJson("not json"))
        assertNull(AppUpdate.parseReleaseJson("{}")) // 无资产
    }
}
