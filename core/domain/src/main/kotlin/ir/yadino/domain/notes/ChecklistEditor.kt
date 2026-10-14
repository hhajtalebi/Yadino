package ir.yadino.domain.notes

/**
 * Editing model behind the note editor.
 *
 * The body is a list of blocks with stable keys so the UI keeps focus and cursor state while
 * items are inserted, split, converted or removed. Operations that move the cursor return a
 * [Focus] for the UI. [content] always serializes back to plain note text (see [Checklist]).
 */
class ChecklistEditor(content: String = "") {
    data class Entry(val key: Long, val block: NoteBlock)

    /** Where the cursor should go: [cursor] is an offset into the block text, null = end. */
    data class Focus(val key: Long, val cursor: Int? = null)

    private var nextKey = 1L

    var entries: List<Entry> = emptyList()
        private set

    init {
        reset(content)
    }

    /** Replaces the whole body (loading a note, restoring a draft, undo). Keys are renewed. */
    fun reset(content: String) {
        entries = Checklist.parse(content).map { entry(it) }.ifEmpty { listOf(entry(NoteBlock.Text(""))) }
    }

    fun content(): String = Checklist.serialize(entries.map { it.block })

    val hasItems: Boolean get() = entries.any { it.block is NoteBlock.Item }

    /** (checked, total) or null without items. */
    fun progress(): Pair<Int, Int>? {
        val items = entries.mapNotNull { it.block as? NoteBlock.Item }
        return if (items.isEmpty()) null else items.count { it.checked } to items.size
    }

    private fun entry(block: NoteBlock) = Entry(nextKey++, block)

    private fun indexOf(key: Long?) = if (key == null) -1 else entries.indexOfFirst { it.key == key }

    /** Never leave the editor without a block to type into. */
    private fun commit(list: MutableList<Entry>) {
        if (list.isEmpty()) list += entry(NoteBlock.Text(""))
        entries = list
    }

    /**
     * Text typed into a block. Inside an item a line break (Enter or a multi-line paste) starts
     * new items; Enter on an empty item leaves the list and continues as normal text.
     */
    fun editText(key: Long, text: String): Focus? {
        val i = indexOf(key)
        if (i < 0) return null
        val list = entries.toMutableList()
        when (val b = list[i].block) {
            is NoteBlock.Text -> list[i] = list[i].copy(block = b.copy(text = text))
            is NoteBlock.Item -> {
                val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
                if ('\n' !in normalized) {
                    list[i] = list[i].copy(block = b.copy(text = normalized))
                } else {
                    val parts = normalized.split('\n')
                    if (b.text.isEmpty() && parts.all { it.isEmpty() } && parts.size == 2) {
                        list[i] = list[i].copy(block = NoteBlock.Text(""))
                        commit(list)
                        return Focus(key, 0)
                    }
                    list[i] = list[i].copy(block = b.copy(text = parts.first()))
                    val rest = parts.drop(1)
                    val added = if (rest.all { it.isBlank() }) {
                        listOf(NoteBlock.Item(""))
                    } else {
                        rest.filter { it.isNotBlank() }.map { Checklist.parseLine(it) ?: NoteBlock.Item(it.trim()) }
                    }.map { entry(it) }
                    list.addAll(i + 1, added)
                    commit(list)
                    return Focus(added.last().key, if (rest.size == 1) 0 else null)
                }
            }
        }
        commit(list)
        return null
    }

    fun toggle(key: Long) {
        val i = indexOf(key)
        val b = entries.getOrNull(i)?.block as? NoteBlock.Item ?: return
        entries = entries.toMutableList().also { it[i] = it[i].copy(block = b.copy(checked = !b.checked)) }
    }

    /** Removes a block; returns the block before it (for backspace on an empty item). */
    fun remove(key: Long): Focus? {
        val i = indexOf(key)
        if (i < 0) return null
        val list = entries.toMutableList()
        list.removeAt(i)
        commit(list)
        return Focus(entries[(i - 1).coerceAtLeast(0)].key)
    }

    /** Appends an empty item at the end; a trailing blank text block is replaced by it. */
    fun appendItem(): Focus {
        val list = entries.toMutableList()
        val last = list.lastOrNull()?.block
        if (last is NoteBlock.Text && last.text.isBlank()) list.removeAt(list.lastIndex)
        val added = entry(NoteBlock.Item(""))
        list += added
        commit(list)
        return Focus(added.key)
    }

    data class ToggleResult(val focus: Focus?, val toText: Boolean, val droppedChecks: Boolean)

    /**
     * The checklist button.
     * - Text focused: the paragraph under the cursor (or every selected line) becomes items;
     *   a blank line becomes one empty item.
     * - Item focused: its run of consecutive items turns back into text lines, merged with
     *   neighbouring text.
     * - Nothing focused: a note without items is converted line by line; otherwise a new empty
     *   item is appended.
     */
    fun toggleChecklist(focusKey: Long?, selectionStart: Int = 0, selectionEnd: Int = selectionStart): ToggleResult {
        val i = indexOf(focusKey)
        if (i < 0) {
            if (hasItems) return ToggleResult(appendItem(), toText = false, droppedChecks = false)
            val all = entries.joinToString("\n") { (it.block as NoteBlock.Text).text }
            val items = Checklist.linesToItems(all).map { entry(it) }
            commit(items.toMutableList())
            return ToggleResult(Focus(items.last().key), toText = false, droppedChecks = false)
        }
        return when (val b = entries[i].block) {
            is NoteBlock.Text -> ToggleResult(textToItems(i, b.text, selectionStart, selectionEnd), toText = false, droppedChecks = false)
            is NoteBlock.Item -> itemsToText(i)
        }
    }

    private fun textToItems(index: Int, text: String, selStart: Int, selEnd: Int): Focus {
        val lines = text.split('\n')
        val starts = IntArray(lines.size)
        for (k in 1 until lines.size) starts[k] = starts[k - 1] + lines[k - 1].length + 1
        fun lineAt(offset: Int): Int {
            val o = offset.coerceIn(0, text.length)
            var k = 0
            while (k + 1 < lines.size && starts[k + 1] <= o) k++
            return k
        }
        val s = minOf(selStart, selEnd)
        val e = maxOf(selStart, selEnd)
        var from = lineAt(s)
        var to = lineAt(e)
        if (s == e && lines[from].isNotBlank()) {
            while (from > 0 && lines[from - 1].isNotBlank()) from--
            while (to < lines.lastIndex && lines[to + 1].isNotBlank()) to++
        }
        val list = entries.toMutableList()
        val replacement = mutableListOf<Entry>()
        if (from > 0) replacement += entries[index].copy(block = NoteBlock.Text(lines.subList(0, from).joinToString("\n")))
        val items = Checklist.linesToItems(lines.subList(from, to + 1).joinToString("\n")).map { entry(it) }
        replacement += items
        if (to < lines.lastIndex) replacement += entry(NoteBlock.Text(lines.subList(to + 1, lines.size).joinToString("\n")))
        list.removeAt(index)
        list.addAll(index, replacement)
        commit(list)
        return Focus(items.last().key)
    }

    private fun itemsToText(index: Int): ToggleResult {
        var from = index
        var to = index
        while (from > 0 && entries[from - 1].block is NoteBlock.Item) from--
        while (to < entries.lastIndex && entries[to + 1].block is NoteBlock.Item) to++
        val run = entries.subList(from, to + 1).map { it.block as NoteBlock.Item }
        val focusedLine = index - from
        var text = run.joinToString("\n") { it.text }
        var cursor = run.take(focusedLine + 1).sumOf { it.text.length + 1 } - 1
        var key = entries[index].key
        val list = entries.toMutableList()
        var start = from
        var end = to
        (entries.getOrNull(from - 1)?.block as? NoteBlock.Text)?.let { before ->
            text = before.text + "\n" + text
            cursor += before.text.length + 1
            key = entries[from - 1].key
            start = from - 1
        }
        (entries.getOrNull(to + 1)?.block as? NoteBlock.Text)?.let { after ->
            text = text + "\n" + after.text
            end = to + 1
        }
        for (k in end downTo start) list.removeAt(k)
        list.add(start, Entry(key, NoteBlock.Text(text)))
        commit(list)
        return ToggleResult(Focus(key, cursor), toText = true, droppedChecks = run.any { it.checked })
    }

    /** Removes checked items; returns how many were removed. */
    fun clearChecked(): Int {
        val kept = entries.filterNot { (it.block as? NoteBlock.Item)?.checked == true }
        val removed = entries.size - kept.size
        if (removed > 0) commit(kept.toMutableList())
        return removed
    }

    /** Unchecks every item; returns how many changed. */
    fun uncheckAll(): Int {
        var changed = 0
        entries = entries.map { e ->
            val b = e.block
            if (b is NoteBlock.Item && b.checked) {
                changed++
                e.copy(block = b.copy(checked = false))
            } else {
                e
            }
        }
        return changed
    }
}
