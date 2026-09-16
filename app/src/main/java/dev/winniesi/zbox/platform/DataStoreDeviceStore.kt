package dev.winniesi.zbox.platform

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.winniesi.zbox.core.DeviceRecord
import dev.winniesi.zbox.core.DeviceStore
import dev.winniesi.zbox.core.DeviceBookDto
import dev.winniesi.zbox.core.toDto
import dev.winniesi.zbox.core.toRecord
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

private val Context.deviceDataStore by preferencesDataStore(name = "zbox_devices")

/** DataStore(JSON) 实现：损坏数据兜底为空列表，避免启动崩溃。 */
class DataStoreDeviceStore(
    private val context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : DeviceStore {

    override suspend fun load(): List<DeviceRecord> {
        return try {
            val prefs: Preferences = context.deviceDataStore.data.first()
            val raw = prefs[KEY_DEVICES] ?: return emptyList()
            json.decodeFromString(DeviceBookDto.serializer(), raw).devices.map { it.toRecord() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun save(devices: List<DeviceRecord>) {
        val raw = json.encodeToString(
            DeviceBookDto.serializer(),
            DeviceBookDto(devices = devices.map { it.toDto() }),
        )
        context.deviceDataStore.edit { it[KEY_DEVICES] = raw }
    }

    private companion object {
        val KEY_DEVICES = stringPreferencesKey("devices_json")
    }
}
