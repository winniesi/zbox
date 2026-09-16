package dev.winniesi.zbox.core

import java.net.URI
import java.net.URLDecoder

/** App 自身的深链：扫码绑定设备、快捷方式直达设备。 */
sealed interface AppLink {
    /** zcode://device/add?url=<编码后的远程链接> */
    data class AddDevice(val url: String) : AppLink

    /** zcode://device/open/<mid> */
    data class OpenDevice(val mid: String) : AppLink
}

object AppLinks {
    const val SCHEME = "zcode"
    const val HOST = "device"
    private const val PATH_ADD = "/add"
    private const val PATH_OPEN_PREFIX = "/open/"

    /** 宽松解析：scheme/host 不匹配或结构不对时返回 null，调用方忽略即可。 */
    fun parse(uriString: String): AppLink? {
        val trimmed = uriString.trim()
        if (trimmed.isEmpty()) return null
        val uri = try {
            URI(trimmed)
        } catch (e: Exception) {
            return null
        }
        if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return null
        if (!uri.host.equals(HOST, ignoreCase = true)) return null
        val path = uri.path ?: return null

        return when {
            path == PATH_ADD -> {
                // 用 rawQuery 自己 split+decode，避免 %26 这类编码在 URI.getQuery 中被提前解开
                val rawQuery = uri.rawQuery ?: return null
                var url: String? = null
                for (pair in rawQuery.split('&')) {
                    if (pair.isEmpty()) continue
                    val idx = pair.indexOf('=')
                    val key = if (idx < 0) pair else pair.substring(0, idx)
                    if (decode(key) == "url") {
                        url = decode(if (idx < 0) "" else pair.substring(idx + 1))
                        break
                    }
                }
                url?.takeIf { it.isNotBlank() }?.let { AppLink.AddDevice(it) }
            }

            path.startsWith(PATH_OPEN_PREFIX) -> {
                val mid = path.removePrefix(PATH_OPEN_PREFIX)
                mid.takeIf { it.isNotBlank() }?.let { AppLink.OpenDevice(it) }
            }

            else -> null
        }
    }

    fun buildAddUrl(remoteUrl: String): String {
        val encoded = java.net.URLEncoder.encode(remoteUrl, Charsets.UTF_8).replace("+", "%20")
        return "$SCHEME://$HOST$PATH_ADD?url=$encoded"
    }

    fun buildOpenUrl(mid: String): String = "$SCHEME://$HOST$PATH_OPEN_PREFIX$mid"

    private fun decode(s: String): String = try {
        URLDecoder.decode(s, Charsets.UTF_8)
    } catch (e: Exception) {
        s
    }
}
