package com.christianmolinari.scorecard.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.christianmolinari.scorecard.AppContainer
import com.christianmolinari.scorecard.ui.games.GameDetailScreen
import com.christianmolinari.scorecard.ui.games.GamesScreen
import com.christianmolinari.scorecard.ui.games.NewGameScreen
import com.christianmolinari.scorecard.ui.games.ScoreboardScreen
import com.christianmolinari.scorecard.ui.players.PlayersScreen
import com.christianmolinari.scorecard.ui.settings.BackupListScreen
import com.christianmolinari.scorecard.ui.settings.SettingsScreen
import com.christianmolinari.scorecard.ui.teams.TeamsScreen

private const val START_DESTINATION = "games"

// Root navigation: Games, Players, Teams, and Settings (the iOS TabView),
// plus the pushed flows (new game, scoreboard, detail, backups).
private data class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val topLevelDestinations = listOf(
    TopLevelDestination("games", "Games", Icons.Filled.Style),
    TopLevelDestination("players", "Players", Icons.Filled.Person),
    TopLevelDestination("teams", "Teams", Icons.Filled.Groups),
    TopLevelDestination("settings", "Settings", Icons.Filled.Settings),
)

@Composable
fun ScoreCardApp(container: AppContainer) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            // The bottom bar only belongs to the four tab roots; pushed
            // screens (scoreboard, detail, new game, backups) hide it, the
            // way iOS pushes them onto a NavigationStack above the tab bar.
            if (topLevelDestinations.any { it.route == currentRoute }) {
                NavigationBar {
                    topLevelDestinations.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = {
                                navController.navigate(destination.route) {
                                    // Keep each tab's own back stack alive while
                                    // switching, like the iOS TabView does.
                                    popUpTo(START_DESTINATION) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = START_DESTINATION,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable("games") {
                GamesScreen(
                    container = container,
                    onOpenGame = { navController.navigate("scoreboard/$it") },
                    onOpenDetail = { navController.navigate("detail/$it") },
                    onNewGame = { navController.navigate("newGame") },
                )
            }
            composable("players") {
                PlayersScreen(container = container)
            }
            composable("teams") {
                TeamsScreen(container = container)
            }
            composable("settings") {
                SettingsScreen(
                    container = container,
                    onOpenBackups = { navController.navigate("backups") },
                )
            }
            composable("newGame") {
                NewGameScreen(
                    container = container,
                    onStarted = { id ->
                        // Replace the new-game flow with the live board so Back
                        // from the scoreboard returns to the games list, not
                        // the already-consumed form.
                        navController.navigate("scoreboard/$id") {
                            popUpTo("newGame") { inclusive = true }
                        }
                    },
                    onCancel = { navController.popBackStack() },
                )
            }
            composable(
                route = "scoreboard/{gameId}",
                arguments = listOf(navArgument("gameId") { type = NavType.LongType }),
            ) { entry ->
                ScoreboardScreen(
                    container = container,
                    gameId = requireNotNull(entry.arguments).getLong("gameId"),
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = "detail/{gameId}",
                arguments = listOf(navArgument("gameId") { type = NavType.LongType }),
            ) { entry ->
                GameDetailScreen(
                    container = container,
                    gameId = requireNotNull(entry.arguments).getLong("gameId"),
                    onBack = { navController.popBackStack() },
                )
            }
            composable("backups") {
                BackupListScreen(
                    container = container,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
