package tw.com.recora.ui.common

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** 設定頁的一張卡片：標題（可帶圖示）加內容（「裝置」頁與「App 設定」頁共用）。 */
@Composable
fun Section(title: String, icon: ImageVector? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    SettingRow(title, subtitle = subtitle) {
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/**
 * 選項設定：點一下展開選項，選了和目前不同的才回呼 [onSelect]。
 * [selectedLabel] 預設是目前選項的名稱（目前的值不在選項裡時可以自己給，例如顯示原始值）。
 */
@Composable
fun <T> ChoiceRow(
    title: String,
    options: List<T>,
    selected: T?,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    hint: String? = null,
    enabled: Boolean = true,
    selectedLabel: String = selected?.let(optionLabel) ?: "?",
    optionEnabled: (T) -> Boolean = { true },
) {
    var open by remember { mutableStateOf(false) }
    Box {
        SettingRow(title, subtitle = hint, onClick = if (enabled) ({ open = true }) else null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(selectedLabel, color = MaterialTheme.colorScheme.primary)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    leadingIcon = if (option == selected) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    enabled = optionEnabled(option),
                    onClick = {
                        open = false
                        if (option != selected) onSelect(option)
                    },
                )
            }
        }
    }
}
