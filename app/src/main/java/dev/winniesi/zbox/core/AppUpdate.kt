package dev.winniesi.zbox.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * GitHub Release 自更新的纯逻辑部分：版本比较 / tag 解析 / APK 资产挑选 /
 * Release JSON 解析，不依赖 Android，可 JVM 单测。
 *
 * 发布约定（winniesi/zbox）：tag 形如 v0.1.3，APK 资产形如 ZBox-v0.1.3-release.apk。
 */
data class AppRelease(
    /** tag 去掉 v 前缀后的版本号，如 "0.1.3"。 */
    val version: String,
    val tag: String,
    val title: String?,
    /** release 描述（Markdown 原文，App 内按纯文本展示）。 */
    val notes: String?,
    val apkUrl: String?,
    val apkName: String?,
    val publishedAt: String?,
)

object AppUpdate {

    private val json = Json { ignoreUnknownKeys = true }

    /** "v0.1.3" -> "0.1.3"；无 v 前缀原样返回。 */
    fun parseTagVersion(tag: String): String = tag.removePrefix("v").removePrefix("V")

    /**
     * candidate 是否比 current 新。按 '.' 分段逐段比较数字，缺失段补 0；
     * 出现非数字段时退化为字符串比较，保证不抛异常。
     */
    fun isNewer(current: String, candidate: String): Boolean {
        val a = current.split('.').map { it.trim() }
        val b = candidate.split('.').map { it.trim() }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { "0" }
            val y = b.getOrElse(i) { "0" }
            val xi = x.toLongOrNull()
            val yi = y.toLongOrNull()
            val c = if (xi != null && yi != null) xi.compareTo(yi) else x.compareTo(y)
            if (c != 0) return c < 0
        }
        return false
    }

    /**
     * 从 (name, url) 资产列表里挑安装包：必须是 .apk，优先名字含 "release"，
     * 排除名字含 "debug" 的构建。
     */
    fun pickApkAsset(assets: List<Pair<String, String>>): Pair<String, String>? {
        val apks = assets.filter { (name, _) ->
            name.endsWith(".apk", ignoreCase = true) && !name.contains("debug", ignoreCase = true)
        }
        return apks.firstOrNull { (name, _) -> name.contains("release", ignoreCase = true) }
            ?: apks.firstOrNull()
    }

    /** 解析 GitHub releases/latest 响应体；无 APK 资产、tag 为空或 JSON 非法时返回 null。 */
    fun parseReleaseJson(body: String): AppRelease? = try {
        val dto = json.decodeFromString<ReleaseDto>(body)
        val apk = pickApkAsset(dto.assets.map { it.name to it.url })
        if (dto.tagName.isBlank() || apk == null) {
            null
        } else {
            AppRelease(
                version = parseTagVersion(dto.tagName),
                tag = dto.tagName,
                title = dto.name,
                notes = dto.body,
                apkUrl = apk.second,
                apkName = apk.first,
                publishedAt = dto.publishedAt,
            )
        }
    } catch (_: Exception) {
        null
    }

    @Serializable
    private data class ReleaseDto(
        @SerialName("tag_name") val tagName: String = "",
        val name: String? = null,
        val body: String? = null,
        @SerialName("published_at") val publishedAt: String? = null,
        val assets: List<AssetDto> = emptyList(),
    )

    @Serializable
    private data class AssetDto(
        val name: String = "",
        @SerialName("browser_download_url") val url: String = "",
    )
}
