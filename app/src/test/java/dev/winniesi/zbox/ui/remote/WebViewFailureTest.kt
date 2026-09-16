package dev.winniesi.zbox.ui.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class WebViewFailureTest {

    @Test
    fun `auth http codes map to key invalid`() {
        assertEquals(RemoteFailure.KeyInvalid, httpFailureOf(401))
        assertEquals(RemoteFailure.KeyInvalid, httpFailureOf(403))
        assertEquals(RemoteFailure.KeyInvalid, httpFailureOf(410))
    }

    @Test
    fun `other http codes stay http`() {
        assertEquals(RemoteFailure.Http(500), httpFailureOf(500))
        assertEquals(RemoteFailure.Http(404), httpFailureOf(404))
        assertEquals(RemoteFailure.Http(429), httpFailureOf(429))
    }

    @Test
    fun `webview auth errors map to key invalid`() {
        assertEquals(
            RemoteFailure.KeyInvalid,
            webViewErrorFailureOf(WebViewErrorCodes.AUTHENTICATION, null),
        )
        assertEquals(
            RemoteFailure.KeyInvalid,
            webViewErrorFailureOf(WebViewErrorCodes.PROXY_AUTHENTICATION, null),
        )
    }

    @Test
    fun `network errors keep code and description`() {
        val failure = webViewErrorFailureOf(WebViewErrorCodes.HOST_LOOKUP, "dns 失败")
        assertEquals(RemoteFailure.Network(WebViewErrorCodes.HOST_LOOKUP, "dns 失败"), failure)
        assertEquals(
            RemoteFailure.Network(WebViewErrorCodes.TIMEOUT, null),
            webViewErrorFailureOf(WebViewErrorCodes.TIMEOUT, null),
        )
    }
}
