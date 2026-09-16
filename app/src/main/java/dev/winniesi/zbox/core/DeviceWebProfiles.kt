package dev.winniesi.zbox.core

/**
 * 每设备独立 WebView Profile 的命名与启用判定（纯逻辑，可单测）。
 * Profile 名只允许字母数字，因此对 mid 做清洗。
 */
object DeviceWebProfiles {

    /** Profile 名长度上限（WebView 实现建议短名称）。 */
    private const val MAX_NAME_LEN = 40

    fun profileNameFor(mid: String): String =
        mid.replace(Regex("[^a-zA-Z0-9]"), "").take(MAX_NAME_LEN).ifEmpty { "device" }

    /** ProfileStore 不可用（WebView < 115 等）时退回默认 profile：功能降级但可用。 */
    fun shouldIsolate(profileStoreSupported: Boolean): Boolean = profileStoreSupported
}
