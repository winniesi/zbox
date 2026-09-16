package dev.winniesi.zbox.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.winniesi.zbox.core.DeviceRecord
import dev.winniesi.zbox.core.DeviceRepository
import dev.winniesi.zbox.core.relativeTime
import dev.winniesi.zbox.core.sortedForDisplay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 列表条目的展示模型。 */
data class DeviceItem(
    val record: DeviceRecord,
    val issuedLabel: String,
    val openedLabel: String?,
)

class DevicesViewModel(
    private val repository: DeviceRepository,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    val items: StateFlow<List<DeviceItem>> = repository.devices
        .filterNotNull()
        .map { list ->
            sortedForDisplay(list).map { record ->
                DeviceItem(
                    record = record,
                    issuedLabel = relativeTime(record.issuedAt, now()),
                    openedLabel = record.lastOpenedAt?.let { relativeTime(it, now()) },
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val loaded: StateFlow<Boolean> = repository.devices
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        viewModelScope.launch { repository.refresh() }
    }

    fun remove(mid: String) {
        viewModelScope.launch { repository.remove(mid) }
    }

    fun rename(mid: String, name: String) {
        viewModelScope.launch { repository.rename(mid, name) }
    }
}
