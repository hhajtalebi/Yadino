package ir.yadino.app.ui.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import ir.yadino.app.R
import ir.yadino.app.ui.theme.LocalFormatter
import ir.yadino.domain.notes.Checklist
import ir.yadino.domain.notes.ChecklistEditor
import ir.yadino.domain.notes.NoteBlock

/**
 * Which body block has focus and where its cursor is, so the checklist button can act on the
 * paragraph under the cursor. Plain holder: updating it must not recompose the editor.
 */
class NoteBodyFocus {
    var focusedKey: Long? = null
    val selections = HashMap<Long, TextRange>()
    val selection: TextRange get() = focusedKey?.let { selections[it] } ?: TextRange.Zero
}

/** Callbacks from the body editor to the view model. */
interface NoteBodyActions {
    fun editBlock(key: Long, text: String)
    fun toggleItem(key: Long)
    fun removeBlock(key: Long, moveFocus: Boolean)
    fun addItem()
    fun consumeFocus()
}

/**
 * Note body: text paragraphs as borderless fields and checklist items as rows with a checkbox.
 * A note without items looks exactly like a single large text field.
 */
@Composable
fun NoteBodyEditor(
    blocks: List<ChecklistEditor.Entry>,
    focusRequest: ChecklistEditor.Focus?,
    focus: NoteBodyFocus,
    actions: NoteBodyActions,
    fieldColors: TextFieldColors,
) {
    val onlyText = blocks.size == 1 && blocks[0].block is NoteBlock.Text
    val items = blocks.mapNotNull { it.block as? NoteBlock.Item }
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        blocks.forEach { entry ->
            key(entry.key) {
                val request = focusRequest?.takeIf { it.key == entry.key }
                when (val b = entry.block) {
                    is NoteBlock.Text -> TextBlockField(entry.key, b.text, onlyText, request, focus, actions, fieldColors)
                    is NoteBlock.Item -> ItemRow(entry.key, b, request, focus, actions)
                }
            }
        }
        if (items.isNotEmpty()) {
            val fmt = LocalFormatter.current
            val done = items.count { it.checked }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                TextButton(onClick = actions::addItem) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.checklist_add_item))
                }
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.checklist_progress, fmt.number(done), fmt.number(items.size)),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
            }
            LinearProgressIndicator(
                progress = { done.toFloat() / items.size },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

/** Keeps a local [TextFieldValue] (cursor, IME composition) in step with the block text. */
@Composable
private fun rememberBlockValue(blockKey: Long, text: String): Pair<TextFieldValue, (TextFieldValue) -> Unit> {
    var local by remember(blockKey) { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    val shown = if (local.text == text) local else TextFieldValue(text, TextRange(text.length))
    return shown to { v: TextFieldValue -> local = v }
}

@Composable
private fun FocusOnRequest(request: ChecklistEditor.Focus?, requester: FocusRequester, text: String, setValue: (TextFieldValue) -> Unit, actions: NoteBodyActions) {
    LaunchedEffect(request) {
        if (request == null) return@LaunchedEffect
        val cursor = (request.cursor ?: text.length).coerceIn(0, text.length)
        setValue(TextFieldValue(text, TextRange(cursor)))
        // The node can be detached if the block disappeared in the same frame.
        runCatching { requester.requestFocus() }
        actions.consumeFocus()
    }
}

@Composable
private fun TextBlockField(
    blockKey: Long, text: String, onlyText: Boolean, request: ChecklistEditor.Focus?,
    focus: NoteBodyFocus, actions: NoteBodyActions, colors: TextFieldColors,
) {
    val requester = remember { FocusRequester() }
    val (value, setValue) = rememberBlockValue(blockKey, text)
    FocusOnRequest(request, requester, text, setValue, actions)
    TextField(
        value = value,
        onValueChange = { v ->
            setValue(v)
            focus.selections[blockKey] = v.selection
            if (v.text != text) actions.editBlock(blockKey, v.text)
        },
        placeholder = if (onlyText) ({ Text(stringResource(R.string.note_body_hint)) }) else null,
        textStyle = MaterialTheme.typography.bodyLarge,
        colors = colors,
        minLines = if (onlyText) 10 else 1,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth().focusRequester(requester).onFocusChanged { s ->
            if (s.isFocused) {
                focus.focusedKey = blockKey
                focus.selections[blockKey] = value.selection
            } else if (focus.focusedKey == blockKey) {
                focus.focusedKey = null
            }
        },
    )
}

@Composable
private fun ItemRow(blockKey: Long, item: NoteBlock.Item, request: ChecklistEditor.Focus?, focus: NoteBodyFocus, actions: NoteBodyActions) {
    val requester = remember { FocusRequester() }
    val (value, setValue) = rememberBlockValue(blockKey, item.text)
    FocusOnRequest(request, requester, item.text, setValue, actions)
    val emptyLabel = stringResource(R.string.checklist_item_hint)
    val colors = MaterialTheme.colorScheme
    val style: TextStyle = MaterialTheme.typography.bodyLarge.merge(
        if (item.checked) TextStyle(color = colors.outline, textDecoration = TextDecoration.LineThrough) else TextStyle(color = colors.onSurface),
    )
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 4.dp)) {
        Checkbox(
            checked = item.checked,
            onCheckedChange = { actions.toggleItem(blockKey) },
            modifier = Modifier.semantics { contentDescription = item.text.ifBlank { emptyLabel } },
        )
        BasicTextField(
            value = value,
            onValueChange = { v ->
                // A line break splits the item; the view model answers with the new layout.
                if ('\n' !in v.text) setValue(v)
                focus.selections[blockKey] = v.selection
                if (v.text != item.text) actions.editBlock(blockKey, v.text)
            },
            textStyle = style,
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 10.dp)
                .focusRequester(requester)
                .onFocusChanged { s ->
                    if (s.isFocused) focus.focusedKey = blockKey else if (focus.focusedKey == blockKey) focus.focusedKey = null
                }
                .onPreviewKeyEvent { e ->
                    // Hardware keyboards: backspace on an empty item removes it.
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Backspace && value.text.isEmpty()) {
                        actions.removeBlock(blockKey, moveFocus = true)
                        true
                    } else {
                        false
                    }
                },
            decorationBox = { inner ->
                Box {
                    if (value.text.isEmpty()) Text(emptyLabel, style = MaterialTheme.typography.bodyLarge, color = colors.outline)
                    inner()
                }
            },
        )
        IconButton(onClick = { actions.removeBlock(blockKey, moveFocus = false) }) {
            Icon(Icons.Default.Close, stringResource(R.string.checklist_remove_item), Modifier.size(18.dp), tint = colors.outline)
        }
    }
}

/**
 * Read-only preview of a note body for cards: checklist items as ☐ / ☑ (checked ones struck
 * through), plain text unchanged.
 */
@Composable
fun NoteBodyPreview(content: String, maxLines: Int, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val doneColor = MaterialTheme.colorScheme.outline
    val text: AnnotatedString = remember(content, doneColor) { previewText(content, doneColor) }
    Text(text, style = MaterialTheme.typography.bodySmall, maxLines = maxLines, overflow = TextOverflow.Ellipsis, color = color, modifier = modifier)
}

private fun previewText(content: String, doneColor: Color): AnnotatedString = buildAnnotatedString {
    Checklist.parse(content).forEachIndexed { i, b ->
        if (i > 0) append('\n')
        when (b) {
            is NoteBlock.Text -> append(b.text)
            is NoteBlock.Item -> if (b.checked) {
                append(Checklist.CHECKED_SYMBOL + " ")
                withStyle(SpanStyle(color = doneColor, textDecoration = TextDecoration.LineThrough)) { append(b.text) }
            } else {
                append(Checklist.UNCHECKED_SYMBOL + " " + b.text)
            }
        }
    }
}

/** "۲ از ۵ انجام شده" for notes with a checklist. */
@Composable
fun ChecklistProgressLabel(content: String, modifier: Modifier = Modifier) {
    val progress = remember(content) { Checklist.progress(content) } ?: return
    val fmt = LocalFormatter.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = modifier) {
        Text(
            stringResource(R.string.checklist_progress, fmt.number(progress.first), fmt.number(progress.second)),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
        )
    }
}
