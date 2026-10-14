package ir.yadino.app.ui.notes

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import ir.yadino.app.R
import ir.yadino.app.data.local.entity.NoteDraftEntity
import ir.yadino.app.di.AppContainer
import ir.yadino.app.ui.LocalContainer
import ir.yadino.app.ui.common.CollectMessages
import ir.yadino.app.ui.common.UiMessage
import ir.yadino.app.ui.common.YadinoViewModel
import ir.yadino.app.ui.components.CategoryPicker
import ir.yadino.app.ui.components.ConfirmDialog
import ir.yadino.app.ui.components.EmptyState
import ir.yadino.app.ui.components.NoteColorPicker
import ir.yadino.app.ui.components.TagEditor
import ir.yadino.app.ui.components.YadinoScaffold
import ir.yadino.app.ui.components.YadinoTopBar
import ir.yadino.app.ui.navigation.LocalNavigator
import ir.yadino.app.ui.navigation.Route
import ir.yadino.app.ui.theme.LocalAppSettings
import ir.yadino.app.ui.theme.LocalFormatter
import ir.yadino.app.ui.theme.LocalIsDark
import ir.yadino.app.ui.theme.container
import ir.yadino.domain.model.Category
import ir.yadino.domain.model.Goal
import ir.yadino.domain.model.Ids
import ir.yadino.domain.model.ListLayout
import ir.yadino.domain.model.Note
import ir.yadino.domain.model.NoteColor
import ir.yadino.domain.model.NoteFilters
import ir.yadino.domain.model.NoteSort
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

enum class NoteFilter { ACTIVE, PINNED, ARCHIVED }

data class NotesState(
    val notes: List<Note> = emptyList(),
    val filter: NoteFilter = NoteFilter.ACTIVE,
    val query: String = "",
    val categoryId: String? = null,
    val categories: List<Category> = emptyList(),
    val drafts: List<NoteDraftEntity> = emptyList(),
    val loaded: Boolean = false,
)

class NotesViewModel(c: AppContainer) : YadinoViewModel(c) {
    private val filter = MutableStateFlow(NoteFilter.ACTIVE)
    private val query = MutableStateFlow("")
    private val category = MutableStateFlow<String?>(null)
    private val drafts = MutableStateFlow<List<NoteDraftEntity>>(emptyList())

    init {
        viewModelScope.launch { c.notes.draftCount.collect { drafts.value = c.notes.allDrafts() } }
    }

    val state: StateFlow<NotesState> = combine(
        c.notes.notes, c.settings.settings, combine(filter, query, category) { f, q, cat -> Triple(f, q, cat) }, c.categories.categories, drafts,
    ) { notes, settings, f, cats, d ->
        val (fl, q, cat) = f
        val base = when (fl) {
            NoteFilter.ACTIVE -> notes.filter { !it.isArchived }
            NoteFilter.PINNED -> notes.filter { it.isPinned && !it.isArchived }
            NoteFilter.ARCHIVED -> notes.filter { it.isArchived }
        }
        val list = NoteFilters.sort(NoteFilters.search(base, q).filter { cat == null || it.categoryId == cat }, settings.noteSort)
        NotesState(list, fl, q, cat, cats, d, true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NotesState())

    fun setFilter(f: NoteFilter) { filter.value = f }
    fun setQuery(q: String) { query.value = q }
    fun setCategory(id: String?) { category.value = id }
    fun setSort(s: NoteSort) = viewModelScope.launch { c.settings.update { it.copy(noteSort = s) } }
    fun setLayout(l: ListLayout) = viewModelScope.launch { c.settings.update { it.copy(noteLayout = l) } }
    fun togglePin(n: Note) = launchSafe { c.notes.setPinned(n, !n.isPinned) }
    fun discardDraft(key: String) = launchSafe { c.notes.clearDraft(key); drafts.value = c.notes.allDrafts() }
}

@Composable
fun sortLabel(s: NoteSort): String = stringResource(
    when (s) {
        NoteSort.UPDATED_DESC -> R.string.sort_updated
        NoteSort.CREATED_DESC -> R.string.sort_created
        NoteSort.TITLE_ASC -> R.string.sort_title
        NoteSort.COLOR -> R.string.sort_color
    },
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotesScreen() {
    val c = LocalContainer.current
    val vm: NotesViewModel = viewModel { NotesViewModel(c) }
    val state by vm.state.collectAsStateWithLifecycle()
    val settings = LocalAppSettings.current
    val nav = LocalNavigator.current
    val dark = LocalIsDark.current
    val fmt = LocalFormatter.current
    CollectMessages(vm.messages)
    var searching by rememberSaveable { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }

    YadinoScaffold(
        topBar = {
            if (searching) {
                TextField(
                    value = state.query, onValueChange = vm::setQuery, placeholder = { Text(stringResource(R.string.search_notes)) }, singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = { IconButton(onClick = { vm.setQuery(""); searching = false }) { Icon(Icons.Default.Close, stringResource(R.string.close)) } },
                    colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth().padding(top = 32.dp, start = 8.dp, end = 8.dp),
                )
            } else {
                YadinoTopBar(stringResource(R.string.tab_notes)) {
                    IconButton(onClick = { searching = true }) { Icon(Icons.Default.Search, stringResource(R.string.search)) }
                    IconButton(onClick = { vm.setLayout(if (settings.noteLayout == ListLayout.GRID) ListLayout.LIST else ListLayout.GRID) }) {
                        Icon(if (settings.noteLayout == ListLayout.GRID) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView, stringResource(R.string.toggle_layout))
                    }
                    Box {
                        IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.sort)) }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            NoteSort.entries.forEach { s ->
                                DropdownMenuItem(text = { Text(sortLabel(s)) }, onClick = { vm.setSort(s); sortMenu = false },
                                    leadingIcon = { if (settings.noteSort == s) Icon(Icons.Default.Check, null) })
                            }
                        }
                    }
                }
            }
        },
        floatingActionButton = { FloatingActionButton(onClick = { nav.navigate(Route.NoteEditor()) }) { Icon(Icons.Default.Add, stringResource(R.string.new_note)) } },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(NoteFilter.entries) { f ->
                    FilterChip(state.filter == f, { vm.setFilter(f) }, {
                        Text(stringResource(when (f) {
                            NoteFilter.ACTIVE -> R.string.notes_all
                            NoteFilter.PINNED -> R.string.notes_pinned
                            NoteFilter.ARCHIVED -> R.string.notes_archived
                        }))
                    })
                }
                item { CategoryPicker(state.categories, state.categoryId, vm::setCategory) }
            }
            if (state.drafts.isNotEmpty()) {
                Card(Modifier.fillMaxWidth().padding(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Restore, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.drafts_title), style = MaterialTheme.typography.titleSmall)
                        }
                        Text(stringResource(R.string.drafts_message), style = MaterialTheme.typography.bodySmall)
                        state.drafts.forEach { d ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(d.title.ifBlank { d.content.lineSequence().firstOrNull().orEmpty() }.ifBlank { stringResource(R.string.untitled_note) },
                                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                TextButton(onClick = { nav.navigate(Route.NoteEditor(noteId = d.noteKey.takeIf { it != "new" }, restoreDraft = true)) }) {
                                    Text(stringResource(R.string.restore))
                                }
                                TextButton(onClick = { vm.discardDraft(d.noteKey) }) { Text(stringResource(R.string.discard)) }
                            }
                        }
                    }
                }
            }
            if (state.loaded && state.notes.isEmpty()) {
                EmptyState(Icons.AutoMirrored.Filled.StickyNote2, stringResource(R.string.notes_empty_title), stringResource(R.string.notes_empty_message))
            }
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(if (settings.noteLayout == ListLayout.GRID) 2 else 1),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalItemSpacing = 8.dp,
            ) {
                items(state.notes, key = { it.id }) { n ->
                    Card(
                        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { nav.navigate(Route.NoteEditor(n.id)) }, onLongClick = { vm.togglePin(n) }),
                        colors = CardDefaults.cardColors(containerColor = n.color.container(dark)),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.Top) {
                                Text(NoteFilters.displayTitle(n).ifBlank { stringResource(R.string.untitled_note) }, style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (n.isPinned) Icon(Icons.Default.PushPin, stringResource(R.string.pinned), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
                            }
                            if (n.title.isNotBlank() && n.content.isNotBlank()) {
                                Text(n.content, style = MaterialTheme.typography.bodySmall, maxLines = 8, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (n.tags.isNotEmpty()) Text(n.tags.joinToString(" ") { "#$it" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text(fmt.relativeDay(n.updatedAt.atZone(ZoneId.systemDefault()).toLocalDate(), c.clock.today()), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        }
    }
}

// =============================================================================================
// Editor with autosave and crash-safe drafts
// =============================================================================================
data class NoteEditorState(
    val loaded: Boolean = false,
    val note: Note? = null,
    val persisted: Boolean = false,
    val savedAt: Instant? = null,
    val dirty: Boolean = false,
    val draftAvailable: NoteDraftEntity? = null,
)

class NoteEditorViewModel(c: AppContainer, private val route: Route.NoteEditor) : YadinoViewModel(c) {
    private val _state = MutableStateFlow(NoteEditorState())
    val state: StateFlow<NoteEditorState> = _state.asStateFlow()
    val categories: StateFlow<List<Category>> = c.categories.categories.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val goals: StateFlow<List<Goal>> = c.goals.goals.map { l -> l.filter { !it.isArchived } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val closed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var autosaveJob: Job? = null
    private var draftJob: Job? = null

    private val draftKey: String get() = if (_state.value.persisted) _state.value.note!!.id else "new"

    init {
        viewModelScope.launch {
            val settings = c.settings.awaitLoaded()
            val existing = route.noteId?.let { c.notes.get(it) }
            if (route.noteId != null && existing == null && c.notes.getDraft(route.noteId) == null) {
                message(UiMessage.Text(R.string.item_not_found))
                closed.tryEmit(Unit)
                return@launch
            }
            val now = c.clock.now()
            val note = existing ?: Note(Ids.newId(), "", "", settings.defaultNoteColor, goalId = route.goalId, createdAt = now, updatedAt = now)
            val draft = c.notes.getDraft(existing?.id ?: "new")
            val usable = draft?.takeIf { existing == null || it.savedAt > existing.updatedAt.toEpochMilli() }
            _state.value = NoteEditorState(loaded = true, note = note, persisted = existing != null, savedAt = existing?.updatedAt)
            if (usable != null) {
                if (route.restoreDraft) applyDraft(usable) else _state.value = _state.value.copy(draftAvailable = usable)
            }
        }
    }

    fun applyDraft(d: NoteDraftEntity) {
        val n = _state.value.note ?: return
        update {
            n.copy(title = d.title, content = d.content, color = NoteColor.entries.firstOrNull { it.name == d.color } ?: n.color,
                categoryId = d.categoryId, tags = d.tags.split("\n").filter { it.isNotBlank() })
        }
        _state.value = _state.value.copy(draftAvailable = null)
    }

    fun dismissDraft() = viewModelScope.launch {
        c.notes.clearDraft(draftKey)
        _state.value = _state.value.copy(draftAvailable = null)
    }

    fun update(transform: (Note) -> Note) {
        val n = _state.value.note ?: return
        _state.value = _state.value.copy(note = transform(n), dirty = true)
        scheduleDraft()
        scheduleAutosave()
    }

    private fun scheduleDraft() {
        draftJob?.cancel()
        draftJob = viewModelScope.launch {
            delay(300)
            val n = _state.value.note ?: return@launch
            if (_state.value.dirty && !n.isBlank) c.notes.saveDraft(draftKey, n)
        }
    }

    private fun scheduleAutosave() {
        val settings = c.settings.currentOrDefault()
        if (!settings.noteAutosave) return
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(settings.noteAutosaveDelayMs)
            persist(showMessage = false)
        }
    }

    /** Writes the note to the database and clears the draft. */
    suspend fun persist(showMessage: Boolean): Boolean {
        val s = _state.value
        val n = s.note ?: return false
        if (!s.dirty && s.persisted) return true
        return try {
            val oldKey = draftKey
            val stored = c.notes.save(n)
            if (stored == null) {
                c.notes.clearDraft(oldKey)
                false
            } else {
                _state.value = _state.value.copy(note = _state.value.note?.copy(updatedAt = stored.updatedAt), persisted = true, savedAt = stored.updatedAt,
                    dirty = _state.value.note != n)
                c.notes.clearDraft(oldKey)
                c.notes.clearDraft(stored.id)
                if (showMessage) message(UiMessage.Text(R.string.msg_note_saved))
                true
            }
        } catch (e: ir.yadino.app.data.repository.ValidationException) {
            message(UiMessage.Validation(e.errors))
            false
        } catch (e: Exception) {
            ir.yadino.app.core.ErrorReporter.log(c.app, e, "note-save")
            message(UiMessage.Failure)
            false
        }
    }

    fun saveNow() = viewModelScope.launch { persist(true) }

    /** Called when the screen goes to background: never lose input. */
    fun flush() {
        val settings = c.settings.currentOrDefault()
        c.appScope.launch {
            val n = _state.value.note ?: return@launch
            if (!_state.value.dirty) return@launch
            if (settings.noteAutosave) persist(false) else if (!n.isBlank) c.notes.saveDraft(draftKey, n)
        }
    }

    fun saveAndClose() = viewModelScope.launch {
        autosaveJob?.cancel()
        val s = _state.value
        if (s.note?.isBlank == true && !s.persisted) {
            c.notes.clearDraft("new")
            closed.tryEmit(Unit)
            return@launch
        }
        if (persist(false) || !_state.value.dirty) closed.tryEmit(Unit)
    }

    fun discardAndClose() = viewModelScope.launch {
        autosaveJob?.cancel()
        draftJob?.cancel()
        c.notes.clearDraft(draftKey)
        closed.tryEmit(Unit)
    }

    fun delete() = launchSafe {
        autosaveJob?.cancel()
        draftJob?.cancel()
        val n = _state.value.note ?: return@launchSafe
        c.notes.clearDraft("new")
        if (_state.value.persisted) c.notes.delete(n.id)
        closed.tryEmit(Unit)
    }

    fun convertToTask(onReady: (String) -> Unit) = viewModelScope.launch {
        if (persist(false) || _state.value.persisted) _state.value.note?.id?.let(onReady)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoteEditorScreen(route: Route.NoteEditor) {
    val c = LocalContainer.current
    val vm: NoteEditorViewModel = viewModel { NoteEditorViewModel(c, route) }
    val state by vm.state.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val goals by vm.goals.collectAsStateWithLifecycle()
    val settings = LocalAppSettings.current
    val nav = LocalNavigator.current
    val fmt = LocalFormatter.current
    val dark = LocalIsDark.current
    val context = LocalContext.current
    CollectMessages(vm.messages)
    LaunchedEffect(vm) { vm.closed.collect { nav.back() } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) vm.flush() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var menu by remember { mutableStateOf(false) }
    var showColors by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var goalMenu by remember { mutableStateOf(false) }
    BackHandler { if (!settings.noteAutosave && state.dirty) confirmDiscard = true else vm.saveAndClose() }

    val note = state.note
    YadinoScaffold(
        modifier = Modifier,
        topBar = {
            YadinoTopBar("", onBack = { if (!settings.noteAutosave && state.dirty) confirmDiscard = true else vm.saveAndClose() }) {
                if (note != null) {
                    IconButton(onClick = { vm.update { it.copy(isPinned = !it.isPinned) } }) {
                        Icon(if (note.isPinned) Icons.Default.PushPin else Icons.Outlined.PushPin, stringResource(if (note.isPinned) R.string.unpin else R.string.pin))
                    }
                    IconButton(onClick = { showColors = !showColors }) { Icon(Icons.Default.Palette, stringResource(R.string.color)) }
                    if (!settings.noteAutosave) IconButton(onClick = vm::saveNow) { Icon(Icons.Default.Check, stringResource(R.string.save)) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.convert_to_task)) }, leadingIcon = { Icon(Icons.Default.TaskAlt, null) },
                                onClick = { menu = false; vm.convertToTask { id -> nav.navigate(Route.TaskEditor(fromNoteId = id)) } })
                            DropdownMenuItem(text = { Text(stringResource(R.string.share)) }, leadingIcon = { Icon(Icons.Default.Share, null) }, onClick = {
                                menu = false
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, listOf(note.title, note.content).filter { it.isNotBlank() }.joinToString("\n\n"))
                                }
                                context.startActivity(Intent.createChooser(send, context.getString(R.string.share)))
                            })
                            DropdownMenuItem(text = { Text(stringResource(if (note.isArchived) R.string.unarchive else R.string.archive)) },
                                leadingIcon = { Icon(if (note.isArchived) Icons.Default.Unarchive else Icons.Default.Archive, null) },
                                onClick = { menu = false; vm.update { it.copy(isArchived = !it.isArchived, isPinned = false) } })
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menu = false; confirmDelete = true })
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (note == null) return@YadinoScaffold
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            state.draftAvailable?.let { d ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.draft_found_message, fmt.dateTime(Instant.ofEpochMilli(d.savedAt), c.clock.zone())))
                        Row {
                            TextButton(onClick = { vm.applyDraft(d) }) { Text(stringResource(R.string.restore)) }
                            TextButton(onClick = { vm.dismissDraft() }) { Text(stringResource(R.string.discard)) }
                        }
                    }
                }
            }
            if (showColors) NoteColorPicker(note.color, { col -> vm.update { it.copy(color = col) } }, Modifier.padding(vertical = 8.dp))
            val fieldColors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            )
            Card(colors = CardDefaults.cardColors(containerColor = note.color.container(dark)), modifier = Modifier.fillMaxWidth()) {
                TextField(note.title, { v -> vm.update { it.copy(title = v) } }, placeholder = { Text(stringResource(R.string.title)) },
                    textStyle = MaterialTheme.typography.titleLarge, colors = fieldColors, modifier = Modifier.fillMaxWidth())
                TextField(note.content, { v -> vm.update { it.copy(content = v) } }, placeholder = { Text(stringResource(R.string.note_body_hint)) },
                    textStyle = MaterialTheme.typography.bodyLarge, colors = fieldColors, minLines = 10, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.size(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CategoryPicker(categories, note.categoryId, { id -> vm.update { it.copy(categoryId = id) } })
                Box {
                    AssistChip(onClick = { goalMenu = true }, label = { Text(goals.firstOrNull { it.id == note.goalId }?.title ?: stringResource(R.string.link_to_goal)) },
                        leadingIcon = { Icon(Icons.Default.Flag, null, Modifier.size(18.dp)) })
                    DropdownMenu(expanded = goalMenu, onDismissRequest = { goalMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.no_goal)) }, onClick = { vm.update { it.copy(goalId = null) }; goalMenu = false })
                        goals.forEach { g -> DropdownMenuItem(text = { Text(g.title) }, onClick = { vm.update { it.copy(goalId = g.id) }; goalMenu = false }) }
                    }
                }
            }
            TagEditor(note.tags, { tags -> vm.update { it.copy(tags = tags) } }, Modifier.padding(vertical = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                Icon(if (state.dirty) Icons.Default.Edit else Icons.Default.CloudDone, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.width(6.dp))
                Text(
                    when {
                        state.dirty && settings.noteAutosave -> stringResource(R.string.saving)
                        state.dirty -> stringResource(R.string.unsaved_changes)
                        state.savedAt != null -> stringResource(R.string.saved_at, fmt.dateTime(state.savedAt!!, c.clock.zone()))
                        else -> stringResource(R.string.new_note)
                    },
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                )
            }
            Spacer(Modifier.size(80.dp))
        }
    }
    if (confirmDelete) {
        ConfirmDialog(stringResource(R.string.delete_note), stringResource(R.string.delete_note_message), stringResource(R.string.delete),
            onConfirm = { vm.delete() }, onDismiss = { confirmDelete = false }, destructive = true)
    }
    if (confirmDiscard) {
        ConfirmDialog(stringResource(R.string.discard_changes), stringResource(R.string.discard_note_message), stringResource(R.string.discard),
            onConfirm = { vm.discardAndClose() }, onDismiss = { confirmDiscard = false }, destructive = true)
    }
}

@Suppress("unused")
private val unusedClickable = Modifier.clickable { }
