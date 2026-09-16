package dev.winniesi.zbox.core

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * ZCode 桌面端「远程控制」链接：
 * https://zcode.z.ai/remote/v4?sid=..&hash=..&t=..&mid=..&name=..&app_version=..
 *
 * 链接本身等同临时钥匙：hash 是敏感凭证，调用方必须加密存储。
 */
data class RemoteLink(
    val scheme: String,
    val host: String,
    val path: String,
    val sid: String,
    val hash: String,
    val t: Long,
    val mid: String,
    val name: String?,
    val appVersion: String?,
) {
    /** 重建为可直接加载的 URL；query 参数做 URL 编码（空格用 %20）。 */
    fun toUrlString(): String {
        val q = StringBuilder()
        fun append(key: String, value: String) {
            if (q.isNotEmpty()) q.append('&')
            q.append(key).append('=').append(encodeQueryValue(value))
        }
        append("sid", sid)
        append("hash", hash)
        append("t", t.toString())
        append("mid", mid)
        name?.let { append("name", it) }
        appVersion?.let { append("app_version", it) }
        val p = if (path.isEmpty()) "/" else path
        return "$scheme://$host$p?$q"
    }

    companion object {
        const val DEFAULT_HOST = "zcode.z.ai"

        private fun encodeQueryValue(value: String): String =
            URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
    }
}

/** 把任意粘贴文本 / 扫码内容解析为 [RemoteLink]，失败给出原因。 */
class RemoteLinkParser {

    sealed interface Result {
        data class Ok(val link: RemoteLink) : Result
        data class Invalid(val reason: String) : Result
    }

    fun parse(raw: String): Result {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Result.Invalid("内容为空")
        val uri = try {
            URI(trimmed)
        } catch (e: Exception) {
            return Result.Invalid("不是有效的 URL：${e.message ?: "解析失败"}")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return Result.Invalid("仅支持 http/https 链接")
        val host = uri.host
        if (host.isNullOrBlank()) return Result.Invalid("缺少主机地址")

        val rawQuery = uri.rawQuery ?: return Result.Invalid("缺少查询参数（sid/hash/t/mid）")
        val params = linkedMapOf<String, String>()
        for (pair in rawQuery.split('&')) {
            if (pair.isEmpty()) continue
            val idx = pair.indexOf('=')
            val key = if (idx < 0) pair else pair.substring(0, idx)
            val value = if (idx < 0) "" else pair.substring(idx + 1)
            params[decode(key)] = decode(value)
        }

        val sid = params["sid"]
        if (sid.isNullOrBlank()) return Result.Invalid("缺少参数 sid")
        val hash = params["hash"]
        if (hash.isNullOrBlank()) return Result.Invalid("缺少参数 hash")
        val mid = params["mid"]
        if (mid.isNullOrBlank()) return Result.Invalid("缺少参数 mid")
        val tRaw = params["t"]
        if (tRaw.isNullOrBlank()) return Result.Invalid("缺少参数 t")
        val t = tRaw.toLongOrNull() ?: return Result.Invalid("参数 t 不是有效时间戳")

        return Result.Ok(
            RemoteLink(
                scheme = scheme,
                host = host,
                path = uri.path ?: "",
                sid = sid,
                hash = hash,
                t = t,
                mid = mid,
                name = params["name"]?.takeIf { it.isNotBlank() },
                appVersion = params["app_version"]?.takeIf { it.isNotBlank() },
            ),
        )
    }

    private fun decode(s: String): String = try {
        URLDecoder.decode(s, Charsets.UTF_8)
    } catch (e: Exception) {
        s
    }
}
