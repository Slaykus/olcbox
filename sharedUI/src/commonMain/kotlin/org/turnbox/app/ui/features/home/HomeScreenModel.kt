package org.turnbox.app.ui.features.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.turnbox.app.data.importer.ConfigImporter
import org.turnbox.app.data.model.HysteriaConfig
import org.turnbox.app.data.model.TurnConfig
import org.turnbox.app.data.repository.HysteriaConfigRepository
import org.turnbox.app.ui.features.locations.LocationItem
import org.turnbox.app.vpn.VpnManager

class HomeScreenViewModel(
    private val vpnManager: VpnManager,
    private val configRepo: HysteriaConfigRepository,
    private val configImporter: ConfigImporter
) : ViewModel() {
    private val _state =
        MutableStateFlow(
            UiState(
                false,
                false,
                null,
                HysteriaConfig(),
                TurnConfig(),
                "custom",
                false,
                emptyList()
            )
        )
    val state get() = _state.asStateFlow()

    init {
        loadCurrentConfig()

        viewModelScope.launch {
            vpnManager.logs.collect { logs ->
                _state.update { it.copy(logs = logs) }
            }
        }

        viewModelScope.launch {
            vpnManager.isConnected.collect { connected ->
                _state.update { it.copy(isVpnConnected = connected, isVpnLoading = false) }
            }
        }
    }

    fun loadCurrentConfig() {
        viewModelScope.launch {
            val selectedId = configRepo.getSelectedHysteriaId()
            if (selectedId.isBlank()) {
                _state.update { it.copy(selectedLocation = null) }
                return@launch
            }

            val savedHysteria = configRepo.loadHysteriaConfig(selectedId)
            val selectedType = configRepo.getSelectedTurnType()
            val savedTurn = configRepo.loadTurnConfig(selectedType)

            val fullName = savedHysteria.name.ifBlank { savedHysteria.server }
            val locationItem = LocationItem(selectedId, fullName, savedHysteria)

            _state.update {
                it.copy(
                    configData = savedHysteria,
                    turnData = savedTurn,
                    selectedTurnType = selectedType,
                    selectedLocation = locationItem
                )
            }
        }
    }

    fun startVpnContinuation() {
        _state.update { it.copy(isVpnLoading = true) }
    }

    fun ToggleVpn() {
        if (_state.value.isVpnLoading) return

        viewModelScope.launch {
            _state.update { it.copy(isVpnLoading = true) }
            try {
                if (_state.value.isVpnConnected) {
                    vpnManager.stopVpn()
                } else {
                    val selectedId = configRepo.getSelectedHysteriaId()
                    if (selectedId.isNotBlank()) {
                        configRepo.saveHysteriaConfig(state.value.configData, selectedId)
                    }
                    configRepo.saveTurnConfig(state.value.turnData, state.value.selectedTurnType)
                    vpnManager.startVpn()
                }
            } catch (e: Exception) {
                _state.update { it.copy(isVpnLoading = false) }
            }
        }
    }

    fun onServerOptionSelected(id: Int) {
        val newType = when (id) {
            1 -> "vk"
            2 -> "yandex"
            3 -> "custom"
            else -> return
        }

        viewModelScope.launch {
            configRepo.saveTurnConfig(_state.value.turnData, _state.value.selectedTurnType)
            configRepo.setSelectedTurnType(newType)
            val newTurnConfig = configRepo.loadTurnConfig(newType)

            _state.update {
                it.copy(
                    selectedTurnType = newType,
                    turnData = newTurnConfig
                )
            }
        }
    }

    fun onServerChanged(value: String) = updateHysteriaConfig { it.copy(server = value) }
    fun onPasswordChanged(value: String) = updateHysteriaConfig { it.copy(password = value) }
    fun onSniChanged(value: String) = updateHysteriaConfig { it.copy(sni = value) }

    fun onTurnEnabledChanged(value: Boolean) = updateTurnConfig { it.copy(enabled = value) }
    fun onTurnPeerChanged(value: String) = updateTurnConfig { it.copy(peer = value) }
    fun onTurnLinkChanged(value: String) = updateTurnConfig { it.copy(link = value) }
    fun onTurnUserChanged(value: String) = updateTurnConfig { it.copy(user = value) }
    fun onTurnPassChanged(value: String) = updateTurnConfig { it.copy(pass = value) }
    fun onTurnUdpChanged(value: Boolean) = updateTurnConfig { it.copy(udp = value) }
    fun onTurnThreadsChanged(threads: String) {
        val n = threads.toIntOrNull() ?: 8
        updateTurnConfig { it.copy(threads = n) }
    }

    private fun updateHysteriaConfig(block: (HysteriaConfig) -> HysteriaConfig) {
        _state.update { it.copy(configData = block(it.configData)) }
    }

    private fun updateTurnConfig(block: (TurnConfig) -> TurnConfig) {
        _state.update { it.copy(turnData = block(it.turnData)) }
    }

    fun onConfigConfirmed() {
        if (isUserConfigValid()) {
            viewModelScope.launch {
                val selectedId = configRepo.getSelectedHysteriaId()
                if (selectedId.isNotBlank()) {
                    configRepo.saveHysteriaConfig(_state.value.configData, selectedId)
                }
                configRepo.saveTurnConfig(_state.value.turnData, _state.value.selectedTurnType)
            }
        } else {
            _state.update { it.copy(shouldShowConfigInvalidReminder = true) }
        }
    }

    fun onConfigInvalidReminderDismissed() {
        _state.update { it.copy(shouldShowConfigInvalidReminder = false) }
    }

    fun onCopyFullConfigClicked() {
        val fullData =
            state.value.configData.toJsonConfig(state.value.turnData, state.value.selectedTurnType)
        configImporter.copyToClipboard(fullData)
    }

    fun onPasteFromClipboard() {
        configImporter.getFromClipboard()?.let { text ->
            onImportFullConfig(text)
        }
    }

    fun onFileSelected(fileSource: Any) {
        viewModelScope.launch {
            configImporter.readTextFromSource(fileSource)?.let { text ->
                onImportFullConfig(text)
            }
        }
    }

    private fun isUserConfigValid(): Boolean {
        val hConfig = _state.value.configData
        val tConfig = _state.value.turnData
        val type = _state.value.selectedTurnType

        if (tConfig.enabled) {
            val isPeerValid = tConfig.peer.isNotBlank()
            val isDataValid = if (type == "custom") {
                tConfig.user.isNotBlank() && tConfig.pass.isNotBlank()
            } else {
                tConfig.link.isNotBlank()
            }
            return isPeerValid && isDataValid && hConfig.password.isNotBlank()
        }
        return hConfig.server.isNotBlank() && hConfig.password.isNotBlank()
    }

    fun onRawConfigImported(rawText: String) {
        if (rawText.isBlank()) return
        viewModelScope.launch {
            configRepo.saveRawConfig(rawText)
            _state.update { it.copy(shouldShowConfigInvalidReminder = false) }
        }
    }

    fun onImportFullConfig(rawText: String) {
        if (rawText.isBlank()) return
        viewModelScope.launch {
            try {
                configRepo.saveRawConfig(rawText)
                loadCurrentConfig()
            } catch (e: Exception) {
                // add error to state
            }
        }
    }
}

data class UiState(
    val isVpnConnected: Boolean,
    val isVpnLoading: Boolean = false,
    val selectedLocation: LocationItem?,
    val configData: HysteriaConfig,
    val turnData: TurnConfig,
    val selectedTurnType: String,
    val shouldShowConfigInvalidReminder: Boolean,
    val logs: List<String> = emptyList()
)
