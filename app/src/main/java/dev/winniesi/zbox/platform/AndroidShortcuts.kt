package dev.winniesi.zbox.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dev.winniesi.zbox.R
import dev.winniesi.zbox.core.AppLinks
import dev.winniesi.zbox.core.DeviceRecord
import dev.winniesi.zbox.core.shortcutsFor
import dev.winniesi.zbox.MainActivity

/** 把最近使用的设备发布为桌面长按快捷方式，直达 zcode://device/open/<mid>。 */
class AndroidShortcuts(private val context: Context) {

    fun sync(devices: List<DeviceRecord>) {
        val infos = shortcutsFor(devices).map { record ->
            ShortcutInfoCompat.Builder(context, record.mid)
                .setShortLabel(record.name.take(10))
                .setLongLabel(record.name)
                .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(
                    Intent(context, MainActivity::class.java)
                        .setAction(Intent.ACTION_VIEW)
                        .setData(Uri.parse(AppLinks.buildOpenUrl(record.mid))),
                )
                .build()
        }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, infos) }
    }
}
