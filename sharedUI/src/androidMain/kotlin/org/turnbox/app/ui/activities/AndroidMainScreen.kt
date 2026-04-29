package org.turnbox.app.ui.activities

import android.app.Activity
import android.net.Uri
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import org.turnbox.app.ui.features.home.HomeScreen
import org.turnbox.app.ui.features.home.HomeScreenViewModel
import org.turnbox.app.ui.features.locations.LocationViewModel

@Composable
fun AndroidMainScreen(
    viewModel: HomeScreenViewModel,
    locationViewModel: LocationViewModel
) {
    val context = LocalContext.current

    val vpnRequestLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.ToggleVpn()
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.onFileSelected(it) {
                locationViewModel.loadLocations()
                viewModel.loadCurrentConfig()
            }
        }
    }

    HomeScreen(
        viewModel = viewModel,
        locationViewModel = locationViewModel,
        onToggleClick = {
            val prepIntent = VpnService.prepare(context)
            if (prepIntent != null) {
                vpnRequestLauncher.launch(prepIntent)
            } else {
                viewModel.ToggleVpn()
            }
        },
        onImportFileRequested = {
            filePickerLauncher.launch("*/*")
        },
        onImportFromClipboardRequested = {
            viewModel.onPasteFromClipboard {
                locationViewModel.loadLocations()
                viewModel.loadCurrentConfig()
            }
        },
        onCopyConfigRequested = {
            viewModel.onCopyFullConfigClicked()
        }
    )
}
