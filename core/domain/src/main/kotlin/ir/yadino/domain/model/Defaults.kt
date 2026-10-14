package ir.yadino.domain.model

import ir.yadino.domain.notes.Checklist
import java.time.Instant
import java.util.UUID

/** Identifier generation. */
object Ids {
    fun newId(): String = UUID.randomUUID().toString()
}

/** Categories created on first launch. Users can rename or delete them. */
object DefaultCategories {
    data class Template(val key: String, val name: String, val color: NoteColor, val kind: CategoryKind)

    val templates = listOf(
        Template("work", "کار", NoteColor.SKY, CategoryKind.GENERAL),
        Template("personal", "شخصی", NoteColor.GREEN, CategoryKind.GENERAL),
        Template("study", "مطالعه و یادگیری", NoteColor.LAVENDER, CategoryKind.GENERAL),
        Template("health", "سلامت و ورزش", NoteColor.ROSE, CategoryKind.GENERAL),
        Template("family", "خانواده", NoteColor.GOLD, CategoryKind.GENERAL),
        Template("finance", "مالی", NoteColor.SAND, CategoryKind.GENERAL),
        Template("ideas", "ایده‌ها", NoteColor.CREAM, CategoryKind.NOTE),
    )

    /** Stable ids so that default categories are not duplicated when merging backups. */
    fun idFor(key: String) = "default-category-$key"

    fun create(now: Instant): List<Category> = templates.mapIndexed { index, t ->
        Category(idFor(t.key), t.name, t.color, t.kind, isDefault = true, sortOrder = index, createdAt = now, updatedAt = now)
    }
}

/** Title (null = no title line) and body of a note card; see [NoteFilters.card]. */
data class NoteCardText(val title: String?, val body: String)

object NoteFilters {
    fun search(notes: List<Note>, query: String): List<Note> {
        val q = query.trim()
        if (q.isEmpty()) return notes
        return notes.filter { n ->
            n.title.contains(q, ignoreCase = true) || n.content.contains(q, ignoreCase = true) ||
                n.tags.any { it.contains(q, ignoreCase = true) }
        }
    }

    /** Pinned notes first, then the requested order. */
    fun sort(notes: List<Note>, sort: NoteSort): List<Note> {
        val comparator: Comparator<Note> = when (sort) {
            NoteSort.UPDATED_DESC -> compareByDescending { it.updatedAt }
            NoteSort.CREATED_DESC -> compareByDescending { it.createdAt }
            NoteSort.TITLE_ASC -> compareBy<Note> { it.title.ifBlank { Checklist.plainText(it.content).trim() }.lowercase() }
            NoteSort.COLOR -> compareBy<Note> { it.color.ordinal }.thenByDescending { it.updatedAt }
        }
        return notes.sortedWith(compareByDescending<Note> { it.isPinned }.then(comparator))
    }

    /**
     * What a card shows for a note: an optional title line and the body below it.
     * - With a title: the title and the whole body.
     * - Untitled checklist: no title line, so every item keeps its checkbox.
     * - Untitled text: the first line becomes the title and is not repeated below, unless it
     *   had to be shortened (then the body is shown in full so nothing is hidden).
     */
    fun card(note: Note, maxTitleLength: Int = 60): NoteCardText {
        val title = note.title.trim()
        if (title.isNotEmpty()) return NoteCardText(title, note.content)
        if (Checklist.hasItems(note.content)) return NoteCardText(null, note.content)
        val lines = note.content.split('\n')
        val first = lines.indexOfFirst { it.isNotBlank() }
        if (first < 0) return NoteCardText("", "")
        val line = lines[first].trim()
        return if (line.length > maxTitleLength) {
            NoteCardText(line.take(maxTitleLength).trimEnd() + "…", note.content.trim())
        } else {
            NoteCardText(line, lines.drop(first + 1).joinToString("\n").trim())
        }
    }

    /** First line used as a title for untitled notes (checklist syntax removed). */
    fun displayTitle(note: Note, maxLength: Int = 60): String {
        val t = note.title.trim()
        if (t.isNotEmpty()) return t
        val firstLine = Checklist.plainText(note.content).trim().lineSequence().firstOrNull()?.trim().orEmpty()
        return if (firstLine.length > maxLength) firstLine.take(maxLength).trimEnd() + "…" else firstLine
    }
}
