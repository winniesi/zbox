package dev.winniesi.zbox.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 扫码 / 粘贴链接落库的结果。 */
sealed interface AddResult {
    data class Added(val record: DeviceRecord) : AddResult
    data class Updated(val record: DeviceRecord) : AddResult
}

/**
 * 设备仓库：所有业务规则集中在这里（按 mid 去重、钥匙更新、命名策略、加密落盘），
 * UI 层只做展示，便于纯 JVM 单元测试。
 */
class DeviceRepository(
    private val store: DeviceStore,
    private val cipher: SecretCipher,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val _devices = MutableStateFlow<List<DeviceRecord>?>(null)

    /** null 表示尚未加载完成。 */
    val devices: StateFlow<List<DeviceRecord>?> = _devices.asStateFlow()

    suspend fun refresh(): List<DeviceRecord> = mutex.withLock {
        val loaded = _devices ?: store.load().also { _devices.value = it }
        loaded
    }

    /**
     * 用解析出的链接新增或更新设备。
     * @param displayName 用户显式输入的名称，优先级高于链接里的 name 参数。
     */
    suspend fun addOrUpdateFromLink(link: RemoteLink, displayName: String? = null): AddResult =
        mutex.withLock {
            val current = _devices ?: store.load().also { _devices.value = it }
            val existing = current.firstOrNull { it.mid == link.mid }
            val ts = now()
            val record = if (existing == null) {
                DeviceRecord(
                    mid = link.mid,
                    name = displayName ?: link.name ?: DEFAULT_NAME,
                    host = link.host,
                    path = link.path.ifEmpty { "/" },
                    sid = link.sid,
                    hashStored = cipher.encrypt(link.hash),
                    issuedAt = link.t,
                    appVersion = link.appVersion,
                    addedAt = ts,
                )
            } else {
                existing.copy(
                    name = when {
                        existing.customName -> existing.name
                        displayName != null -> displayName
                        link.name != null -> link.name
                        else -> existing.name
                    },
                    customName = existing.customName || displayName != null,
                    host = link.host,
                    path = link.path.ifEmpty { "/" },
                    sid = link.sid,
                    hashStored = cipher.encrypt(link.hash),
                    issuedAt = link.t,
                    appVersion = link.appVersion,
                )
            }
            val next = current.filterNot { it.mid == record.mid } + record
            store.save(next)
            _devices.value = next
            if (existing == null) AddResult.Added(record) else AddResult.Updated(record)
        }

    suspend fun rename(mid: String, newName: String): Unit = mutex.withLock {
        val current = _devices ?: store.load().also { _devices.value = it }
        val next = current.map {
            if (it.mid == mid && newName.isNotBlank()) it.copy(name = newName.trim(), customName = true) else it
        }
        store.save(next)
        _devices.value = next
    }

    suspend fun remove(mid: String): Unit = mutex.withLock {
        val current = _devices ?: store.load().also { _devices.value = it }
        val next = current.filterNot { it.mid == mid }
        store.save(next)
        _devices.value = next
    }

    suspend fun markOpened(mid: String): Unit = mutex.withLock {
        val current = _devices ?: store.load().also { _devices.value = it }
        val ts = now()
        val next = current.map { if (it.mid == mid) it.copy(lastOpenedAt = ts) else it }
        store.save(next)
        _devices.value = next
    }

    /** 是否已存在该设备（用于添加页提示"将更新钥匙"）。 */
    suspend fun exists(mid: String): Boolean = mutex.withLock {
        val current = _devices ?: store.load().also { _devices.value = it }
        current.any { it.mid == mid }
    }

    fun plainUrl(record: DeviceRecord): String =
        record.link(cipher.decrypt(record.hashStored)).toUrlString()

    companion object {
        const val DEFAULT_NAME = "未命名设备"
    }
}
