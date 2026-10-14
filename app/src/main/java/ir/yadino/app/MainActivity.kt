package ir.yadino.app

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.yadino.app.notifications.NotificationHelper
import ir.yadino.app.ui.YadinoAppUi
import ir.yadino.app.ui.navigation.Navigator
import ir.yadino.app.ui.navigation.Route
import ir.yadino.app.ui.onboarding.OnboardingScreen
import ir.yadino.app.ui.theme.YadinoTheme
import kotlinx.coroutines.launch
import java.util.Locale

/** Wraps contexts so resources, layout direction and system components use Persian. */
object LocaleHelper {
    val PERSIAN: Locale = Locale("fa", "IR")

    fun wrap(base: Context): Context {
        val config = Configuration(base.resources.configuration)
        config.setLocale(PERSIAN)
        config.setLayoutDirection(PERSIAN)
        return base.createConfigurationContext(config)
    }
}

class MainActivity : ComponentActivity() {

    private val pendingRoute = mutableStateOf<Route?>(null)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) pendingRoute.value = Route.decode(intent?.getStringExtra(NotificationHelper.EXTRA_ROUTE))
        val app = application as YadinoApplication
        val container = app.container
        setContent {
            val loaded by container.settings.state.collectAsStateWithLifecycle()
            val settings = loaded
            if (settings == null) {
                Box(Modifier.fillMaxSize().background(Color.Transparent))
                return@setContent
            }
            YadinoTheme(settings) {
                val startTab = { Navigator.tabFor(container.settings.currentOrDefault().startScreen) }
                val navigator = rememberSaveable(saver = Navigator.saver(startTab)) { Navigator(emptyList(), startTab) }
                val route = pendingRoute.value
                LaunchedEffect(route) {
                    if (route != null) {
                        navigator.navigate(route)
                        pendingRoute.value = null
                    }
                }
                val state by container.appState.status.collectAsStateWithLifecycle(initialValue = null)
                val s = state
                when {
                    s == null -> Box(Modifier.fillMaxSize())
                    !s.onboardingDone -> OnboardingScreen(onDone = { app.appScope.launch { container.appState.setOnboardingDone() } })
                    else -> YadinoAppUi(container, navigator, onExit = { finish() })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Route.decode(intent.getStringExtra(NotificationHelper.EXTRA_ROUTE))?.let { pendingRoute.value = it }
    }
}
