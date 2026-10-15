package tw.com.recora.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import tw.com.recora.ui.theme.Scrim
import tw.com.recora.ui.theme.recora
import tw.com.recora.ui.theme.tabular

/** 依日期分組的清單標題（黏在頂端），右邊的按鈕可以全選這一天。 */
@Composable
fun DateHeader(title: String, onSelectAll: () -> Unit, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onSelectAll) {
                Icon(Icons.Filled.SelectAll, contentDescription = "全選這一天")
            }
        }
    }
}

/** 小標籤（前／後鏡頭、檔案大小…）。[downloaded] 時右邊多一個打勾。 */
@Composable
fun MediaBadge(
    label: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceVariant,
    content: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    downloaded: Boolean = false,
) {
    Surface(shape = MaterialTheme.shapes.extraSmall, color = container, contentColor = content, modifier = modifier) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            if (downloaded) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "已下載",
                    modifier = Modifier.padding(start = 3.dp).size(12.dp),
                    tint = MaterialTheme.recora.success,
                )
            }
        }
    }
}

/** 刪除前的確認：帶警示圖示，確認鈕用錯誤色。[content] 放說明文字與額外選項。 */
@Composable
fun ConfirmDeleteDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(title) },
        text = content,
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("刪除", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 多選模式的頂列：標題顯示選了多少，左邊的 X 取消選取，[actions] 放各畫面自己的動作。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(
    title: String,
    onClose: () -> Unit,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit,
) {
    TopAppBar(
        title = {
            Column {
                Text(title)
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "取消選取") }
        },
        actions = actions,
    )
}

/** 疊在影片、照片上方的頂列：背景是由上往下淡出的黑色漸層，白字在任何畫面上都讀得到。 */
@Composable
fun OverlayTopBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Scrim.strong, Color.Transparent)))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** 疊在影片上的小按鈕（半透明黑底白字；[selected] 時用主色）。 */
@Composable
fun OverlayChip(label: String, icon: ImageVector, selected: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.primary else Scrim.light,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White,
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * 縮圖：載入前、載入失敗時露出底下的佔位圖示，載入成功後圖片蓋過它。
 * [model] 傳 Coil 認得的東西（網址、[MediaThumb]…）。
 */
@Composable
fun Thumbnail(
    model: Any?,
    placeholder: ImageVector,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.small,
) {
    Box(
        modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            placeholder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(24.dp),
        )
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
    }
}

/** 設定列：左邊標題加說明，右邊放開關、目前的值之類的 [trailing]；給了 [onClick] 整列都可以點。 */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing()
    }
}
