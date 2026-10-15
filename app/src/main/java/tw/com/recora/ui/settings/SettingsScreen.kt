package tw.com.recora.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tw.com.recora.AppContainer
import tw.com.recora.data.AppSettings
import tw.com.recora.data.DashcamConfig
import tw.com.recora.data.ExportCodec
import tw.com.recora.data.ExportQuality
import tw.com.recora.data.ExportResolution
import tw.com.recora.data.Folder
import tw.com.recora.data.LocalLibrary
import tw.com.recora.data.SubfolderMode
import tw.com.recora.data.ThemeMode
import tw.com.recora.ui.common.ChoiceRow
import tw.com.recora.ui.common.Section
import tw.com.recora.ui.common.SettingRow
import tw.com.recora.ui.common.SwitchRow
import tw.com.recora.ui.editor.ClipExporter
import tw.com.recora.ui.library.LibraryFoldersDialog
import tw.com.recora.ui.library.libraryFoldersSummary

/** 修改 App 設定：傳入一個轉換函式。 */
typealias SettingsUpdate = ((AppSettings) -> AppSettings) -> Unit

/** App 自己的設定（記錄器本身的設定在「裝置」頁）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val settings by container.settings.state.collectAsStateWithLifecycle()
    // 用 App 的 scope 寫入：改完馬上離開這頁也會存到
    val update: SettingsUpdate = { transform -> container.appScope.launch { container.settings.update(transform) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App 設定") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("下載", Icons.Filled.Download) {
                SwitchRow(
                    title = "預設下載後鏡頭",
                    subtitle = "下載選單中「前+後鏡頭」排在第一個；備份緊急錄影時也一併下載後鏡頭",
                    checked = settings.includeRear,
                ) { v -> update { it.copy(includeRear = v) } }
                SwitchRow(
                    title = "連上記錄器時自動備份緊急錄影",
                    subtitle = "把還沒下載的緊急錄影自動加入下載佇列",
                    checked = settings.autoBackupEvents,
                ) { v -> update { it.copy(autoBackupEvents = v) } }
                ChoiceRow(
                    title = "下載資料夾分類",
                    options = SubfolderMode.entries,
                    selected = settings.downloadSubfolder,
                    optionLabel = { it.label },
                    onSelect = { m -> update { it.copy(downloadSubfolder = m) } },
                    hint = "在 Movies／Pictures 的 ${settings.albumDir} 底下再分子資料夾，系統相簿裡比較好找；只影響之後的下載",
                )
                AlbumDirField(settings.albumDir) { dir -> update { it.copy(albumDir = dir) } }
            }

            Section("瀏覽", Icons.Filled.VideoLibrary) {
                ChoiceRow(
                    title = "開啟時顯示的資料夾",
                    options = Folder.entries,
                    selected = settings.startFolder,
                    optionLabel = { it.label },
                    onSelect = { f -> update { it.copy(startFolder = f) } },
                    hint = "「檔案」頁一開始顯示哪個分頁",
                )
                SwitchRow(
                    title = "相簿用格狀顯示",
                    subtitle = "縮圖較大，一次看得到比較多（相簿右上也能切換）",
                    checked = settings.libraryGrid,
                ) { v -> update { it.copy(libraryGrid = v) } }
                var showFolders by remember { mutableStateOf(false) }
                SettingRow(
                    title = "相簿資料夾",
                    subtitle = "App 的資料夾以外也讀：${libraryFoldersSummary(settings.libraryFolders)}",
                ) {
                    TextButton(onClick = { showFolders = true }) { Text("變更") }
                }
                if (showFolders) LibraryFoldersDialog(container, onDismiss = { showFolders = false })
            }

            Section("播放", Icons.Filled.PlayCircle) {
                SwitchRow(
                    title = "播完自動播下一段",
                    subtitle = "依錄影時間接著播（播放畫面上也能切換）",
                    checked = settings.autoPlayNext,
                ) { v -> update { it.copy(autoPlayNext = v) } }
                SwitchRow(
                    title = "放大時截圖只存看到的範圍",
                    subtitle = "例如只存車牌附近；關閉時一律存整張畫面",
                    checked = settings.screenshotVisibleArea,
                ) { v -> update { it.copy(screenshotVisibleArea = v) } }
            }

            Section("剪輯匯出", Icons.Filled.ContentCut) { ExportSettingsFields(settings, update) }

            Section("外觀", Icons.Filled.Palette) {
                ChoiceRow(
                    title = "主題",
                    options = ThemeMode.entries,
                    selected = settings.themeMode,
                    optionLabel = { it.label },
                    onSelect = { m -> update { it.copy(themeMode = m) } },
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SwitchRow(
                        title = "動態色彩",
                        subtitle = "依桌布配色；關閉時使用錄刻品牌色",
                        checked = settings.dynamicColor,
                    ) { v -> update { it.copy(dynamicColor = v) } }
                }
            }

            Section("MUFU 記錄器 Wi-Fi", Icons.Filled.Wifi) {
                WifiFields(
                    ssidPrefix = settings.ssidPrefix,
                    password = settings.wifiPassword,
                    onSave = { prefix, pwd -> update { it.copy(ssidPrefix = prefix, wifiPassword = pwd) } },
                )
            }

            Section("Astro 記錄器 Wi-Fi", Icons.Filled.Wifi) {
                WifiFields(
                    ssidPrefix = settings.astroSsidPrefix,
                    password = settings.astroWifiPassword,
                    onSave = { prefix, pwd -> update { it.copy(astroSsidPrefix = prefix, astroWifiPassword = pwd) } },
                )
            }
        }
    }
}

/** 剪輯匯出的設定；「App 設定」頁與剪輯頁的對話框共用。 */
@Composable
fun ExportSettingsFields(settings: AppSettings, update: SettingsUpdate, showMuteDefault: Boolean = true) {
    val hevc = ClipExporter.hevcEncoderAvailable
    val codec = if (settings.exportCodec == ExportCodec.H265 && !hevc) ExportCodec.H264 else settings.exportCodec
    val reencode = codec != ExportCodec.Original
    ChoiceRow(
        title = "編碼",
        options = ExportCodec.entries,
        selected = codec,
        optionLabel = { it.label },
        onSelect = { c -> update { it.copy(exportCodec = c) } },
        hint = when {
            !reencode -> "直接複製原本的畫面（Mufu 是 H.265、Astro 是 H.264）：畫質、檔案大小都跟原檔一樣，匯出很快；" +
                "Mufu 的 H.265 有些裝置或網站播不了"
            hevc -> "H.264 相容性最好；H.265 檔案較小，但有些裝置或網站播不了；「不重新編碼」畫質最好、最快"
            else -> "這支手機沒有 H.265 編碼器，重新編碼只能用 H.264；「不重新編碼」畫質最好、最快"
        },
        optionEnabled = { it != ExportCodec.H265 || hevc },
    )
    ChoiceRow(
        title = "解析度",
        options = ExportResolution.entries,
        selected = if (reencode) settings.exportResolution else ExportResolution.Original,
        optionLabel = { it.label },
        onSelect = { r -> update { it.copy(exportResolution = r) } },
        hint = if (reencode) "只會縮小、不會放大；解析度越低轉檔越快" else "不重新編碼時跟原檔一樣",
        enabled = reencode,
    )
    ChoiceRow(
        title = "畫質",
        options = ExportQuality.entries,
        selected = settings.exportQuality,
        optionLabel = { it.label },
        onSelect = { q -> update { it.copy(exportQuality = q) } },
        hint = if (reencode) "「省空間」適合用通訊軟體分享" else "不重新編碼時跟原檔一樣",
        enabled = reencode,
        selectedLabel = if (reencode) settings.exportQuality.label else "同原檔",
    )
    if (showMuteDefault) {
        SwitchRow(
            title = "預設去除聲音",
            subtitle = "剪輯時「去除聲音」預設打開，車內的對話不會跟著分享出去",
            checked = settings.exportMute,
        ) { v -> update { it.copy(exportMute = v) } }
    }
}

/** 存檔資料夾：下載、匯出、截圖存到 Movies／Pictures 底下的哪個資料夾。 */
@Composable
private fun AlbumDirField(albumDir: String, onSave: (String) -> Unit) {
    var name by rememberSaveable(albumDir) { mutableStateOf(albumDir) }
    val trimmed = name.trim()
    val valid = LocalLibrary.isValidAlbumDir(trimmed)
    Text("存檔資料夾")
    Text(
        "下載的影片、剪輯匯出的 MP4 存到 Movies/$albumDir，照片與截圖存到 Pictures/$albumDir。" +
            "只影響之後存的檔案；原本資料夾裡的檔案不會搬，相簿照樣讀得到。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text("資料夾名稱") },
        singleLine = true,
        isError = !valid,
        supportingText = if (valid) null else {
            { Text("只能是一層資料夾名稱，不能空白、不能以 . 開頭，也不能有 / \\ : * ? \" < > |") }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onSave(trimmed) }, enabled = valid && trimmed != albumDir) { Text("儲存") }
        if (albumDir != DashcamConfig.LOCAL_DIR) {
            TextButton(onClick = { onSave(DashcamConfig.LOCAL_DIR) }) { Text("改回 ${DashcamConfig.LOCAL_DIR}") }
        }
    }
}

@Composable
private fun WifiFields(ssidPrefix: String, password: String, onSave: (String, String) -> Unit) {
    var prefix by rememberSaveable(ssidPrefix) { mutableStateOf(ssidPrefix) }
    var pwd by rememberSaveable(password) { mutableStateOf(password) }
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
