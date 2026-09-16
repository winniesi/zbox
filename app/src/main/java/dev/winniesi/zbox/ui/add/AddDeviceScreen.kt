package dev.winniesi.zbox.ui.add

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.winniesi.zbox.core.RemoteLink
import dev.winniesi.zbox.core.RemoteLinkParser
import dev.winniesi.zbox.core.formatDateTime
import dev.winniesi.zbox.core.relativeTime
import dev.winniesi.zbox.di.LocalAppContainer
import kotlinx.coroutines.launch

/**
 * 粘贴 / 扫码确认页：解析链接 → 展示关键信息（含"将更新已有设备"提示）→ 保存。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDeviceScreen(
    prefillUrl: String?,
    onDone: () -> Unit,
) {
    val container = LocalAppContainer.current
    val repository = container.repository
    val scope = rememberCoroutineScope()

    var text by remember { mutableStateOf(prefillUrl ?: "") }
    var name by remember { mutableStateOf("") }
    var nameInitialized by remember { mutableStateOf(prefillUrl == null) }
    var exists by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    val parsed = remember(text) { RemoteLinkParser().parse(text) }
    val link = (parsed as? RemoteLinkParser.Result.Ok)?.link

    LaunchedEffect(link) {
        if (link != null) {
            exists = repository.exists(link.mid)
            if (!nameInitialized) {
                name = link.name.orEmpty()
                nameInitialized = true
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (exists) "更新设备钥匙" else "添加设备") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("远程控制链接") },
                placeholder = { Text("粘贴 https://zcode.z.ai/remote/v4?...") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                isError = parsed is RemoteLinkParser.Result.Invalid && text.isNotBlank(),
                supportingText = {
                    when (val p = parsed) {
                        is RemoteLinkParser.Result.Invalid ->
                            if (text.isNotBlank()) Text(p.reason, color = MaterialTheme.colorScheme.error)
                        is RemoteLinkParser.Result.Ok -> Unit
                    }
                },
            )

            if (link != null) {
                LinkPreviewCard(link = link, exists = exists)

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("设备名称") },
                    placeholder = { Text(link.name ?: "未命名设备") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Button(
                    onClick = {
                        saving = true
                        scope.launch {
                            repository.addOrUpdateFromLink(link, displayName = name.trim().ifEmpty { null })
                            saving = false
                            onDone()
                        }
                    },
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (exists) "更新钥匙" else "保存设备")
                }
            }
        }
    }
}

@Composable
private fun LinkPreviewCard(link: RemoteLink, exists: Boolean) {
    val now = remember { System.currentTimeMillis() }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("链接解析结果", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            PreviewRow("设备名", link.name ?: "（未提供）")
            PreviewRow("主机", link.host)
            PreviewRow("设备 ID", link.mid)
            PreviewRow("桌面版本", link.appVersion ?: "（未提供）")
            PreviewRow("钥匙生成", "${formatDateTime(link.t)}（${relativeTime(link.t, now)}）")
            if (exists) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "该设备已存在，保存后将更新它的访问钥匙。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun PreviewRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
    }
}
