package com.custom.treadmill.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.custom.treadmill.DebugLog
import com.custom.treadmill.R
import com.custom.treadmill.ui.screens.*
import com.custom.treadmill.ui.viewmodels.MainViewModel
import com.custom.treadmill.ui.viewmodels.ProgramViewModel
import kotlinx.coroutines.launch

object Routes {
    const val TABS = "tabs"
    const val WORKOUT = "workout"
    const val DEBUG = "debug"
    const val ABOUT = "about"
    const val PROFILES = "profiles"
    const val TTS = "tts"
    const val PROGRAMS = "tab_programs"
    const val HISTORY = "tab_history"
    const val SETTINGS = "tab_settings"
    const val MAIN = "tab_main"
}

@Composable
fun AppNavHost() {
    val nav = rememberNavController()
    val mainVm: MainViewModel = viewModel()
    val programVm: ProgramViewModel = viewModel()

    LaunchedEffect(nav) {
        nav.addOnDestinationChangedListener { _, destination, arguments ->
            val route = destination.route ?: "unknown"
            val args = arguments?.keySet()
                ?.joinToString { "$it=${arguments.get(it)}" }
                .orEmpty()
            DebugLog.d("NAV", "→ $route" + if (args.isNotEmpty()) " ($args)" else "")
        }
    }

    NavHost(navController = nav, startDestination = Routes.TABS) {
        composable(Routes.TABS) {
            MainTabs(
                mainVm = mainVm,
                programVm = programVm,
                onOpenWorkout = { nav.navigate(Routes.WORKOUT) },
                onOpenDebug = { nav.navigate(Routes.DEBUG) },
                onOpenAbout = { nav.navigate(Routes.ABOUT) },
                onOpenProfiles = { nav.navigate(Routes.PROFILES) },
                onOpenTts = { nav.navigate(Routes.TTS) }
            )
        }
        composable(Routes.WORKOUT) {
            WorkoutScreen(vm = mainVm, onBack = { nav.popBackStack() }, onOpenDebug = { nav.navigate(Routes.DEBUG) })
        }
        composable(Routes.DEBUG) { DebugScreen(vm = mainVm, onBack = { nav.popBackStack() }) }
        composable(Routes.ABOUT) { AboutScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.PROFILES) { ProfilesScreen(vm = mainVm, onBack = { nav.popBackStack() }) }
        composable(Routes.TTS) { TtsSettingsScreen(vm = mainVm, onBack = { nav.popBackStack() }) }
    }
}

private data class Tab(@StringRes val titleRes: Int, val icon: ImageVector)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MainTabs(
    mainVm: MainViewModel,
    programVm: ProgramViewModel,
    onOpenWorkout: () -> Unit,
    onOpenDebug: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenProfiles: () -> Unit,
    onOpenTts: () -> Unit
) {
    val tabs = listOf(
        Tab(R.string.tab_main, Icons.Default.Home),
        Tab(R.string.tab_programs, Icons.AutoMirrored.Filled.List),
        Tab(R.string.tab_history, Icons.Default.History),
        Tab(R.string.tab_settings, Icons.Default.Settings),
    )
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()

    // [ФИКС] Убрали floatingActionButton с жучком Debug, который перекрывал кнопки на главном экране.
    // Доступ к Debug остался в настройках.
    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    val title = stringResource(tab.titleRes)
                    NavigationBarItem(
                        selected = pagerState.currentPage == index,
                        onClick = {
                            DebugLog.d("NAV", "tab tap: ${tab.titleRes}")
                            scope.launch { pagerState.scrollToPage(index) }
                        },
                        icon = { Icon(tab.icon, contentDescription = title) },
                        label = { Text(title, fontSize = 11.sp) }
                    )
                }
            }
        }
    ) { padding ->
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize().padding(padding)) { page ->
            when (page) {
                0 -> MainScreen(
                    vm = mainVm,
                    onNavigate = { route ->
                        when (route) {
                            Routes.PROGRAMS -> scope.launch { pagerState.scrollToPage(1) }
                            Routes.HISTORY  -> scope.launch { pagerState.scrollToPage(2) }
                            Routes.SETTINGS -> scope.launch { pagerState.scrollToPage(3) }
                            Routes.WORKOUT  -> onOpenWorkout()
                            Routes.DEBUG    -> onOpenDebug()
                            Routes.ABOUT    -> onOpenAbout()
                        }
                    }
                )
                1 -> ProgramsScreen(
                    mainVm = mainVm, programVm = programVm, onBack = null,
                    onStartWorkout = { mainVm.startWorkout(it); onOpenWorkout() }
                )
                2 -> HistoryScreen(programVm = programVm, onBack = null)
                3 -> SettingsScreen(
                    vm = mainVm, onBack = null, onOpenDebug = onOpenDebug, onOpenAbout = onOpenAbout,
                    onOpenProfiles = onOpenProfiles, onOpenTts = onOpenTts
                )
            }
        }
    }
}
