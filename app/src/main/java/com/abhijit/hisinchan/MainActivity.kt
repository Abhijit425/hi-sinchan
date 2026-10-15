package com.abhijit.hisinchan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.abhijit.hisinchan.ui.MainViewModel
import com.abhijit.hisinchan.ui.UiEvent
import com.abhijit.hisinchan.ui.screens.HelpScreen
import com.abhijit.hisinchan.ui.screens.HistoryScreen
import com.abhijit.hisinchan.ui.screens.HomeScreen
import com.abhijit.hisinchan.ui.screens.MemoryScreen
import com.abhijit.hisinchan.ui.screens.SettingsScreen
import com.abhijit.hisinchan.ui.theme.HiSinchanTheme
import com.abhijit.hisinchan.voice.WakeBus
import com.abhijit.hisinchan.voice.WakeWordService
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()
    private var started = false

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.onMicPermissionResult(granted)
        if (granted) syncWakeService()
    }

    private var afterWakePermissions: ((Boolean) -> Unit)? = null
    private val wakePermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val mic = result[Manifest.permission.RECORD_AUDIO] ?: hasMic()
        afterWakePermissions?.invoke(mic)
        afterWakePermissions = null
        syncWakeService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            HiSinchanTheme(settings.themeMode) {
                val nav = rememberNavController()
                LaunchedEffect(Unit) {
                    vm.events.collect { e ->
                        when (e) {
                            UiEvent.OpenMemory -> nav.navigate("memory") { launchSingleTop = true }
                            is UiEvent.Toast -> Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_LONG).show()
                            UiEvent.NeedMicPermission -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                }
                NavHost(
                    navController = nav,
                    startDestination = "home",
                    enterTransition = { slideInHorizontally { it / 6 } + fadeIn() },
                    exitTransition = { fadeOut() },
                    popEnterTransition = { fadeIn() },
                    popExitTransition = { slideOutHorizontally { it / 6 } + fadeOut() },
                ) {
                    composable("home") { HomeScreen(vm) { route -> nav.navigate(route) { launchSingleTop = true } } }
                    composable("history") { HistoryScreen(onBack = { nav.popBackStack() }) }
                    composable("memory") { MemoryScreen(onBack = { nav.popBackStack() }) }
                    composable("settings") {
                        SettingsScreen(
                            vm = vm,
                            onBack = { nav.popBackStack() },
                            requestWakePermissions = { cb -> requestWakePermissions(cb) },
                        )
                    }
                    composable("help") { HelpScreen(onBack = { nav.popBackStack() }) }
                }
            }
        }

        // React to settings that affect the window / wake service.
        lifecycleScope.launch {
            vm.settings.map { it.keepScreenOn }.distinctUntilChanged().collect { on ->
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        lifecycleScope.launch {
            vm.settings.map { it.wakeWordEnabled to it.wakeInBackground }.distinctUntilChanged().collect { syncWakeService() }
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_AUTO_LISTEN, false) == true) {
            intent.removeExtra(EXTRA_AUTO_LISTEN)
            lifecycleScope.launch { vm.onOpenedFromWakeNotification() }
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        WakeBus.appInForeground = true
        syncWakeService()
    }

    override fun onStop() {
        WakeBus.appInForeground = false
        started = false
        val s = vm.settings.value
        if (!s.wakeInBackground) WakeWordService.stop(this)
        // Never keep talking/listening to an empty room when the app is closed.
        if (!isChangingConfigurations) vm.stopAll()
        super.onStop()
    }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun syncWakeService() {
        val s = vm.settings.value
        val shouldRun = s.wakeWordEnabled && hasMic() && (started || s.wakeInBackground)
        if (shouldRun && started) WakeWordService.start(this)
        else if (!shouldRun) WakeWordService.stop(this)
    }

    private fun requestWakePermissions(callback: (Boolean) -> Unit) {
        val perms = buildList {
            if (!hasMic()) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (perms.isEmpty()) { callback(true); syncWakeService(); return }
        afterWakePermissions = callback
        wakePermissions.launch(perms.toTypedArray())
    }

    companion object {
        const val EXTRA_AUTO_LISTEN = "auto_listen"
    }
}
