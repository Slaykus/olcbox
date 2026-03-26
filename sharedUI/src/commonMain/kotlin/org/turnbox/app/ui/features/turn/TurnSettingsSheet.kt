package org.turnbox.app.ui.features.turn

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.turnbox.app.ui.components.PingButton
import org.turnbox.app.ui.features.home.HomeScreenViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomTurnSheet(
    onDismiss: () -> Unit,
    viewModel: HomeScreenViewModel
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    val closeSheet = {
        scope.launch {
            sheetState.hide()
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        CustomTurnContent(
            viewModel = viewModel,
            onDismiss = { closeSheet() }
        )
    }
}

@Composable
fun CustomTurnContent(
    viewModel: HomeScreenViewModel,
    onDismiss: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val turnData = state.turnData
    val isCustom = state.selectedTurnType == "custom"

    val title = when (state.selectedTurnType) {
        "vk" -> "VK TURN Settings"
        "yandex" -> "Yandex TURN Settings"
        else -> "Custom TURN Settings"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 24.dp)
        )
        if (isCustom) {
            OutlinedTextField(
                value = turnData.peer,
                onValueChange = { viewModel.onTurnPeerChanged(it) },
                label = { Text("TURN Address") },
                placeholder = { Text("turn.example.com:3478") },
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    if (turnData.peer.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onTurnPeerChanged("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear")
                        }
                    }
                }
            )
        }

        if (!isCustom) {
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = turnData.link,
                onValueChange = { viewModel.onTurnLinkChanged(it) },
                label = { Text("Link") },
                placeholder = { Text("https://...") },
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    if (turnData.link.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onTurnLinkChanged("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear")
                        }
                    }
                }
            )
        }

        if (isCustom) {
            Spacer(modifier = Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = turnData.user,
                    onValueChange = { viewModel.onTurnUserChanged(it) },
                    label = { Text("Username") },
                    placeholder = { Text("user") },
                    modifier = Modifier.weight(1f),
                    trailingIcon = {
                        if (turnData.user.isNotEmpty()) {
                            IconButton(onClick = { viewModel.onTurnUserChanged("") }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear")
                            }
                        }
                    }
                )

                Spacer(modifier = Modifier.width(16.dp))

                OutlinedTextField(
                    value = turnData.pass,
                    onValueChange = { viewModel.onTurnPassChanged(it) },
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.weight(1f),
                    trailingIcon = {
                        if (turnData.pass.isNotEmpty()) {
                            IconButton(onClick = { viewModel.onTurnPassChanged("") }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear")
                            }
                        }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Number of threads: ${turnData.threads}",
                style = MaterialTheme.typography.labelLarge
            )
            Slider(
                value = turnData.threads.toFloat(),
                onValueChange = { viewModel.onTurnThreadsChanged(it.toInt().toString()) },
                valueRange = 1f..16f,
                steps = 14
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        PingButton()

        Spacer(modifier = Modifier.height(32.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isCustom) {
                OutlinedIconButton(
                    onClick = { /* Handle delete if needed */ },
                    modifier = Modifier.size(56.dp),
                    shape = CircleShape,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant
                    )
                ) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Delete")
                }

                Spacer(modifier = Modifier.width(16.dp))
            }

            Button(
                onClick = {
                    viewModel.onConfigConfirmed()
                    onDismiss()
                },
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                shape = RoundedCornerShape(28.dp)
            ) {
                Icon(Icons.Rounded.Check, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Save TURN Settings", fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}
