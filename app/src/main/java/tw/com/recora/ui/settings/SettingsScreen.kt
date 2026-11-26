package tw.com.recora.ui.settings

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import tw.com.recora.AppContainer
import tw.com.recora.BuildConfig
import tw.com.recora.data.AppSettings
import tw.com.recora.data.DashcamConfig
import tw.com.recora.data.ExportCodec
import tw.com.recora.data.ExportQuality
import tw.com.recora.data.ExportResolution
import tw.com.recora.data.Folder
import tw.com.recora.data.LocalLibrary
import tw.com.recora.data.SubfolderMode
import tw.com.recora.data.ThemeMode
import tw.com.recora.ui.Formats
import tw.com.recora.ui.common.ChoiceRow
import tw.com.recora.ui.common.Section
import tw.com.recora.ui.common.SettingRow
import tw.com.recora.ui.common.SwitchRow
import tw.com.recora.ui.editor.ClipExporter
import tw.com.recora.ui.library.LibraryFoldersDialog
import tw.com.recora.ui.library.libraryFoldersSummary
import tw.com.recora.ui.update.launchInstallConfirmIntent
import tw.com.recora.ui.update.openInstallPermissionSettings
import tw.com.recora.update.AppUpdateState
import tw.com.recora.update.AppUpdater
import tw.com.recora.update.InstallPauseReason

/** 修改 App 設定：傳入一個轉換函式。 */
typealias SettingsUpdate = ((AppSettings) -> AppSettings) -> Unit

/** App 自己的設定（記錄器本身的設定在「裝置」頁）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val settings by container.settings.state.collectAsStateWithLifecycle()
    // 用 App 的 scope 寫入：改完馬上離開這頁也會存到
    val update: SettingsUpdate = { transform -> container.settings.launchUpdate(transform) }
    var albumDirDirty by remember { mutableStateOf(false) }
    var mufuWifiDirty by remember { mutableStateOf(false) }
    var astroWifiDirty by remember { mutableStateOf(false) }
    val hasUnsavedChanges = albumDirDirty || mufuWifiDirty || astroWifiDirty
    var confirmDiscard by remember { mutableStateOf(false) }
    val tryBack = { if (hasUnsavedChanges) confirmDiscard = true else onBack() }
    BackHandler(enabled = hasUnsavedChanges) { confirmDiscard = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App 設定") },
                navigationIcon = {
                    IconButton(onClick = tryBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
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
                AlbumDirField(
                    albumDir = settings.albumDir,
                    onDirtyChange = { albumDirDirty = it },
                ) { dir -> update { it.copy(albumDir = dir) } }
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
                SwitchRow(
                    title = "顯示 GPS",
                    subtitle = "影片含 GPS 時（如 Astro 與其剪輯檔）：標題下顯示時速與座標（關掉後「更多」選單仍可開地圖、分享軌跡）",
                    checked = settings.showGps,
                ) { v -> update { it.copy(showGps = v) } }
                SwitchRow(
                    title = "顯示路線地圖",
                    subtitle = "影片含 GPS 時：直向畫面下半部顯示路線，點路線跳到那一秒（播放畫面上也能切換）",
                    checked = settings.showRouteMap,
                ) { v -> update { it.copy(showRouteMap = v) } }
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
                    onDirtyChange = { mufuWifiDirty = it },
                    onSave = { prefix, pwd -> update { it.copy(ssidPrefix = prefix, wifiPassword = pwd) } },
                )
            }

            Section("Astro 記錄器 Wi-Fi", Icons.Filled.Wifi) {
                WifiFields(
                    ssidPrefix = settings.astroSsidPrefix,
                    password = settings.astroWifiPassword,
                    onDirtyChange = { astroWifiDirty = it },
                    onSave = { prefix, pwd -> update { it.copy(astroSsidPrefix = prefix, astroWifiPassword = pwd) } },
                )
            }

            Section("App 更新", Icons.Filled.SystemUpdate) {
                AppUpdateSection(container, settings, update)
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("尚未儲存變更") },
            text = { Text("資料夾名稱或 Wi-Fi 設定尚未按「儲存」，確定要捨棄修改並離開嗎？") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onBack()
                }) { Text("捨棄並離開", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("繼續編輯") } },
        )
    }
}

@Composable
private fun AppUpdateSection(container: AppContainer, settings: AppSettings, update: SettingsUpdate) {
    val context = LocalContext.current
    val appUpdate by AppUpdater.state.collectAsStateWithLifecycle()
    val transferActive by container.downloads.active.collectAsStateWithLifecycle()
    var hasInstallPermission by remember { mutableStateOf(context.packageManager.canRequestPackageInstalls()) }
    LifecycleResumeEffect(Unit) {
        hasInstallPermission = context.packageManager.canRequestPackageInstalls()
        onPauseOrDispose { }
    }

    Text("目前版本：v${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）")
    Text(
        "上次檢查更新：${Formats.epochMinute(settings.lastAppUpdateCheckMs)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = { AppUpdater.checkManual(context) }, enabled = !appUpdate.isBusy) {
            Text("檢查更新")
        }
        when (val st = appUpdate) {
            is AppUpdateState.Checking, is AppUpdateState.Verifying, is AppUpdateState.Installing -> {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            is AppUpdateState.Downloading -> {
                OutlinedButton(onClick = { AppUpdater.cancelDownload(context) }) { Text("取消下載") }
            }
            is AppUpdateState.Available -> {
                OutlinedButton(onClick = { AppUpdater.startDownloadAndInstall(context, st.release, showPrompt = true) }) {
                    Text("下載 v${st.release.version}")
                }
            }
            is AppUpdateState.ReadyToInstall -> {
                if (!hasInstallPermission) {
                    OutlinedButton(onClick = { openInstallPermissionSettings(context) }) { Text("授權安裝") }
                } else {
                    OutlinedButton(
                        onClick = { AppUpdater.installDownloaded(context, st.release, st.apkFile, showPrompt = true) },
                        enabled = !transferActive,
                    ) { Text("立即安裝") }
                }
            }
            is AppUpdateState.PendingUserAction -> {
                OutlinedButton(onClick = { launchInstallConfirmIntent(context, st.confirmIntent) }) {
                    Text("確認安裝")
                }
            }
            AppUpdateState.Idle, is AppUpdateState.UpToDate, is AppUpdateState.Failed -> Unit
        }
    }
    if (appUpdate is AppUpdateState.Downloading) {
        val dl = appUpdate as AppUpdateState.Downloading
        val total = dl.totalBytes
        val downloaded = dl.downloadedBytes
        if (total > 0L) {
            val fraction = (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
            val pct = ((downloaded * 100L) / total).coerceIn(0L, 100L)
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Text(
                "正在下載 v${dl.release.version}：$pct%（${Formats.progress(downloaded, total)}）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                "正在下載 v${dl.release.version}：已下載 ${Formats.size(downloaded)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    val statusText = when (val st = appUpdate) {
        AppUpdateState.Idle -> null
        AppUpdateState.Checking -> "正在檢查 GitHub 最新版本…"
        is AppUpdateState.UpToDate -> "目前已是最新版本（v${st.currentVersion}）"
        is AppUpdateState.Available -> "發現新版本 v${st.release.version}"
        is AppUpdateState.Downloading -> null
        is AppUpdateState.Verifying -> "正在驗證更新檔與簽章…"
        is AppUpdateState.ReadyToInstall -> when {
            transferActive || st.reason == InstallPauseReason.TRANSFER_ACTIVE ->
                "v${st.release.version} 已下載完成，請等待記錄器檔案下載結束再安裝"
            !hasInstallPermission || st.reason == InstallPauseReason.PERMISSION_REQUIRED ->
                "v${st.release.version} 已下載完成，請允許「安裝未知應用程式」權限"
            else -> "v${st.release.version} 已下載完成，可立即安裝"
        }
        is AppUpdateState.Installing -> "正在啟動安裝程序…"
        is AppUpdateState.PendingUserAction -> "請在系統安裝確認視窗中完成更新"
        is AppUpdateState.Failed -> "更新失敗：${st.message}"
    }
    statusText?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = if (appUpdate is AppUpdateState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    SwitchRow(
        title = "自動更新 App",
        subtitle = "定期檢查 GitHub 新版本並自動下載安裝",
        checked = settings.appAutoUpdate,
    ) { v -> update { it.copy(appAutoUpdate = v) } }
    if (settings.appAutoUpdate) {
        SwitchRow(
            title = "僅在 Wi-Fi 下自動下載更新",
            subtitle = "避免使用行動數據下載安裝檔",
            checked = settings.appUpdateWifiOnly,
        ) { v -> update { it.copy(appUpdateWifiOnly = v) } }
        if (!hasInstallPermission) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "尚未開啟「安裝未知應用程式」權限，自動下載後仍需手動授權才能完成更新。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { openInstallPermissionSettings(context) }) { Text("授權") }
            }
        }
    }
}

/** 剪輯匯出的設定；「App 設定」頁與剪輯頁的對話框共用。 */
@Composable
fun ExportSettingsFields(
    settings: AppSettings,
    update: SettingsUpdate,
    showMuteDefault: Boolean = true,
    /** 各畫質要求編碼器的影像位元率（bps），有給的話標在選項上；剪輯頁才知道解析度與影格率。 */
    qualityBitrate: ((ExportQuality) -> Int)? = null,
) {
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
        optionLabel = { q -> qualityBitrate?.let { "${q.label}・${formatMbps(it(q))}" } ?: q.label },
        onSelect = { q -> update { it.copy(exportQuality = q) } },
        hint = if (reencode) {
            "「省空間」適合用通訊軟體分享" + if (qualityBitrate != null) "；位元率是要求編碼器的影像目標，實際會隨畫面內容浮動" else ""
        } else {
            "不重新編碼時跟原檔一樣"
        },
        enabled = reencode,
        selectedLabel = when {
            !reencode -> "同原檔"
            qualityBitrate != null -> "${settings.exportQuality.label}・${formatMbps(qualityBitrate(settings.exportQuality))}"
            else -> settings.exportQuality.label
        },
    )
    if (showMuteDefault) {
        SwitchRow(
            title = "預設去除聲音",
            subtitle = "剪輯時「去除聲音」預設打開，車內的對話不會跟著分享出去",
            checked = settings.exportMute,
        ) { v -> update { it.copy(exportMute = v) } }
    }
}

/** 位元率顯示成 Mbps：10 以上取整數，其餘一位小數。 */
private fun formatMbps(bps: Int): String {
    val mbps = bps / 1_000_000.0
    return if (mbps >= 10) "約 ${mbps.roundToInt()} Mbps" else "約 ${"%.1f".format(mbps)} Mbps"
}

/** 存檔資料夾：下載、匯出、截圖存到 Movies／Pictures 底下的哪個資料夾。 */
@Composable
private fun AlbumDirField(albumDir: String, onDirtyChange: (Boolean) -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable(albumDir) { mutableStateOf(albumDir) }
    val trimmed = name.trim()
    val valid = LocalLibrary.isValidAlbumDir(trimmed)
    val dirty = name != albumDir
    LaunchedEffect(dirty) { onDirtyChange(dirty) }
    DisposableEffect(Unit) { onDispose { onDirtyChange(false) } }
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
private fun WifiFields(
    ssidPrefix: String,
    password: String,
    onDirtyChange: (Boolean) -> Unit,
    onSave: (String, String) -> Unit,
) {
    var prefix by rememberSaveable(ssidPrefix) { mutableStateOf(ssidPrefix) }
    var pwd by rememberSaveable(password) { mutableStateOf(password) }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    val dirty = prefix != ssidPrefix || pwd != password
    LaunchedEffect(dirty) { onDirtyChange(dirty) }
    DisposableEffect(Unit) { onDispose { onDirtyChange(false) } }
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
        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { showPassword = !showPassword }) {
                Icon(
                    imageVector = if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (showPassword) "隱藏密碼" else "顯示密碼",
                )
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = { onSave(prefix.trim(), pwd) },
        enabled = prefix.isNotBlank() && pwd.length >= 8 && (prefix != ssidPrefix || pwd != password),
    ) { Text("儲存") }
}
