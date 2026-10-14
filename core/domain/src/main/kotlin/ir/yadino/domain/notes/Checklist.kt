package ir.yadino.domain.notes

import ir.yadino.domain.validation.Limits

/** A note body is a sequence of text paragraphs and checklist items. */
sealed interface NoteBlock {
    data class Text(val text: String) : NoteBlock
    data class Item(val text: String, val checked: Boolean = false) : NoteBlock
}

/** Checklist items of a note body that can become subtasks. */
data class ChecklistSplit(val text: String, val items: List<NoteBlock.Item>)

/**
 * Checklists inside note bodies.
 *
 * Items are stored as GitHub-style task list lines, so a note body always stays plain text
 * (database, backups, sharing and older app versions are unaffected):
 * ```
 * خرید هفته
 * - [ ] شیر
 * - [x] نان
 * ```
 * Every other line is ordinary text. Reading also accepts `*` bullets, `[X]` and a missing
 * space after the brackets; writing always uses `- [ ] ` and `- [x] `.
 */
object Checklist {
    const val UNCHECKED_PREFIX = "- [ ] "
    const val CHECKED_PREFIX = "- [x] "
    const val UNCHECKED_SYMBOL = "☐"
    const val CHECKED_SYMBOL = "☑"

    private val ITEM = Regex("""^[ \t]*[-*][ \t]\[([ xX])\][ \t]?(.*)$""")

    /** The item written on [line], or null for an ordinary text line. */
    fun parseLine(line: String): NoteBlock.Item? =
        ITEM.matchEntire(line.removeSuffix("\r"))?.let { m -> NoteBlock.Item(m.groupValues[2], m.groupValues[1] != " ") }

    /** Splits a body into blocks; consecutive non-item lines form one text block. */
    fun parse(content: String): List<NoteBlock> {
        val blocks = mutableListOf<NoteBlock>()
        val text = mutableListOf<String>()
        fun flush() {
            if (text.isNotEmpty()) {
                blocks += NoteBlock.Text(text.joinToString("\n"))
                text.clear()
            }
        }
        for (line in content.split('\n')) {
            val parsed = parseLine(line)
            if (parsed != null) {
                flush()
                blocks += parsed
            } else {
                text += line
            }
        }
        flush()
        return blocks
    }

    /** Inverse of [parse]. Line breaks inside an item are replaced by spaces. */
    fun serialize(blocks: List<NoteBlock>): String = blocks.joinToString("\n") { b ->
        when (b) {
            is NoteBlock.Text -> b.text
            is NoteBlock.Item -> (if (b.checked) CHECKED_PREFIX else UNCHECKED_PREFIX) + b.text.replace('\n', ' ').replace('\r', ' ')
        }
    }

    fun items(content: String): List<NoteBlock.Item> = content.split('\n').mapNotNull { parseLine(it) }

    fun hasItems(content: String): Boolean = content.split('\n').any { parseLine(it) != null }

    /** (checked, total) or null when the body has no checklist. */
    fun progress(content: String): Pair<Int, Int>? {
        val all = items(content)
        return if (all.isEmpty()) null else all.count { it.checked } to all.size
    }

    /** Toggles the [index]-th item (counting items only). Out of range leaves the body unchanged. */
    fun toggle(content: String, index: Int): String {
        var i = -1
        return content.split('\n').joinToString("\n") { line ->
            val parsed = parseLine(line) ?: return@joinToString line
            i++
            if (i == index) (if (parsed.checked) UNCHECKED_PREFIX else CHECKED_PREFIX) + parsed.text else line
        }
    }

    /** Body without checklist syntax: items become their bare text (titles, sorting). */
    fun plainText(content: String): String = parse(content).joinToString("\n") { b ->
        when (b) {
            is NoteBlock.Text -> b.text
            is NoteBlock.Item -> b.text
        }
    }

    /** Body for display and sharing: items shown with ☐ / ☑. */
    fun displayText(content: String): String = parse(content).joinToString("\n") { b ->
        when (b) {
            is NoteBlock.Text -> b.text
            is NoteBlock.Item -> (if (b.checked) CHECKED_SYMBOL else UNCHECKED_SYMBOL) + " " + b.text
        }
    }

    /** Text paragraphs and items separately (note → task with subtasks). Empty items are dropped. */
    fun split(content: String): ChecklistSplit {
        val blocks = parse(content)
        val text = blocks.filterIsInstance<NoteBlock.Text>().joinToString("\n") { it.text }.trim()
        val items = blocks.filterIsInstance<NoteBlock.Item>()
            .filter { it.text.isNotBlank() }
            .map { it.copy(text = it.text.trim().take(Limits.TITLE)) }
        return ChecklistSplit(text, items)
    }

    /**
     * Every non-blank line of [text] becomes an item: unchecked, or as written when the line
     * already uses checklist syntax. An empty text gives one empty item.
     */
    fun linesToItems(text: String): List<NoteBlock.Item> {
        val lines = text.split('\n').filter { it.isNotBlank() }
        return if (lines.isEmpty()) listOf(NoteBlock.Item("")) else lines.map { parseLine(it) ?: NoteBlock.Item(it.trim()) }
    }

    /** Removes checked items. */
    fun clearChecked(blocks: List<NoteBlock>): List<NoteBlock> = blocks.filterNot { it is NoteBlock.Item && it.checked }

    /** Unchecks every item (reusing a shopping or packing list). */
    fun uncheckAll(blocks: List<NoteBlock>): List<NoteBlock> = blocks.map { if (it is NoteBlock.Item) it.copy(checked = false) else it }
}
