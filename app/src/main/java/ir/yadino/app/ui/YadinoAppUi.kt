package ir.yadino.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ir.yadino.app.R
import ir.yadino.app.di.AppContainer
import ir.yadino.app.ui.calendar.CalendarScreen
import ir.yadino.app.ui.components.animDuration
import ir.yadino.app.ui.dashboard.DashboardCustomizeScreen
import ir.yadino.app.ui.dashboard.DashboardScreen
import ir.yadino.app.ui.goals.GoalDetailScreen
import ir.yadino.app.ui.goals.GoalEditorScreen
import ir.yadino.app.ui.goals.GoalsScreen
import ir.yadino.app.ui.habits.HabitDetailScreen
import ir.yadino.app.ui.habits.HabitEditorScreen
import ir.yadino.app.ui.navigation.DestinationScope
import ir.yadino.app.ui.navigation.DestinationStores
import ir.yadino.app.ui.navigation.LocalNavigator
import ir.yadino.app.ui.navigation.Navigator
import ir.yadino.app.ui.navigation.PruneStores
import ir.yadino.app.ui.navigation.Route
import ir.yadino.app.ui.notes.NoteEditorScreen
import ir.yadino.app.ui.notes.NotesScreen
import ir.yadino.app.ui.reports.ReportsScreen
import ir.yadino.app.ui.reports.SearchScreen
import ir.yadino.app.ui.settings.AboutScreen
import ir.yadino.app.ui.settings.CategoriesScreen
import ir.yadino.app.ui.settings.SettingsAdvancedScreen
import ir.yadino.app.ui.settings.SettingsCalendarScreen
import ir.yadino.app.ui.settings.SettingsDataScreen
import ir.yadino.app.ui.settings.SettingsGeneralScreen
import ir.yadino.app.ui.settings.SettingsNotesGoalsScreen
import ir.yadino.app.ui.settings.SettingsNotificationsScreen
import ir.yadino.app.ui.settings.SettingsScreen
import ir.yadino.app.ui.settings.SettingsWidgetsScreen
import ir.yadino.app.ui.tasks.TaskEditorScreen
import ir.yadino.app.ui.tasks.TasksScreen
import ir.yadino.app.ui.templates.TemplateEditorScreen
import ir.yadino.app.ui.templates.TemplatesScreen

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("container not provided") }
val LocalSnackbar = staticCompositionLocalOf { SnackbarHostState() }

private data class TabItem(val route: Route.Tab, val label: Int, val icon: ImageVector, val selectedIcon: ImageVector)

private val tabs = listOf(
    TabItem(Route.Dashboard, R.string.tab_dashboard, Icons.Outlined.Home, Icons.Filled.Home),
    TabItem(Route.Calendar(), R.string.tab_calendar, Icons.Outlined.CalendarMonth, Icons.Filled.CalendarMonth),
    TabItem(Route.Tasks(), R.string.tab_tasks, Icons.Outlined.CheckCircle, Icons.Filled.CheckCircle),
    TabItem(Route.Notes, R.string.tab_notes, Icons.AutoMirrored.Outlined.StickyNote2, Icons.AutoMirrored.Filled.StickyNote2),
    TabItem(Route.Goals(), R.string.tab_goals, Icons.Outlined.Flag, Icons.Filled.Flag),
)

@Composable
fun YadinoAppUi(container: AppContainer, navigator: Navigator, onExit: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    val stores: DestinationStores = viewModel()
    CompositionLocalProvider(LocalContainer provides container, LocalNavigator provides navigator, LocalSnackbar provides snackbar) {
        BackHandler { if (!navigator.back()) onExit() }
        val keys = navigator.keys()
        PruneStores(stores, keys)
        val current = navigator.current
        val key = keys.last()
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            androidx.compose.material3.Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = { if (current is Route.Tab) BottomBar(current, navigator) },
                contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0.dp),
            ) { padding ->
                Box(
                    Modifier.padding(padding).consumeWindowInsets(padding)
                        .then(if (current is Route.Tab) Modifier else Modifier.navigationBarsPadding())
                        .imePadding(),
                ) {
                    val duration = animDuration(220)
                    AnimatedContent(
                        targetState = key,
                        transitionSpec = { fadeIn(tween(duration)) togetherWith fadeOut(tween(duration / 2)) },
                        label = "screens",
                    ) { k ->
                        val route = Route.decode(k.substringAfter('|')) ?: Route.Dashboard
                        DestinationScope(stores, k) { Screen(route) }
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomBar(current: Route.Tab, navigator: Navigator) {
    NavigationBar {
        tabs.forEach { tab ->
            val selected = tab.route::class == current::class
            NavigationBarItem(
                selected = selected,
                onClick = { if (!selected) navigator.navigate(tab.route) },
                icon = { Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = null) },
                label = { Text(stringResource(tab.label)) },
            )
        }
    }
}

@Composable
private fun Screen(route: Route) {
    when (route) {
        Route.Dashboard -> DashboardScreen()
        is Route.Calendar -> CalendarScreen(route)
        is Route.Tasks -> TasksScreen(route)
        Route.Notes -> NotesScreen()
        is Route.Goals -> GoalsScreen(route)
        is Route.TaskEditor -> TaskEditorScreen(route)
        is Route.NoteEditor -> NoteEditorScreen(route)
        is Route.GoalEditor -> GoalEditorScreen(route)
        is Route.GoalDetail -> GoalDetailScreen(route)
        is Route.HabitEditor -> HabitEditorScreen(route)
        is Route.HabitDetail -> HabitDetailScreen(route)
        Route.Reports -> ReportsScreen()
        Route.Search -> SearchScreen()
        Route.Settings -> SettingsScreen()
        Route.SettingsGeneral -> SettingsGeneralScreen()
        Route.SettingsCalendar -> SettingsCalendarScreen()
        Route.SettingsNotifications -> SettingsNotificationsScreen()
        Route.SettingsNotesGoals -> SettingsNotesGoalsScreen()
        Route.SettingsWidgets -> SettingsWidgetsScreen()
        Route.SettingsData -> SettingsDataScreen()
        Route.SettingsAdvanced -> SettingsAdvancedScreen()
        Route.Categories -> CategoriesScreen()
        Route.DashboardCustomize -> DashboardCustomizeScreen()
        Route.About -> AboutScreen()
        Route.Templates -> TemplatesScreen()
        is Route.TemplateEditor -> TemplateEditorScreen(route)
    }
}
