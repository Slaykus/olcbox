package org.turnbox.app.ui.features.locations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.turnbox.app.ui.features.locations.components.LocationRow
import org.turnbox.app.ui.features.locations.components.RefreshButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationSelectionSheet(
    onDismiss: () -> Unit,
    viewModel: LocationViewModel
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var isSettingsOpen by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    val closeSheet = {
        scope.launch {
            sheetState.hide()
            onDismiss()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.loadLocations()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        LocationSheetContent(
            viewModel = viewModel,
            onAddClick = {
                viewModel.startEditing(null)
                isSettingsOpen = true
            },
            onSettingsClick = { id ->
                viewModel.startEditing(id)
                isSettingsOpen = true
            },
            onLocationSelected = { id ->
                viewModel.selectLocation(id)
                closeSheet()
            }
        )
    }

    if (isSettingsOpen) {
        LocationSettingsSheet(
            onDismiss = {
                isSettingsOpen = false
            },
            viewModel = viewModel
        )
    }
}

@Composable
fun LocationSheetContent(
    viewModel: LocationViewModel,
    onAddClick: () -> Unit,
    onSettingsClick: (String) -> Unit,
    onLocationSelected: (String) -> Unit
) {
    val state = viewModel.pingsState

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, bottom = 40.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Select Location",
                fontSize = 28.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )

            RefreshButton(
                state = state,
                onClick = { viewModel.refreshPings() },
                tint = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            viewModel.locations.forEach { location ->
                val pingMs = (state as? PingsState.Success)?.pings?.get(location.id)
                val isCurrentlyLoading = state is PingsState.Loading

                LocationRow(
                    location = location,
                    isSelected = viewModel.selectedLocationId == location.id,
                    pingMs = pingMs,
                    onSettingsClick = { onSettingsClick(location.id) },
                    onClick = { onLocationSelected(location.id) },
                    isLoading = isCurrentlyLoading
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        FilledTonalButton(
            onClick = onAddClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Add Custom Location", fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
    }
}