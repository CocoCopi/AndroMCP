@file:Suppress("FunctionNaming")

package com.cococopi.andromcp.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.cococopi.andromcp.ui.navigation.ServerRoute
import com.cococopi.andromcp.ui.viewmodels.MainViewModel

@Composable
fun ServerTabScreen(
    onNavigateToPermissions: () -> Unit,
    onNavigateToNetworkSettings: () -> Unit,
    onNavigateToTunnelSettings: () -> Unit,
    onOpenPrivacySettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = ServerRoute.Index.route,
        modifier = modifier,
    ) {
        composable(ServerRoute.Index.route) {
            ServerScreen(
                onNavigateToPermissions = onNavigateToPermissions,
                onShowAllLogs = { navController.navigate(ServerRoute.Logs.route) },
                onNavigateToNetworkSettings = onNavigateToNetworkSettings,
                onNavigateToTunnelSettings = onNavigateToTunnelSettings,
                onOpenPrivacySettings = onOpenPrivacySettings,
                viewModel = viewModel,
            )
        }
        composable(ServerRoute.Logs.route) {
            LogsScreen(onBack = { navController.popBackStack() })
        }
    }
}
