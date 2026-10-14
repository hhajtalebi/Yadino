package ir.yadino.app.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ir.yadino.app.R
import ir.yadino.app.ui.theme.LocalAnimationScale
import ir.yadino.app.ui.theme.LocalFormatter
import ir.yadino.app.ui.theme.LocalIsDark
import ir.yadino.app.ui.theme.accent
import ir.yadino.app.ui.theme.container
import ir.yadino.domain.model.Category
import ir.yadino.domain.model.NoteColor
import ir.yadino.domain.model.Priority

@Composable
fun animDuration(base: Int): Int = (base * LocalAnimationScale.current).toInt()

/** Top app bar with an optional back button. */
@Composable
fun YadinoTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            }
        },
        actions = actions,
        scrollBehavior = scrollBehavior,
    )
}

/** Rounded card with a header row used by the dashboard and detail screens. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth().animateContentSize(tween(animDuration(250))),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                if (icon != null) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (actionLabel != null && onAction != null) {
                    TextButton(onClick = onAction) { Text(actionLabel) }
                }
            }
            content()
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, message: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirmText, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Single choice dialog used by settings and editors. */
@Composable
fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T?,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    description: (@Composable (T) -> String?)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn {
                items(options) { option ->
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(selected = option == selected, role = Role.RadioButton) { onSelect(option); onDismiss() }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(label(option), style = MaterialTheme.typography.bodyLarge)
                            description?.invoke(option)?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
fun priorityLabel(p: Priority): String = stringResource(
    when (p) {
        Priority.LOW -> R.string.priority_low
        Priority.MEDIUM -> R.string.priority_medium
        Priority.HIGH -> R.string.priority_high
        Priority.URGENT -> R.string.priority_urgent
    },
)

@Composable
fun priorityColor(p: Priority): Color {
    val dark = LocalIsDark.current
    return when (p) {
        Priority.LOW -> MaterialTheme.colorScheme.outline
        Priority.MEDIUM -> MaterialTheme.colorScheme.primary
        Priority.HIGH -> if (dark) Color(0xFFE3C66F) else Color(0xFFB08A10)
        Priority.URGENT -> if (dark) Color(0xFFF08A80) else Color(0xFFC0392B)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrioritySelector(selected: Priority, onSelect: (Priority) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Priority.entries.forEach { p ->
            FilterChip(
                selected = p == selected,
                onClick = { onSelect(p) },
                label = { Text(priorityLabel(p)) },
                leadingIcon = { Icon(Icons.Default.Flag, null, tint = priorityColor(p), modifier = Modifier.size(18.dp)) },
            )
        }
    }
}

@Composable
fun ColorDot(color: Color, size: Dp = 10.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}

@Composable
fun noteColorName(c: NoteColor): String = stringResource(
    when (c) {
        NoteColor.DEFAULT -> R.string.color_default
        NoteColor.CREAM -> R.string.color_cream
        NoteColor.GREEN -> R.string.color_green
        NoteColor.GOLD -> R.string.color_gold
        NoteColor.ROSE -> R.string.color_rose
        NoteColor.SKY -> R.string.color_sky
        NoteColor.LAVENDER -> R.string.color_lavender
        NoteColor.SAND -> R.string.color_sand
    },
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoteColorPicker(selected: NoteColor, onSelect: (NoteColor) -> Unit, modifier: Modifier = Modifier) {
    val dark = LocalIsDark.current
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NoteColor.entries.forEach { c ->
            val name = noteColorName(c)
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(c.container(dark))
                    .border(if (c == selected) 3.dp else 1.dp, if (c == selected) c.accent(dark) else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .clickable(role = Role.RadioButton) { onSelect(c) }
                    .semantics { contentDescription = name },
                contentAlignment = Alignment.Center,
            ) {
                if (c == selected) Icon(Icons.Default.Check, null, tint = c.accent(dark), modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Free text tag editor: type and press enter/comma to add. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagEditor(tags: List<String>, onChange: (List<String>) -> Unit, modifier: Modifier = Modifier) {
    var input by remember { mutableStateOf("") }
    fun commit() {
        val parts = input.split(',', '،', '\n').map { it.trim().trimStart('#') }.filter { it.isNotEmpty() }
        if (parts.isNotEmpty()) onChange((tags + parts).distinctBy { it.lowercase() })
        input = ""
    }
    Column(modifier) {
        OutlinedTextField(
            value = input,
            onValueChange = { v -> if (v.endsWith(",") || v.endsWith("،")) { input = v.dropLast(1); commit() } else input = v },
            label = { Text(stringResource(R.string.tags)) },
            placeholder = { Text(stringResource(R.string.tags_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier.fillMaxWidth(),
        )
        if (tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                tags.forEach { tag ->
                    InputChip(
                        selected = false,
                        onClick = { onChange(tags - tag) },
                        label = { Text("#$tag") },
                        trailingIcon = { Icon(Icons.Default.Close, stringResource(R.string.remove), Modifier.size(16.dp)) },
                    )
                }
            }
        }
    }
}

/** Dropdown to choose a category (or none). */
@Composable
fun CategoryPicker(categories: List<Category>, selectedId: String?, onSelect: (String?) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val dark = LocalIsDark.current
    val selected = categories.firstOrNull { it.id == selectedId }
    Box(modifier) {
        AssistChip(
            onClick = { open = true },
            label = { Text(selected?.name ?: stringResource(R.string.no_category)) },
            leadingIcon = { ColorDot(selected?.color?.accent(dark) ?: MaterialTheme.colorScheme.outline) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.no_category)) }, onClick = { onSelect(null); open = false })
            categories.forEach { c ->
                DropdownMenuItem(
                    text = { Text(c.name) },
                    leadingIcon = { ColorDot(c.color.accent(dark)) },
                    onClick = { onSelect(c.id); open = false },
                )
            }
        }
    }
}

/** Circular progress with percentage in the centre. */
@Composable
fun ProgressRing(percent: Int, size: Dp, stroke: Dp = 8.dp, color: Color = MaterialTheme.colorScheme.primary, label: String? = null) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fmt = LocalFormatter.current
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = stroke.toPx()
            val d = this.size.minDimension - s
            val topLeft = Offset((this.size.width - d) / 2, (this.size.height - d) / 2)
            drawArc(track, 0f, 360f, false, topLeft, Size(d, d), style = Stroke(s, cap = StrokeCap.Round))
            drawArc(color, -90f, 360f * percent.coerceIn(0, 100) / 100f, false, topLeft, Size(d, d), style = Stroke(s, cap = StrokeCap.Round))
        }
        Text(label ?: fmt.percent(percent), style = if (size > 80.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

/** Simple vertical bar chart (values >= 0) with labels under the bars. */
@Composable
fun BarChart(values: List<Int>, labels: List<String>, modifier: Modifier = Modifier, highlightIndex: Int? = null, height: Dp = 140.dp) {
    val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val fmt = LocalFormatter.current
    Row(modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
        values.forEachIndexed { i, v ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                if (v > 0) Text(fmt.number(v), style = MaterialTheme.typography.labelSmall)
                val fraction = v.toFloat() / max
                Box(
                    Modifier.fillMaxWidth(0.7f).height(((height.value - 40) * fraction).coerceAtLeast(2f).dp)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .background(if (i == highlightIndex) secondary else primary.copy(alpha = if (v == 0) 0.25f else 0.85f)),
                )
                Text(labels.getOrElse(i) { "" }, style = MaterialTheme.typography.labelSmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Small rounded label. */
@Composable
fun Pill(text: String, color: Color = MaterialTheme.colorScheme.secondaryContainer, textColor: Color = MaterialTheme.colorScheme.onSecondaryContainer) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = textColor,
        modifier = Modifier.clip(MaterialTheme.shapes.small).background(color).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

val ScreenPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp)

/** Screen scaffold; window insets are handled by the app shell. */
@Composable
fun YadinoScaffold(
    topBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    floatingActionButton: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    androidx.compose.material3.Scaffold(
        modifier = modifier,
        topBar = topBar,
        bottomBar = bottomBar,
        floatingActionButton = floatingActionButton,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0.dp),
        content = content,
    )
}
