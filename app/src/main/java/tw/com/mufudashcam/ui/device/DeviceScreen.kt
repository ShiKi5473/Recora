package tw.com.mufudashcam.ui.device

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import tw.com.mufudashcam.AppContainer
import tw.com.mufudashcam.BuildConfig
import tw.com.mufudashcam.data.DashcamConfig
import tw.com.mufudashcam.data.Folder
import tw.com.mufudashcam.data.SettingDef
import tw.com.mufudashcam.ui.Formats
import tw.com.mufudashcam.data.SettingDefs
import tw.com.mufudashcam.data.net.ConnState
import tw.com.mufudashcam.ui.common.ConnectionChip

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(container: AppContainer) {
    val vm: DeviceViewModel = viewModel(factory = DeviceViewModel.factory(container))
    val ui by vm.ui.collectAsStateWithLifecycle()
    val conn by container.connection.state.collectAsStateWithLifecycle()
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    val connected = conn is ConnState.Connected
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

            Section("連線") {
                Text(
                    when (val s = conn) {
                        is ConnState.Connected -> if (s.viaOverride) "已連線（測試模式）" else "已連線"
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
                Section("錄影") {
                    Text("目前狀態：${ui.recordStatus ?: "讀取中…"}")
                    Text(
                        "實測此記錄器在手機連線時會停在 Standby（也無法遠端開始錄影），" +
                            "中斷連線後由記錄器自行恢復錄影。用完請按「中斷連線」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = vm::refreshRecordStatus) { Text("重新讀取狀態") }
                }

                Section("記憶卡") {
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

                Section("時間") {
                    Text("把手機目前的時間寫入記錄器（影片時間戳記與檔名都以記錄器時間為準）。")
                    Button(onClick = vm::syncTime, enabled = !ui.working) { Text("同步手機時間到記錄器") }
                }

                Section("記錄器設定") {
                    when {
                        ui.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        ui.error != null -> Text(ui.error!!, color = MaterialTheme.colorScheme.error)
                        ui.properties.isEmpty() -> Text("記錄器沒有回傳設定值")
                        else -> {
                            // 可調整的項目（記錄器有回報的才顯示）
                            val editable = SettingDefs.all.filter { "Camera.Menu.${it.key}" in ui.properties }
                            editable.filter { !it.advanced }.forEach { def ->
                                SettingRow(
                                    def = def,
                                    current = ui.properties["Camera.Menu.${def.key}"],
                                    enabled = !ui.working,
                                    onSelect = { vm.changeSetting(def, it) },
                                )
                            }
                            var showAdvanced by remember { mutableStateOf(false) }
                            TextButton(onClick = { showAdvanced = !showAdvanced }) {
                                Text(if (showAdvanced) "收起進階設定" else "進階設定（官方 App 未提供）")
                            }
                            if (showAdvanced) {
                                Text(
                                    "這些設定記錄器可以修改，但原廠 App 沒有開放給 V60P，效果未經確認。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                editable.filter { it.advanced }.forEach { def ->
                                    SettingRow(
                                        def = def,
                                        current = ui.properties["Camera.Menu.${def.key}"],
                                        enabled = !ui.working,
                                        onSelect = { vm.changeSetting(def, it) },
                                    )
                                }
                            }
                            Text(
                                "改完會重新讀取確認；若記錄器沒有套用會提示。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // 其他設定唯讀顯示
                            var showAll by remember { mutableStateOf(false) }
                            TextButton(onClick = { showAll = !showAll }) {
                                Text(if (showAll) "收起其他設定" else "顯示其他設定（唯讀）")
                            }
                            if (showAll) {
                                val editableKeys = editable.map { "Camera.Menu.${it.key}" }.toSet()
                                ui.properties.filterKeys { it !in editableKeys }.forEach { (key, value) ->
                                    Row(Modifier.fillMaxWidth()) {
                                        Text(
                                            key.removePrefix("Camera.Menu."),
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                    }
                                }
                            }
                        }
                    }
                    TextButton(onClick = vm::loadInfo) { Text("重新讀取") }
                }
            }

            Section("App 設定") {
                SwitchRow(
                    title = "預設下載後鏡頭",
                    subtitle = "下載選單中「前+後鏡頭」排在第一個",
                    checked = settings.includeRear,
                    onChange = { v -> vm.updateSettings { it.copy(includeRear = v) } },
                )
                WifiFields(
                    ssidPrefix = settings.ssidPrefix,
                    password = settings.wifiPassword,
                    onSave = { prefix, pwd -> vm.updateSettings { it.copy(ssidPrefix = prefix, wifiPassword = pwd) } },
                )
            }

            ConnectionLog(container)

            if (BuildConfig.DEBUG) {
                Section("開發者（debug build）") {
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
        LinearProgressIndicator(
            progress = { (used.toFloat() / storage.totalBytes).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        Text("剩餘 ${storage.remain} / 總容量 ${storage.total}")
    } else if (storage != null) {
        Text("容量：剩餘 ${storage.remain ?: "?"} / 總容量 ${storage.total ?: "?"}")
    }
    ui.usage?.forEach { (folder, pair) ->
        if (pair.first > 0 || folder == Folder.Normal || folder == Folder.Event) {
            Row(Modifier.fillMaxWidth()) {
                Text("${folder.label}錄影".replace("照片錄影", "照片"), modifier = Modifier.weight(1f))
                Text("${Formats.size(pair.second)}（${pair.first} 個檔案）")
            }
        }
    }
    if (ui.usageLoading) {
        Text("計算各類錄影佔用中…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        TextButton(onClick = onRefresh) { Text("重新計算容量") }
    }
}

/** 一項記錄器設定：點一下展開選項。 */
@Composable
private fun SettingRow(def: SettingDef, current: String?, enabled: Boolean, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = enabled) { open = true }.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(def.title)
                def.hint?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(def.labelOf(current), color = MaterialTheme.colorScheme.primary)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            def.options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    leadingIcon = if (option.value.equals(current, ignoreCase = true)) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = {
                        open = false
                        if (!option.value.equals(current, ignoreCase = true)) onSelect(option.value)
                    },
                )
            }
        }
    }
}

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
    val clipboard = LocalClipboardManager.current
    Section("診斷紀錄") {
        Text(
            lines.takeLast(30).joinToString("\n"),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        TextButton(onClick = { clipboard.setText(AnnotatedString(lines.joinToString("\n"))) }) { Text("複製紀錄") }
    }
}

@Composable
private fun WifiFields(ssidPrefix: String, password: String, onSave: (String, String) -> Unit) {
    var prefix by rememberSaveable(ssidPrefix) { mutableStateOf(ssidPrefix) }
    var pwd by rememberSaveable(password) { mutableStateOf(password) }
    Text("記錄器 Wi-Fi", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    Text(
        "這裡只是 App 按「連線」時用的 SSID 開頭與密碼，不會改到記錄器本身。" +
            "記錄器的密碼請用官方 App 修改，改完再回來這裡更新。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (password == DashcamConfig.DEFAULT_PASSWORD) {
        Text(
            "記錄器若還在用出廠密碼 ${DashcamConfig.DEFAULT_PASSWORD}，附近任何人都能連上，" +
                "不需驗證就能刪除影片或格式化記憶卡。建議盡快改掉。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    OutlinedTextField(
        value = prefix,
        onValueChange = { prefix = it },
        label = { Text("SSID 開頭") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = pwd,
        onValueChange = { pwd = it },
        label = { Text("密碼") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = { onSave(prefix.trim(), pwd) },
        enabled = prefix.isNotBlank() && pwd.length >= 8 && (prefix != ssidPrefix || pwd != password),
    ) { Text("儲存") }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
