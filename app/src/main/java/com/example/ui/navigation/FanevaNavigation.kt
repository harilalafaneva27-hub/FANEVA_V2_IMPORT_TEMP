package com.example.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import com.example.ui.FanevaViewModel
import com.example.ui.screens.*

enum class FanevaScreen(
    val route: String,
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val isBottomNav: Boolean
) {
    DASHBOARD("dashboard", "Tableau de bord", Icons.Filled.Dashboard, Icons.Outlined.Dashboard, true),
    POS("pos", "Caisse POS", Icons.Filled.PointOfSale, Icons.Outlined.PointOfSale, true),
    INVENTORY("inventory", "Stocks A1-A5", Icons.Filled.Inventory2, Icons.Outlined.Inventory2, true),
    CUSTOMERS("customers", "Clients & Dettes", Icons.Filled.People, Icons.Outlined.People, true),
    SETTINGS("settings", "Paramètres", Icons.Filled.Settings, Icons.Outlined.Settings, true),

    // Sub-screens
    TRANSFERS("transfers", "Transferts", Icons.Filled.SwapHoriz, Icons.Outlined.SwapHoriz, false),
    SALES_HISTORY("history", "Historique", Icons.Filled.ReceiptLong, Icons.Outlined.ReceiptLong, false),
    SYNC_OUTBOX("outbox", "File Synchro", Icons.Filled.Sync, Icons.Outlined.Sync, false),
    AI_INSIGHTS("ai", "FANEVA IA", Icons.Filled.AutoAwesome, Icons.Outlined.AutoAwesome, false)
}

@Composable
fun FanevaApp(
    viewModel: FanevaViewModel,
    modifier: Modifier = Modifier
) {
    var currentScreen by remember { mutableStateOf(FanevaScreen.DASHBOARD) }

    // Hardware back button navigation handling
    if (currentScreen != FanevaScreen.DASHBOARD) {
        BackHandler {
            currentScreen = FanevaScreen.DASHBOARD
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar(
                modifier = Modifier.testTag("faneva_bottom_nav")
            ) {
                FanevaScreen.entries.filter { it.isBottomNav }.forEach { screen ->
                    val isSelected = currentScreen == screen
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { currentScreen = screen },
                        icon = {
                            Icon(
                                imageVector = if (isSelected) screen.selectedIcon else screen.unselectedIcon,
                                contentDescription = screen.title
                            )
                        },
                        label = { Text(screen.title) },
                        modifier = Modifier.testTag("nav_item_${screen.route}")
                    )
                }
            }
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        val screenModifier = Modifier.padding(innerPadding)

        when (currentScreen) {
            FanevaScreen.DASHBOARD -> DashboardScreen(
                viewModel = viewModel,
                onNavigateToPos = { currentScreen = FanevaScreen.POS },
                onNavigateToSalesHistory = {
                    currentScreen = FanevaScreen.SALES_HISTORY
                },
                onNavigateToInventory = { currentScreen = FanevaScreen.INVENTORY },
                onNavigateToTransfers = { currentScreen = FanevaScreen.TRANSFERS },
                onNavigateToDebts = { currentScreen = FanevaScreen.CUSTOMERS },
                onNavigateToSync = { currentScreen = FanevaScreen.SYNC_OUTBOX },
                onNavigateToAi = { currentScreen = FanevaScreen.AI_INSIGHTS },
                modifier = screenModifier
            )
            FanevaScreen.POS -> PosScreen(
                viewModel = viewModel,
                onNavigateToHistory = { currentScreen = FanevaScreen.SALES_HISTORY },
                modifier = screenModifier
            )
            FanevaScreen.INVENTORY -> InventoryScreen(
                viewModel = viewModel,
                onNavigateToTransfers = { currentScreen = FanevaScreen.TRANSFERS },
                modifier = screenModifier
            )
            FanevaScreen.CUSTOMERS -> CustomersDebtScreen(
                viewModel = viewModel,
                modifier = screenModifier
            )
            FanevaScreen.SETTINGS -> SettingsScreen(
                viewModel = viewModel,
                modifier = screenModifier
            )
            FanevaScreen.TRANSFERS -> StoreTransfersScreen(
                viewModel = viewModel,
                onNavigateBack = { currentScreen = FanevaScreen.DASHBOARD },
                modifier = screenModifier
            )
            FanevaScreen.SALES_HISTORY -> SalesHistoryScreen(
                viewModel = viewModel,
                onNavigateBack = { currentScreen = FanevaScreen.POS },
                modifier = screenModifier
            )
            FanevaScreen.SYNC_OUTBOX -> SyncOutboxScreen(
                viewModel = viewModel,
                onNavigateBack = { currentScreen = FanevaScreen.DASHBOARD },
                modifier = screenModifier
            )
            FanevaScreen.AI_INSIGHTS -> FanevaAiScreen(
                viewModel = viewModel,
                onNavigateBack = { currentScreen = FanevaScreen.DASHBOARD },
                modifier = screenModifier
            )
        }
    }
}
