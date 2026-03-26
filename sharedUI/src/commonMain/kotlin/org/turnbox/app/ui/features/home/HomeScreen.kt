package org.turnbox.app.ui.features.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.turnbox.app.ui.components.StartButton
import org.turnbox.app.ui.features.home.components.HomeScreenAppBar
import org.turnbox.app.ui.features.home.components.LocationSelectorScreen
import org.turnbox.app.ui.features.home.components.LogsSheet
import org.turnbox.app.ui.features.home.components.RelayStatus
import org.turnbox.app.ui.features.home.components.ServerSelectionScreen
import org.turnbox.app.ui.features.locations.LocationSelectionSheet
import org.turnbox.app.ui.features.locations.LocationSettingsSheet
import org.turnbox.app.ui.features.locations.LocationViewModel
import org.turnbox.app.ui.features.turn.CustomTurnSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeScreenViewModel,
    locationViewModel: LocationViewModel,
    onToggleClick: () -> Unit = { viewModel.ToggleVpn() },
    onImportFileRequested: () -> Unit = {},
    onImportFromClipboardRequested: () -> Unit = { viewModel.onPasteFromClipboard() },
    onCopyConfigRequested: () -> Unit = { viewModel.onCopyFullConfigClicked() }
) {
    val scrollState = rememberScrollState()
    var isLocationSheetOpen by remember { mutableStateOf(false) }
    var isLocationSettingsOpen by remember { mutableStateOf(false) }
    var isTurnSheetOpen by remember { mutableStateOf(false) }
    var isLogsSheetOpen by remember { mutableStateOf(false) }

    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            HomeScreenAppBar(
                onHistoryClick = { isLogsSheetOpen = true },
                onImportFileClick = onImportFileRequested,
                onImportClipboardClick = {
                    onImportFromClipboardRequested()
                    scope.launch { snackbarHostState.showSnackbar("Импортировано из буфера") }
                },
                onExportClipboardClick = {
                    onCopyConfigRequested()
                    scope.launch { snackbarHostState.showSnackbar("Конфиг скопирован") }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 32.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            RelayStatus(isActive = state.isVpnConnected)
            Spacer(
                modifier = Modifier.height(16.dp)
            )
            StartButton(
                isActive = state.isVpnConnected,
                isLoading = state.isVpnLoading,
                onClick = { onToggleClick() }
            )
            Spacer(
                modifier = Modifier.height(32.dp)
            )
            LocationSelectorScreen(
                onRefreshClick = {},
                onSelectorClick = { isLocationSheetOpen = true },
                item = state.selectedLocation,
                onAddLocationClick = {
                    locationViewModel.startEditing(null)
                    isLocationSettingsOpen = true
                }
            )
            Spacer(
                modifier = Modifier.height(12.dp)
            )
            ServerSelectionScreen(
                selectedType = state.selectedTurnType,
                onOptionSelected = { option ->
                    viewModel.onServerOptionSelected(option.id)
                },
                onSettingsClick = {
                    isTurnSheetOpen = true
                }
            )

            if (isLocationSheetOpen) {
                LocationSelectionSheet(
                    onDismiss = {
                        isLocationSheetOpen = false
                        viewModel.loadCurrentConfig()
                    },
                    viewModel = locationViewModel
                )
            }

            if (isLocationSettingsOpen) {
                LocationSettingsSheet(
                    onDismiss = {
                        isLocationSettingsOpen = false
                        viewModel.loadCurrentConfig()
                    },
                    viewModel = locationViewModel
                )
            }

            if (isTurnSheetOpen) {
                CustomTurnSheet(
                    viewModel = viewModel,
                    onDismiss = { isTurnSheetOpen = false }
                )
            }

            if (isLogsSheetOpen) {
                LogsSheet(
                    logs = state.logs,
                    onDismiss = { isLogsSheetOpen = false }
                )
            }
        }
    }
}
