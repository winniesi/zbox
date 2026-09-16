package dev.winniesi.zbox.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLinksTest {

    private val remoteUrl =
        "https://zcode.z.ai/remote/v4?sid=fakeSid&hash=Fake%2FHash%3D&t=1789534959563" +
            "&mid=fakeMid&name=omarchy&app_version=3.11.2"

    @Test
    fun `add link round trips with fully encoded remote url`() {
        val appUrl = AppLinks.buildAddUrl(remoteUrl)
        val parsed = AppLinks.parse(appUrl)
        assertEquals(AppLink.AddDevice(remoteUrl), parsed)
    }

    @Test
    fun `add link with ampersands survives parsing`() {
        val parsed = AppLinks.parse(
            "zcode://device/add?url=https%3A%2F%2Fexample.com%2F%3Fa%3D1%26b%3D2",
        )
        assertEquals(AppLink.AddDevice("https://example.com/?a=1&b=2"), parsed)
    }

    @Test
    fun `open link extracts mid`() {
        assertEquals(AppLink.OpenDevice("abc-123"), AppLinks.parse("zcode://device/open/abc-123"))
        assertEquals(
            AppLink.OpenDevice("11111111-2222"),
            AppLinks.parse(AppLinks.buildOpenUrl("11111111-2222")),
        )
    }

    @Test
    fun `scheme and host are case insensitive`() {
        assertEquals(AppLink.OpenDevice("x"), AppLinks.parse("ZCODE://Device/open/x"))
    }

    @Test
    fun `rejects wrong scheme or host`() {
        assertNull(AppLinks.parse("https://device/add?url=x"))
        assertNull(AppLinks.parse("zcode://other/add?url=x"))
        assertNull(AppLinks.parse("zcode://device/unknown"))
    }

    @Test
    fun `rejects malformed links`() {
        assertNull(AppLinks.parse("zcode://device/add"))
        assertNull(AppLinks.parse("zcode://device/add?url="))
        assertNull(AppLinks.parse("zcode://device/open/"))
        assertNull(AppLinks.parse("not a uri"))
        assertNull(AppLinks.parse(""))
    }
}
