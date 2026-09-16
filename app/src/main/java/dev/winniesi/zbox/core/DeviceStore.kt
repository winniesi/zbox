package dev.winniesi.zbox.core

/** 设备持久化抽象；实现负责原子读写全量列表。 */
interface DeviceStore {
    suspend fun load(): List<DeviceRecord>
    suspend fun save(devices: List<DeviceRecord>)
}
