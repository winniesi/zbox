package dev.winniesi.zbox.core

import kotlinx.serialization.Serializable

/**
 * 一台受管 ZCode 桌面设备。"设备"长期存在，链接里的钥匙会因桌面端刷新二维码而失效，
 * 因此 mid 是设备主键，sid/hash/t 可通过重新扫码整体更新。
 *
 * @property hashStored 加密后的 hash 凭证（由 [SecretCipher] 处理）。
 * @property customName 用户手动改过名后，后续扫码更新不再覆盖该名称。
 */
data class DeviceRecord(
    val mid: String,
    val name: String,
    val host: String,
    val path: String,
    val sid: String,
    val hashStored: String,
    val issuedAt: Long,
    val appVersion: String?,
    val addedAt: Long,
    val lastOpenedAt: Long? = null,
    val customName: Boolean = false,
) {
    /** 用明文 hash 重建可加载的远程链接。 */
    fun link(hashPlain: String): RemoteLink = RemoteLink(
        scheme = "https",
        host = host,
        path = path,
        sid = sid,
        hash = hashPlain,
        t = issuedAt,
        mid = mid,
        name = null,
        appVersion = appVersion,
    )
}

// ---- 持久化 DTO（DataStore 中存 JSON） ----

@Serializable
data class DeviceDto(
    val mid: String,
    val name: String,
    val host: String,
    val path: String,
    val sid: String,
    val hashStored: String,
    val issuedAt: Long,
    val appVersion: String? = null,
    val addedAt: Long,
    val lastOpenedAt: Long? = null,
    val customName: Boolean = false,
)

@Serializable
data class DeviceBookDto(
    val version: Int = 1,
    val devices: List<DeviceDto> = emptyList(),
)

fun DeviceRecord.toDto(): DeviceDto = DeviceDto(
    mid = mid,
    name = name,
    host = host,
    path = path,
    sid = sid,
    hashStored = hashStored,
    issuedAt = issuedAt,
    appVersion = appVersion,
    addedAt = addedAt,
    lastOpenedAt = lastOpenedAt,
    customName = customName,
)

fun DeviceDto.toRecord(): DeviceRecord = DeviceRecord(
    mid = mid,
    name = name,
    host = host,
    path = path,
    sid = sid,
    hashStored = hashStored,
    issuedAt = issuedAt,
    appVersion = appVersion,
    addedAt = addedAt,
    lastOpenedAt = lastOpenedAt,
    customName = customName,
)

/** 列表展示排序：最近打开的在前，其次按添加时间新→旧。 */
fun sortedForDisplay(devices: List<DeviceRecord>): List<DeviceRecord> =
    devices.sortedWith(
        compareByDescending<DeviceRecord> { it.lastOpenedAt ?: Long.MIN_VALUE }
            .thenByDescending { it.addedAt },
    )
