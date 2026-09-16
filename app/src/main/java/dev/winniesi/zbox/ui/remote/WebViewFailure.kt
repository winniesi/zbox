package dev.winniesi.zbox.ui.remote

/**
 * WebView 加载失败的原因分类 + 归类规则。
 * 抽成纯 Kotlin 以便单元测试；错误码取值与 android.webkit.WebViewClient 一致。
 */
sealed interface RemoteFailure {
    /** 钥匙失效 / 无权限：应引导重新扫码。 */
    data object KeyInvalid : RemoteFailure

    /** 远端 HTTP 错误。 */
    data class Http(val statusCode: Int) : RemoteFailure

    /** 网络类失败（连不上、DNS、超时等）。 */
    data class Network(val errorCode: Int, val description: String?) : RemoteFailure
}

/** WebViewClient 错误码（与 android.webkit.WebViewClient 常量一致，便于 JVM 测试）。 */
object WebViewErrorCodes {
    const val UNKNOWN = -1
    const val HOST_LOOKUP = -2
    const val UNSUPPORTED = -3
    const val AUTHENTICATION = -4
    const val PROXY_AUTHENTICATION = -5
    const val CONNECT = -6
    const val IO = -7
    const val TIMEOUT = -8
    const val REDIRECT_LOOP = -9
    const val UNSUPPORTED_SCHEME = -10
    const val FAILED_SSL_HANDSHAKE = -11
    const val BAD_URL = -12
    const val FILE = -13
    const val FILE_NOT_FOUND = -14
    const val TOO_MANY_REQUESTS = -15
}

/** 主帧 HTTP 状态码 → 失败分类。 */
fun httpFailureOf(statusCode: Int): RemoteFailure = when (statusCode) {
    401, 403, 410 -> RemoteFailure.KeyInvalid
    else -> RemoteFailure.Http(statusCode)
}

/** 主帧资源错误码 → 失败分类。 */
fun webViewErrorFailureOf(errorCode: Int, description: String?): RemoteFailure = when (errorCode) {
    WebViewErrorCodes.AUTHENTICATION, WebViewErrorCodes.PROXY_AUTHENTICATION ->
        RemoteFailure.KeyInvalid

    else -> RemoteFailure.Network(errorCode, description)
}
