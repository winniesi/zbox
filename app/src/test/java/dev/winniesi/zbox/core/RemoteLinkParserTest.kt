package dev.winniesi.zbox.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteLinkParserTest {

    private val parser = RemoteLinkParser()

    // 真实形状的链接（hash 含 %2F 与 %3D 编码）
    private val realUrl =
        "https://zcode.z.ai/remote/v4?sid=d_FakeSidForUnitTests" +
            "&hash=FakeHashA%2FFakeHashB%2BFakeHashC%3D" +
            "&t=1789534959563" +
            "&mid=11111111-2222-4333-8444-555555555555" +
            "&name=omarchy" +
            "&app_version=3.11.2"

    @Test
    fun `parses real world url`() {
        val ok = parser.parse(realUrl) as RemoteLinkParser.Result.Ok
        val link = ok.link
        assertEquals("https", link.scheme)
        assertEquals("zcode.z.ai", link.host)
        assertEquals("/remote/v4", link.path)
        assertEquals("d_FakeSidForUnitTests", link.sid)
        assertEquals("FakeHashA/FakeHashB+FakeHashC=", link.hash)
        assertEquals(1789534959563L, link.t)
        assertEquals("11111111-2222-4333-8444-555555555555", link.mid)
        assertEquals("omarchy", link.name)
        assertEquals("3.11.2", link.appVersion)
    }

    @Test
    fun `rebuilt url round trips through parser`() {
        val first = parser.parse(realUrl) as RemoteLinkParser.Result.Ok
        val rebuilt = first.link.toUrlString()
        val second = parser.parse(rebuilt) as RemoteLinkParser.Result.Ok
        assertEquals(first.link, second.link)
    }

    @Test
    fun `rebuilt url encodes special chars in hash`() {
        val link = (parser.parse(realUrl) as RemoteLinkParser.Result.Ok).link
        val url = link.toUrlString()
        assertTrue(url.contains("hash=FakeHashA%2FFakeHashB%2BFakeHashC%3D"))
        assertTrue(url.startsWith("https://zcode.z.ai/remote/v4?"))
    }

    @Test
    fun `missing sid is invalid`() {
        val url = realUrl.replaceFirst("sid=d_FakeSidForUnitTests&", "")
        val result = parser.parse(url)
        assertTrue(result is RemoteLinkParser.Result.Invalid)
        assertTrue((result as RemoteLinkParser.Result.Invalid).reason.contains("sid"))
    }

    @Test
    fun `missing hash is invalid`() {
        val url = realUrl.replace(Regex("&hash=[^&]+"), "")
        assertTrue(parser.parse(url) is RemoteLinkParser.Result.Invalid)
    }

    @Test
    fun `missing t is invalid`() {
        val url = realUrl.replace(Regex("&t=\\d+"), "")
        assertTrue(parser.parse(url) is RemoteLinkParser.Result.Invalid)
    }

    @Test
    fun `non numeric t is invalid`() {
        val url = realUrl.replace("t=1789534959563", "t=abc")
        assertTrue(parser.parse(url) is RemoteLinkParser.Result.Invalid)
    }

    @Test
    fun `missing mid is invalid`() {
        val url = realUrl.replace(Regex("&mid=[^&]+"), "")
        assertTrue(parser.parse(url) is RemoteLinkParser.Result.Invalid)
    }

    @Test
    fun `garbage input is invalid`() {
        assertTrue(parser.parse("") is RemoteLinkParser.Result.Invalid)
        assertTrue(parser.parse("   ") is RemoteLinkParser.Result.Invalid)
        assertTrue(parser.parse("hello world") is RemoteLinkParser.Result.Invalid)
        assertTrue(parser.parse("ftp://zcode.z.ai/remote/v4?sid=1&hash=2&t=3&mid=4") is RemoteLinkParser.Result.Invalid)
    }

    @Test
    fun `http scheme is accepted and host preserved`() {
        val url = "http://192.168.1.5:8080/remote/v4?sid=s&hash=h&t=1&mid=m"
        val link = (parser.parse(url) as RemoteLinkParser.Result.Ok).link
        assertEquals("http", link.scheme)
        assertEquals("192.168.1.5", link.host)
        assertEquals("/remote/v4", link.path)
    }

    @Test
    fun `unknown extra params are ignored`() {
        val url = realUrl + "&foo=bar&baz=1"
        assertTrue(parser.parse(url) is RemoteLinkParser.Result.Ok)
    }

    @Test
    fun `blank name decodes to null`() {
        val url = "https://zcode.z.ai/remote/v4?sid=s&hash=h&t=1&mid=m&name="
        val link = (parser.parse(url) as RemoteLinkParser.Result.Ok).link
        assertEquals(null, link.name)
    }

    @Test
    fun `name and unicode values are decoded`() {
        val url = "https://zcode.z.ai/remote/v4?sid=s&hash=h&t=1&mid=m&name=%20dev%20%E4%B8%AD%E6%96%87"
        val link = (parser.parse(url) as RemoteLinkParser.Result.Ok).link
        assertEquals(" dev 中文", link.name)
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        val ok = parser.parse("  $realUrl\n ") as RemoteLinkParser.Result.Ok
        assertEquals("zcode.z.ai", ok.link.host)
    }
}
