package com.streamtwin.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.streamtwin.ui.connect.ConnectScreen
import com.streamtwin.ui.home.HomeScreen
import com.streamtwin.ui.live.LiveScreen
import com.streamtwin.ui.settings.SettingsScreen
import com.streamtwin.ui.splash.SplashScreen

sealed class Screen(val route: String) {
    object Splash : Screen("splash")
    object Connect : Screen("connect")
    object Home : Screen("home")
    object Settings : Screen("settings")
    object Live : Screen("live")
    object Vault : Screen("vault")
    object Permissions : Screen("permissions")
}

@Composable
fun NavGraph(
    navController: NavHostController,
    startDestination: String = Screen.Splash.route
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.Splash.route) {
            SplashScreen(
                onNavigateToHome = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Splash.route) { inclusive = true }
                    }
                },
                onNavigateToConnect = {
                    navController.navigate(Screen.Connect.route) {
                        popUpTo(Screen.Splash.route) { inclusive = true }
                    }
                },
                onNavigateToPermissionsFromSplash = { targetRoute ->
                    // Instead of passing target route as argument, we just navigate to Permissions.
                    // PermissionsScreen will figure out where to go next based on auth state.
                    navController.navigate(Screen.Permissions.route) {
                        popUpTo(Screen.Splash.route) { inclusive = true }
                    }
                }
            )
        }
        composable(Screen.Permissions.route) {
            com.streamtwin.ui.permissions.PermissionsScreen(
                onAllPermissionsGranted = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Permissions.route) { inclusive = true }
                    }
                }
            )
        }
        composable(Screen.Connect.route) {
            ConnectScreen(
                onAuthSuccess = {
                    navController.navigate(Screen.Home.route + "?startMode=STREAMING") {
                        popUpTo(Screen.Connect.route) { inclusive = true }
                    }
                }
            )
        }
        composable(
            route = Screen.Home.route + "?startMode={startMode}",
            arguments = listOf(androidx.navigation.navArgument("startMode") { nullable = true; defaultValue = null })
        ) { backStackEntry ->
            val startMode = backStackEntry.arguments?.getString("startMode")
            HomeScreen(
                startMode = startMode,
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                },
                onNavigateToLive = {
                    navController.navigate(Screen.Live.route)
                },
                onNavigateToVault = {
                    navController.navigate(Screen.Vault.route)
                },
                onNavigateToConnect = {
                    navController.navigate(Screen.Connect.route)
                },
                onPermissionsRevoked = {
                    navController.navigate(Screen.Permissions.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                    }
                }
            )
        }
        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onSignOut = {
                    navController.navigate(Screen.Connect.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
        composable(Screen.Live.route) {
            LiveScreen(
                onStreamEnded = {
                    navController.popBackStack()
                }
            )
        }
        composable(Screen.Vault.route) {
            com.streamtwin.ui.vault.VaultScreen(
                onNavigateToHome = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                    }
                }
            )
        }
    }
}
