package com.stickcommander.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.stickcommander.di.AppContainer
import com.stickcommander.ui.components.ConnectionStatusChip
import com.stickcommander.ui.dashboard.DashboardScreen
import com.stickcommander.ui.dashboard.DashboardViewModel
import com.stickcommander.ui.remote.RemoteControlScreen
import com.stickcommander.ui.remote.RemoteViewModel
import com.stickcommander.ui.scripts.ScriptManagerScreen
import com.stickcommander.ui.scripts.ScriptManagerViewModel
import com.stickcommander.ui.tools.ToolsScreen
import com.stickcommander.ui.tools.ToolsViewModel
import com.stickcommander.ui.terminal.TerminalScreen
import com.stickcommander.ui.terminal.TerminalViewModel

enum class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Dashboard("dashboard", "Dashboard", Icons.Rounded.Home),
    Remote("remote", "Remote", Icons.Rounded.Gamepad),
    Tools("tools", "Tools", Icons.Rounded.Build),
    Scripts("scripts", "Scripts", Icons.Rounded.Description),
    Terminal("terminal", "Terminal", Icons.Rounded.Terminal),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickCommanderApp(container: AppContainer) {
    val repository = container.deviceRepository
    val context = LocalContext.current
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    val connection by repository.connection.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val currentDestination = Destination.entries.firstOrNull { it.route == currentRoute }
        ?: Destination.Dashboard

    LaunchedEffect(repository) {
        repository.messages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= 720.dp

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "StickCommander",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                text = currentDestination.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        ConnectionStatusChip(
                            state = connection,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                    },
                )
            },
            bottomBar = {
                if (!useNavigationRail) {
                    NavigationBar {
                        Destination.entries.forEach { destination ->
                            NavigationBarItem(
                                selected = currentDestination == destination,
                                onClick = { navigateTo(navController, destination) },
                                icon = {
                                    Icon(
                                        imageVector = destination.icon,
                                        contentDescription = destination.label,
                                    )
                                },
                                label = { Text(destination.label) },
                            )
                        }
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                if (useNavigationRail) {
                    NavigationRail {
                        Destination.entries.forEach { destination ->
                            NavigationRailItem(
                                selected = currentDestination == destination,
                                onClick = { navigateTo(navController, destination) },
                                icon = {
                                    Icon(
                                        imageVector = destination.icon,
                                        contentDescription = destination.label,
                                    )
                                },
                                label = { Text(destination.label) },
                            )
                        }
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = Destination.Dashboard.route,
                    modifier = Modifier.weight(1f),
                ) {
                    composable(Destination.Dashboard.route) {
                        val vm: DashboardViewModel = viewModel(
                            factory = DashboardViewModel.factory(repository),
                        )
                        DashboardScreen(viewModel = vm)
                    }
                    composable(Destination.Remote.route) {
                        val vm: RemoteViewModel = viewModel(
                            factory = RemoteViewModel.factory(repository),
                        )
                        RemoteControlScreen(viewModel = vm)
                    }
                    composable(Destination.Tools.route) {
                        val vm: ToolsViewModel = viewModel(
                            factory = ToolsViewModel.factory(repository, context),
                        )
                        ToolsScreen(viewModel = vm)
                    }
                    composable(Destination.Scripts.route) {
                        val vm: ScriptManagerViewModel = viewModel(
                            factory = ScriptManagerViewModel.factory(
                                scriptRepository = container.scriptRepository,
                                deviceRepository = repository,
                            ),
                        )
                        ScriptManagerScreen(viewModel = vm)
                    }
                    composable(Destination.Terminal.route) {
                        val vm: TerminalViewModel = viewModel(
                            factory = TerminalViewModel.factory(repository),
                        )
                        TerminalScreen(viewModel = vm)
                    }
                }
            }
        }
    }
}

private fun navigateTo(
    navController: NavHostController,
    destination: Destination,
) {
    if (navController.currentDestination?.route == destination.route) return
    navController.navigate(destination.route) {
        popUpTo(navController.graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}
