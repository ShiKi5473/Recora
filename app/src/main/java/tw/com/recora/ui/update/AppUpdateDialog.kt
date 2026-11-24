package tw.com.recora.ui.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File
import tw.com.recora.BuildConfig
import tw.com.recora.ui.Formats
import tw.com.recora.update.AppUpdateState
import tw.com.recora.update.AvailableUpdate
import tw.com.recora.update.InstallPauseReason

fun openInstallPermissionSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

fun launchInstallConfirmIntent(context: Context, confirmIntent: Intent) {
    runCatching {
        confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(confirmIntent)
    }
}

/** GitHub release check / download / self-install prompt. */
@Composable
fun AppUpdateDialog(
    state: AppUpdateState,
    transferActive: Boolean,
    autoUpdateEnabled: Boolean,
    hasInstallPermission: Boolean,
    onDownload: (AvailableUpdate) -> Unit,
    onInstall: (AvailableUpdate, File) -> Unit,
    onCancelDownload: () -> Unit,
    onOpenInstallSettings: () -> Unit,
    onRelaunchConfirm: (Intent) -> Unit,
    onEnableAutoUpdate: (Boolean) -> Unit,
    onSkipVersion: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        is AppUpdateState.Available -> {
            if (!state.showPrompt) return
            val rel = state.release
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("發現新版本 v${rel.version}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("目前版本：v${BuildConfig.VERSION_NAME} → 最新版本：v${rel.version}", fontWeight = FontWeight.Bold)
                        if (rel.sizeBytes > 0L) {
                            Text("安裝檔大小：${Formats.size(rel.sizeBytes)}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (rel.notes.isNotBlank()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 200.dp)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                Text(rel.notes, style = MaterialTheme.typography.bodySmall)
                            }
                        } else {
                            Text("可從 GitHub Releases 下載新版 APK 並更新 App。", style = MaterialTheme.typography.bodySmall)
                        }
                        if (!autoUpdateEnabled) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onEnableAutoUpdate(true) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = false, onCheckedChange = { onEnableAutoUpdate(it) })
                                Text("之後自動檢查並更新 App", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { onDownload(rel) }) { Text("下載並更新") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { onSkipVersion(rel.version) }) { Text("略過此版") }
                        TextButton(onClick = onDismiss) { Text("稍後再說") }
                    }
                },
            )
        }

        is AppUpdateState.Downloading -> {
            if (!state.showPrompt) return
            val total = state.totalBytes
            val downloaded = state.downloadedBytes
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("正在下載更新 v${state.release.version}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (total > 0L) {
                            val fraction = (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                            val pct = ((downloaded * 100L) / total).coerceIn(0L, 100L)
                            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                            Text(
                                "$pct%（${Formats.progress(downloaded, total)}）",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text("已下載 ${Formats.size(downloaded)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) { Text("背景下載") }
                },
                dismissButton = {
                    TextButton(onClick = onCancelDownload) { Text("取消下載") }
                },
            )
        }

        is AppUpdateState.Verifying -> {
            if (!state.showPrompt) return
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("正在驗證更新檔") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("正在驗證 SHA-256 雜湊與 APK 簽章…", style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) { Text("背景處理") }
                },
            )
        }

        is AppUpdateState.ReadyToInstall -> {
            if (!state.showPrompt) return
            val needsPerm = !hasInstallPermission || state.reason == InstallPauseReason.PERMISSION_REQUIRED
            val transferBlocked = transferActive || state.reason == InstallPauseReason.TRANSFER_ACTIVE
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("準備安裝 v${state.release.version}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("更新檔已下載並通過驗證。")
                        when {
                            transferBlocked -> Text(
                                "記錄器影片下載進行中，請等待下載結束後再進行安裝。",
                                color = MaterialTheme.colorScheme.error,
                            )
                            needsPerm -> Text("需要允許「安裝未知應用程式」權限才能更新，授權後返回 App 即可自動安裝。")
                            else -> Text("點擊「立即安裝」即可開始更新。")
                        }
                    }
                },
                confirmButton = {
                    when {
                        transferBlocked -> TextButton(onClick = {}, enabled = false) { Text("下載中無法安裝") }
                        needsPerm -> TextButton(onClick = onOpenInstallSettings) { Text("前往授權") }
                        else -> TextButton(onClick = { onInstall(state.release, state.apkFile) }) { Text("立即安裝") }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("稍後安裝") }
                },
            )
        }

        is AppUpdateState.Installing -> {
            if (!state.showPrompt) return
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("正在安裝 v${state.release.version}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("正在呼叫系統安裝程序…", style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) { Text("關閉") }
                },
            )
        }

        is AppUpdateState.PendingUserAction -> {
            if (!state.showPrompt) return
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("確認更新 v${state.release.version}") },
                text = { Text("請在系統跳出的確認視窗中點擊「更新」以完成安裝；若未看到視窗可點擊下方按鈕重新開啟。") },
                confirmButton = {
                    TextButton(onClick = { onRelaunchConfirm(state.confirmIntent) }) { Text("開啟安裝視窗") }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("關閉") }
                },
            )
        }

        is AppUpdateState.Failed -> {
            if (!state.showPrompt) return
            val rel = state.release
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("更新失敗") },
                text = { Text(state.message) },
                confirmButton = {
                    if (rel != null) {
                        TextButton(onClick = { onDownload(rel) }) { Text("重試") }
                    } else {
                        TextButton(onClick = onDismiss) { Text("確定") }
                    }
                },
                dismissButton = {
                    if (rel != null) {
                        TextButton(onClick = onDismiss) { Text("關閉") }
                    }
                },
            )
        }

        AppUpdateState.Idle, AppUpdateState.Checking, is AppUpdateState.UpToDate -> Unit
    }
}
