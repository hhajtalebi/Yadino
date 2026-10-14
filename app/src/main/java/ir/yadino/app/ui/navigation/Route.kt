package ir.yadino.app.ui.navigation

import android.net.Uri

/**
 * Typed navigation destinations. Routes are encoded as strings so they can be saved in
 * the back stack across process death and passed through notification/widget intents.
 */
sealed interface Route {
    /** Top level destinations shown in the bottom bar. */
    sealed interface Tab : Route

    data object Dashboard : Tab
    data class Calendar(val epochDay: Long? = null) : Tab
    data class Tasks(val filter: String? = null) : Tab
    data object Notes : Tab
    data class Goals(val habitsTab: Boolean = false) : Tab

    data class TaskEditor(
        val taskId: String? = null,
        val date: Long? = null,
        val kind: String? = null,
        val fromNoteId: String? = null,
        val goalId: String? = null,
    ) : Route

    data class NoteEditor(val noteId: String? = null, val goalId: String? = null, val restoreDraft: Boolean = false) : Route
    data class GoalEditor(val goalId: String? = null) : Route
    data class GoalDetail(val goalId: String) : Route
    data class HabitEditor(val habitId: String? = null, val goalId: String? = null) : Route
    data class HabitDetail(val habitId: String) : Route
    data object Reports : Route
    data object Search : Route
    data object Settings : Route
    data object SettingsGeneral : Route
    data object SettingsCalendar : Route
    data object SettingsNotifications : Route
    data object SettingsNotesGoals : Route
    data object SettingsWidgets : Route
    data object SettingsData : Route
    data object SettingsAdvanced : Route
    data object Categories : Route
    data object DashboardCustomize : Route
    data object About : Route
    data object Templates : Route

    data class TemplateEditor(val templateId: String? = null) : Route

    fun encode(): String {
        val b = Uri.Builder().scheme("yadino")
        when (this) {
            Dashboard -> b.authority("dashboard")
            is Calendar -> b.authority("calendar").q("d", epochDay)
            is Tasks -> b.authority("tasks").q("f", filter)
            Notes -> b.authority("notes")
            is Goals -> b.authority("goals").q("h", if (habitsTab) "1" else null)
            is TaskEditor -> b.authority("task").q("id", taskId).q("d", date).q("k", kind).q("n", fromNoteId).q("g", goalId)
            is NoteEditor -> b.authority("note").q("id", noteId).q("g", goalId).q("r", if (restoreDraft) "1" else null)
            is GoalEditor -> b.authority("goal-edit").q("id", goalId)
            is GoalDetail -> b.authority("goal").q("id", goalId)
            is HabitEditor -> b.authority("habit-edit").q("id", habitId).q("g", goalId)
            is HabitDetail -> b.authority("habit").q("id", habitId)
            Reports -> b.authority("reports")
            Search -> b.authority("search")
            Settings -> b.authority("settings")
            SettingsGeneral -> b.authority("settings-general")
            SettingsCalendar -> b.authority("settings-calendar")
            SettingsNotifications -> b.authority("settings-notifications")
            SettingsNotesGoals -> b.authority("settings-notes")
            SettingsWidgets -> b.authority("settings-widgets")
            SettingsData -> b.authority("settings-data")
            SettingsAdvanced -> b.authority("settings-advanced")
            Categories -> b.authority("categories")
            DashboardCustomize -> b.authority("dashboard-customize")
            About -> b.authority("about")
            Templates -> b.authority("templates")
            is TemplateEditor -> b.authority("template").q("id", templateId)
        }
        return b.build().toString()
    }

    companion object {
        private fun Uri.Builder.q(key: String, value: Any?): Uri.Builder =
            if (value == null) this else appendQueryParameter(key, value.toString())

        fun decode(text: String?): Route? {
            if (text.isNullOrBlank()) return null
            val uri = runCatching { Uri.parse(text) }.getOrNull() ?: return null
            if (uri.scheme != "yadino") return null
            fun p(k: String) = uri.getQueryParameter(k)
            return when (uri.authority) {
                "dashboard" -> Dashboard
                "calendar" -> Calendar(p("d")?.toLongOrNull())
                "tasks" -> Tasks(p("f"))
                "notes" -> Notes
                "goals" -> Goals(p("h") == "1")
                "task" -> TaskEditor(p("id"), p("d")?.toLongOrNull(), p("k"), p("n"), p("g"))
                "note" -> NoteEditor(p("id"), p("g"), p("r") == "1")
                "goal-edit" -> GoalEditor(p("id"))
                "goal" -> p("id")?.let { GoalDetail(it) }
                "habit-edit" -> HabitEditor(p("id"), p("g"))
                "habit" -> p("id")?.let { HabitDetail(it) }
                "reports" -> Reports
                "search" -> Search
                "settings" -> Settings
                "settings-general" -> SettingsGeneral
                "settings-calendar" -> SettingsCalendar
                "settings-notifications" -> SettingsNotifications
                "settings-notes" -> SettingsNotesGoals
                "settings-widgets" -> SettingsWidgets
                "settings-data" -> SettingsData
                "settings-advanced" -> SettingsAdvanced
                "categories" -> Categories
                "dashboard-customize" -> DashboardCustomize
                "about" -> About
                "templates" -> Templates
                "template" -> TemplateEditor(p("id"))
                else -> null
            }
        }
    }
}
