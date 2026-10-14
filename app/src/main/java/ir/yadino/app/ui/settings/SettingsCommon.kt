package ir.yadino.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import ir.yadino.app.di.AppContainer
import ir.yadino.app.ui.common.YadinoViewModel
import ir.yadino.app.ui.components.ChoiceDialog
import ir.yadino.app.ui.components.TimePickerDialog
import ir.yadino.app.ui.components.YadinoScaffold
import ir.yadino.app.ui.components.YadinoTopBar
import ir.yadino.app.ui.navigation.LocalNavigator
import ir.yadino.app.ui.theme.LocalFormatter
import ir.yadino.domain.model.AppSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalTime

/** Shared settings ViewModel: every change is persisted immediately and takes effect app-wide. */
open class SettingsViewModel(c: AppContainer) : YadinoViewModel(c) {
    val settings: StateFlow<AppSettings> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, c.settings.currentOrDefault())
    fun update(transform: (AppSettings) -> AppSettings) = viewModelScope.launch { c.settings.update(transform) }
}

@Composable
fun SettingsPage(title: String, content: @Composable ColumnScope.() -> Unit) {
    val nav = LocalNavigator.current
    YadinoScaffold(topBar = { YadinoTopBar(title, onBack = { nav.back() }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
            Spacer(Modifier.size(48.dp))
        }
    }
}

@Composable
fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(vertical = 4.dp), content = content)
        }
    }
}

@Composable
fun SettingRow(
    title: String,
    description: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
fun SwitchSetting(title: String, description: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
fun <T> ChoiceSetting(
    title: String,
    description: String?,
    value: T,
    options: List<T>,
    label: @Composable (T) -> String,
    enabled: Boolean = true,
    optionDescription: (@Composable (T) -> String?)? = null,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    SettingRow(title, description, enabled = enabled, onClick = { open = true }, trailing = {
        Text(label(value), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    })
    if (open) ChoiceDialog(title, options, value, label, onSelect, { open = false }, optionDescription)
}

@Composable
fun TimeSetting(title: String, description: String?, value: LocalTime?, enabled: Boolean = true, allowClear: Boolean = false, clearLabel: String = "", onChange: (LocalTime?) -> Unit) {
    val fmt = LocalFormatter.current
    var open by remember { mutableStateOf(false) }
    SettingRow(title, description, enabled = enabled, onClick = { open = true }, trailing = {
        Text(value?.let { fmt.time(it) } ?: clearLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    })
    if (open) TimePickerDialog(value ?: LocalTime.of(9, 0), { open = false }, { onChange(it) }, onClear = if (allowClear) ({ onChange(null) }) else null, title = title)
}

@Composable
fun SliderSetting(title: String, description: String?, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, valueLabel: (Float) -> String, onChange: (Float) -> Unit) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(valueLabel(local), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = local, onValueChange = { local = it }, onValueChangeFinished = { onChange(local) }, valueRange = range, steps = steps)
    }
}
