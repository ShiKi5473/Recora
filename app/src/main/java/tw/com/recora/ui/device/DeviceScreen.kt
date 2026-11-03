package tw.com.recora.ui.device

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.draw.clip
import tw.com.recora.ui.theme.recora
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import tw.com.recora.AppContainer
import tw.com.recora.BuildConfig
import tw.com.recora.data.Brand
import tw.com.recora.data.Folder
import tw.com.recora.data.SettingDef
import tw.com.recora.ui.Formats
import tw.com.recora.data.SettingDefs
import tw.com.recora.data.net.ConnState
import tw.com.recora.ui.common.ChoiceRow
import tw.com.recora.ui.common.ConnectionChip
import tw.com.recora.ui.common.Section

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(container: AppContainer, onOpenSettings: () -> Unit) {
    val vm: DeviceViewModel = viewModel(factory = DeviceViewModel.factory(container))
    val ui by vm.ui.collectAsStateWithLifecycle()
    val conn by container.connection.state.collectAsStateWithLifecycle()
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    // 看得到這一頁才向記錄器讀取（連線中、這次連線還沒讀過才讀）
    LifecycleResumeEffect(vm) {
        vm.setVisible(true)
        onPauseOrDispose { vm.setVisible(false) }
    }
    val connected = conn is ConnState.Connected
    val connectedBrand = (conn as? ConnState.Connected)?.brand
    var askFormat by remember { mutableStateOf(false) }
    if (askFormat) {
        FormatDialog(onDismiss = { askFormat = false }, onConfirm = { askFormat = false; vm.formatCard() })
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(title = { Text("裝置") }, actions = { ConnectionChip(container) }) },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (ui.working) LinearProgressIndicator(Modifier.fillMaxWidth())

            Section("連線", Icons.Filled.Wifi) {
                Text(
                    when (val s = conn) {
                        is ConnState.Connected ->
                            "已連線：${s.brand.label}" + if (s.viaOverride) "（測試模式）" else ""
                        ConnState.Requesting -> "等待系統連線視窗…"
                        is ConnState.Failed -> s.message
                        ConnState.Idle -> "未連線"
                    },
                )
                Text("記錄器位址：${container.api.host}", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (connected) {
                        OutlinedButton(onClick = container.connection::disconnect) { Text("中斷連線") }
                    } else {
                        Button(onClick = container.connection::connect) { Text("連線") }
                    }
                }
            }

            if (connected) {
                Section("錄影", Icons.Filled.Videocam) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (ui.recording) {
                            Box(Modifier.size(8.dp).background(MaterialTheme.recora.recordDot, CircleShape))
                        }
                        Text("目前狀態：${ui.recordStatus ?: "讀取中…"}")
                    }
                    Text(
                        if (connectedBrand == Brand.Astro) {
                            "Astro 連線時會繼續錄影；只有用檔案（瀏覽、下載、串流、刪除）或改設定時才暫停，" +
                                "離開約 10 秒後自動恢復。按「中斷連線」會立刻恢復。"
                        } else {
                            "實測此記錄器在手機連線時會停在 Standby（也無法遠端開始錄影），" +
                                "中斷連線後由記錄器自行恢復錄影。用完請按「中斷連線」。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = vm::refreshRecordStatus) { Text("重新讀取狀態") }
                }

                Section("記憶卡", Icons.Filled.SdCard) {
                    Text("狀態：${ui.cardStatus ?: "讀取中…"}")
                    StorageSummary(ui, onRefresh = { vm.refreshUsage(refresh = true) })
                    Text(
                        "由記錄器自己格式化，格式化後的檔案系統與資料夾就是記錄器要的格式。" +
                            "所有影片（含緊急錄影）與照片都會被清除。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { askFormat = true },
                            enabled = !ui.formatting && !ui.working,
                        ) { Text("格式化記憶卡…", color = MaterialTheme.colorScheme.error) }
                        TextButton(onClick = vm::refreshCardStatus, enabled = !ui.formatting) { Text("重新讀取") }
                    }
                    if (ui.formatting) LinearProgressIndicator(Modifier.fillMaxWidth())
                }

                Section("時間", Icons.Filled.Schedule) {
                    Text("把手機目前的時間寫入記錄器（影片時間戳記與檔名都以記錄器時間為準）。")
                    Button(onClick = vm::syncTime, enabled = !ui.working) { Text("同步手機時間到記錄器") }
                }

                Section("記錄器設定", Icons.Filled.Tune) {
                    when {
                        // 還沒開始讀（頁面剛出現、等 setVisible）也算讀取中，免得先閃一下「沒有回傳設定值」
                        ui.loading || (!ui.settingsLoaded && ui.error == null) -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        ui.error != null -> Text(ui.error!!, color = MaterialTheme.colorScheme.error)
                        !ui.settingsLoaded || (ui.defs.isEmpty() && ui.readOnly.isEmpty()) -> Text("記錄器沒有回傳設定值")
                        else -> {
                            // 可調整的項目（記錄器有回報的才顯示）
                            ui.defs.filter { !it.advanced }.forEach { def ->
                                SettingRow(
                                    def = def,
                                    current = ui.values[def.key],
                                    enabled = !ui.working,
                                    onSelect = { vm.changeSetting(def, it) },
                                )
                            }
                            if (ui.defs.any { it.advanced }) {
                                var showAdvanced by remember { mutableStateOf(false) }
                                TextButton(onClick = { showAdvanced = !showAdvanced }) {
                                    Text(if (showAdvanced) "收起進階設定" else "進階設定（官方 App 未提供）")
                                }
                                if (showAdvanced) {
                                    Text(
                                        "這些設定記錄器可以修改，但原廠 App 沒有開放給這台機型，效果未經確認。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                    ui.defs.filter { it.advanced }.forEach { def ->
                                        SettingRow(
                                            def = def,
                                            current = ui.values[def.key],
                                            enabled = !ui.working,
                                            onSelect = { vm.changeSetting(def, it) },
                                        )
                                    }
                                }
                            }
                            Text(
                                "改完會重新讀取確認；若記錄器沒有套用會提示。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // 其他設定唯讀顯示
                            if (ui.readOnly.isNotEmpty()) {
                                var showAll by remember { mutableStateOf(false) }
                                TextButton(onClick = { showAll = !showAll }) {
                                    Text(if (showAll) "收起其他設定" else "顯示其他設定（唯讀）")
                                }
                                if (showAll) {
                                    ui.readOnly.forEach { (key, value) ->
                                        Row(Modifier.fillMaxWidth()) {
                                            Text(
                                                key,
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.weight(1f),
                                            )
                                            Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    TextButton(onClick = vm::loadInfo) { Text("重新讀取") }
                }
            }

            Card(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                ListItem(
                    leadingContent = { Icon(Icons.Filled.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    headlineContent = { Text("App 設定", style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text("下載、剪輯匯出、外觀、記錄器 Wi-Fi 密碼") },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }

            ConnectionLog(container)

            if (BuildConfig.DEBUG) {
                Section("開發者（debug build）", Icons.Filled.Code) {
                    var host by rememberSaveable(settings.hostOverride) { mutableStateOf(settings.hostOverride) }
                    Text("記錄器位址覆寫：填入假記錄器位址（模擬器用 adb reverse 加 127.0.0.1:8080），留空 = 使用真實記錄器。")
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("host[:port]") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = { vm.applyHostOverride(host) }) { Text("套用") }
                }
            }
        }
    }
}

/** 記憶卡容量：總容量 / 剩餘，以及各類錄影佔用。 */
@Composable
private fun StorageSummary(ui: DeviceUi, onRefresh: () -> Unit) {
    val storage = ui.storage
    if (storage?.totalBytes != null && storage.remainBytes != null && storage.totalBytes > 0) {
        val used = (storage.totalBytes - storage.remainBytes).coerceAtLeast(0)
        StorageBar(storage.totalBytes, used, ui.usage, Modifier.fillMaxWidth().padding(top = 4.dp))
        Text("剩餘 ${storage.remain} / 總容量 ${storage.total}")
    } else if (storage != null) {
        Text("容量：剩餘 ${storage.remain ?: "?"} / 總容量 ${storage.total ?: "?"}")
    }
    ui.usage?.forEach { (folder, pair) ->
        if (pair.first > 0 || folder == Folder.Normal || folder == Folder.Event) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.padding(end = 8.dp).size(10.dp).background(folderColor(folder), CircleShape))
                Text("${folder.label}錄影".replace("照片錄影", "照片"), modifier = Modifier.weight(1f))
                Text("${Formats.size(pair.second)}（${pair.first} 個檔案）")
            }
        }
    }
    if (ui.usage?.isEmpty() == true && !ui.usageLoading) {
        Text(
            "還沒讀過檔案列表：先到「檔案」頁，或按下方「重新計算容量」（Astro 讀列表時會暫停錄影）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (ui.usageLoading) {
        Text("計算各類錄影佔用中…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        TextButton(onClick = onRefresh) { Text("重新計算容量") }
    }
}

/** 一項記錄器設定：點一下展開選項；目前的值不在選項裡時顯示原始值。 */
@Composable
private fun SettingRow(def: SettingDef, current: String?, enabled: Boolean, onSelect: (String) -> Unit) =
    ChoiceRow(
        title = def.title,
        options = def.options,
        selected = def.options.firstOrNull { it.value.equals(current, ignoreCase = true) },
        optionLabel = { it.label },
        onSelect = { onSelect(it.value) },
        hint = def.hint,
        enabled = enabled,
        selectedLabel = def.labelOf(current),
    )

@Composable
private fun FormatDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var understood by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("格式化記憶卡？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("記憶卡上所有影片（含緊急錄影）與照片都會被永久刪除，無法復原。需要的影片請先下載。")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = understood, onCheckedChange = { understood = it })
                    Text("我了解，所有檔案都會被刪除")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = understood) {
                Text("格式化", color = if (understood) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 連線過程紀錄：沒有接電腦時用來查連不上的原因，可複製貼給別人看。 */
@Composable
private fun ConnectionLog(container: AppContainer) {
    val lines by container.connection.log.collectAsStateWithLifecycle()
    if (lines.isEmpty()) return
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Section("診斷紀錄", Icons.Filled.BugReport) {
        Text(
            lines.takeLast(30).joinToString("\n"),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        TextButton(onClick = {
            scope.launch { clipboard.setClipEntry(ClipData.newPlainText("診斷紀錄", lines.joinToString("\n")).toClipEntry()) }
        }) { Text("複製紀錄") }
    }
}

@Composable
private fun folderColor(folder: Folder): Color = when (folder) {
    Folder.Normal -> MaterialTheme.colorScheme.primary
    Folder.Event -> MaterialTheme.colorScheme.error
    Folder.Parking -> MaterialTheme.colorScheme.tertiary
    Folder.Photo -> MaterialTheme.colorScheme.secondary
}

/** 記憶卡容量條：依類別（一般、緊急、停車、照片）分色，其餘已用空間灰色，剩下的是空白。 */
@Composable
private fun StorageBar(total: Long, used: Long, usage: Map<Folder, Pair<Int, Long>>?, modifier: Modifier = Modifier) {
    val segments = Folder.entries.mapNotNull { f ->
        val bytes = usage?.get(f)?.second ?: 0L
        if (bytes > 0) folderColor(f) to bytes else null
    }
    val other = (used - segments.sumOf { it.second }).coerceAtLeast(0)
    val otherColor = MaterialTheme.colorScheme.outline
    val free = (total - used).coerceAtLeast(0)
    Row(modifier.height(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        (segments + (otherColor to other)).forEach { (color, bytes) ->
            if (bytes > 0) Box(Modifier.weight(bytes.toFloat() / total).fillMaxHeight().background(color))
        }
        if (free > 0) Spacer(Modifier.weight(free.toFloat() / total))
    }
}
