package dev.winniesi.zbox.core

/**
 * 桌面快捷方式的选取规则（纯逻辑，可单测）：按最近使用排序取前 max 个。
 */
fun shortcutsFor(devices: List<DeviceRecord>, max: Int = MAX_SHORTCUTS): List<DeviceRecord> =
    sortedForDisplay(devices).take(max)

const val MAX_SHORTCUTS = 4
