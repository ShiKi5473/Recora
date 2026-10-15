package tw.com.recora.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tw.com.recora.AppContainer
import tw.com.recora.R
import tw.com.recora.data.Brand
import tw.com.recora.data.net.ConnState
import tw.com.recora.ui.theme.recora

/** TopAppBar 右上角的連線狀態；已連線時點一下可中斷。 */
@Composable
fun ConnectionChip(container: AppContainer) {
    val state by container.connection.state.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    val connected = state is ConnState.Connected
    Box {
        AssistChip(
            onClick = {
                if (connected) menu = true else container.connection.connect()
            },
            label = {
                Text(
                    when (val s = state) {
                        is ConnState.Connected -> if (s.viaOverride) "測試：${s.brand.label}" else "已連線：${s.brand.label}"
                        ConnState.Requesting -> "連線中…"
                        else -> "未連線"
                    },
                )
            },
            leadingIcon = {
                when {
                    // 已連線：錄影紅點，呼應 logo
                    connected -> Box(Modifier.size(8.dp).background(MaterialTheme.recora.recordDot, CircleShape))
                    state == ConnState.Requesting -> CircularProgressIndicator(
                        modifier = Modifier.size(AssistChipDefaults.IconSize),
                        strokeWidth = 2.dp,
                    )
                    else -> Icon(
                        Icons.Filled.WifiOff,
                        contentDescription = null,
                        modifier = Modifier.size(AssistChipDefaults.IconSize),
                    )
                }
            },
            colors = if (connected) {
                AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            } else {
                AssistChipDefaults.assistChipColors()
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

/** 尚未連線時的說明與連線按鈕；可切換要連 MUFU 還是 Astro（記住上次的選擇）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotConnectedPane(container: AppContainer, modifier: Modifier = Modifier) {
    val state by container.connection.state.collectAsStateWithLifecycle()
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val brand = settings.dashcamBrand
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // logo 是炭灰線條，墊一個米白圓底，深色主題下也看得到
        Box(Modifier.size(104.dp).background(Color(0xFFF7F2EC), CircleShape), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(104.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text("尚未連線到記錄器", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Step(1, "確認記錄器已開機、Wi-Fi 已開啟")
            Step(2, "按下方按鈕，在系統視窗選擇 ${settings.ssidPrefixFor(brand)} 開頭的 Wi-Fi")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "連線後手機的行動網路照常可用；也可以先在系統設定連上，App 會自動偵測",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        SingleChoiceSegmentedButtonRow {
            Brand.dashcams.forEachIndexed { i, b ->
                SegmentedButton(
                    selected = brand == b,
                    onClick = { scope.launch { container.settings.update { it.copy(dashcamBrand = b) } } },
                    shape = SegmentedButtonDefaults.itemShape(i, Brand.dashcams.size),
                    enabled = state != ConnState.Requesting,
                ) { Text(b.label) }
            }
        }
        Spacer(Modifier.height(12.dp))
        (state as? ConnState.Failed)?.let {
            Text(it.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
        }
        if (state == ConnState.Requesting) {
            CircularProgressIndicator()
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { container.connection.cancelRequest() }) { Text("取消") }
        } else {
            Button(onClick = { container.connection.connect() }) { Text("連線到 ${brand.label} 記錄器") }
        }
    }
}

/** 有編號的說明步驟：數字放在主色淡底的圓裡。 */
@Composable
private fun Step(number: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(24.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    "$number",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 空狀態與錯誤的共用版面：圖示放在主色淡底的圓裡，下面是標題、說明與動作。 */
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
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(88.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Spacer(Modifier.height(20.dp))
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
