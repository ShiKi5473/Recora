package tw.com.mufudashcam.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tw.com.mufudashcam.AppContainer
import tw.com.mufudashcam.data.net.ConnState

/** TopAppBar 右上角的連線狀態；已連線時點一下可中斷。 */
@Composable
fun ConnectionChip(container: AppContainer) {
    val state by container.connection.state.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = {
                if (state is ConnState.Connected) menu = true else container.connection.connect()
            },
            label = {
                Text(
                    when (val s = state) {
                        is ConnState.Connected -> if (s.viaOverride) "測試模式" else "已連線"
                        ConnState.Requesting -> "連線中…"
                        else -> "未連線"
                    },
                )
            },
            leadingIcon = {
                Icon(
                    if (state is ConnState.Connected) Icons.Filled.Wifi else Icons.Filled.WifiOff,
                    contentDescription = null,
                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                )
            },
            modifier = Modifier.padding(end = 8.dp),
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("中斷連線") },
                onClick = {
                    menu = false
                    container.connection.disconnect()
                },
            )
        }
    }
}

/** 尚未連線時的說明與連線按鈕。 */
@Composable
fun NotConnectedPane(container: AppContainer, modifier: Modifier = Modifier) {
    val state by container.connection.state.collectAsStateWithLifecycle()
    val settings by container.settings.state.collectAsStateWithLifecycle()
    MessagePane(
        icon = Icons.Filled.WifiOff,
        title = "尚未連線到記錄器",
        text = "1. 確認記錄器已開機、Wi-Fi 已開啟\n" +
            "2. 按下方按鈕，在系統視窗選擇 ${settings.ssidPrefix} 開頭的 Wi-Fi\n" +
            "（連線後手機的行動網路照常可用）",
        modifier = modifier,
    ) {
        (state as? ConnState.Failed)?.let {
            Text(it.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
        }
        if (state == ConnState.Requesting) {
            CircularProgressIndicator()
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { container.connection.cancelRequest() }) { Text("取消") }
        } else {
            Button(onClick = { container.connection.connect() }) { Text("連線到記錄器") }
        }
    }
}

@Composable
fun MessagePane(
    icon: ImageVector,
    title: String,
    text: String? = null,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (text != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(20.dp))
        actions()
    }
}
