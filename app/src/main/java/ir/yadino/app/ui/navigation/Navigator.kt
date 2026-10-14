package ir.yadino.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import ir.yadino.domain.model.StartScreen

/** Back stack of typed routes. */
@Stable
class Navigator(initial: List<Route>, private val startTab: () -> Route.Tab) {
    val backStack = mutableStateListOf<Route>().apply { addAll(initial.ifEmpty { listOf(startTab()) }) }

    val current: Route get() = backStack.last()

    fun navigate(route: Route) {
        if (route is Route.Tab) {
            backStack.clear()
            backStack.add(route)
        } else {
            backStack.add(route)
        }
    }

    /** Replaces the current destination (e.g. editor -> detail after saving). */
    fun replace(route: Route) {
        if (backStack.isNotEmpty()) backStack.removeAt(backStack.lastIndex)
        backStack.add(route)
    }

    /** Returns false when the app should close. */
    fun back(): Boolean {
        if (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
            return true
        }
        val start = startTab()
        val cur = current
        if (cur is Route.Tab && cur::class != start::class) {
            backStack.clear()
            backStack.add(start)
            return true
        }
        return false
    }

    /** Stable keys of the entries (index + route). */
    fun keys(): List<String> = backStack.mapIndexed { i, r -> "$i|${r.encode()}" }

    companion object {
        fun saver(startTab: () -> Route.Tab): Saver<Navigator, ArrayList<String>> = Saver(
            save = { nav -> ArrayList(nav.backStack.map { it.encode() }) },
            restore = { saved -> Navigator(saved.mapNotNull { Route.decode(it) }, startTab) },
        )

        fun tabFor(start: StartScreen): Route.Tab = when (start) {
            StartScreen.DASHBOARD -> Route.Dashboard
            StartScreen.CALENDAR -> Route.Calendar()
            StartScreen.TASKS -> Route.Tasks()
            StartScreen.NOTES -> Route.Notes
            StartScreen.GOALS -> Route.Goals()
        }
    }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("Navigator not provided") }

/**
 * Activity-scoped holder of one [ViewModelStore] per back-stack entry, so that screen
 * ViewModels are cleared when their destination is popped (like Navigation Compose).
 */
class DestinationStores : ViewModel() {
    private val stores = HashMap<String, ViewModelStore>()

    fun owner(key: String): ViewModelStoreOwner {
        val store = stores.getOrPut(key) { ViewModelStore() }
        return object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore get() = store
        }
    }

    fun retainOnly(keys: Collection<String>) {
        val remove = stores.keys - keys.toSet()
        remove.forEach { stores.remove(it)?.clear() }
    }

    override fun onCleared() {
        stores.values.forEach { it.clear() }
        stores.clear()
    }
}

@Composable
fun DestinationScope(stores: DestinationStores, key: String, content: @Composable () -> Unit) {
    val owner = stores.owner(key)
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { content() }
}

@Composable
fun PruneStores(stores: DestinationStores, keys: List<String>) {
    DisposableEffect(keys) {
        stores.retainOnly(keys)
        onDispose { }
    }
}
