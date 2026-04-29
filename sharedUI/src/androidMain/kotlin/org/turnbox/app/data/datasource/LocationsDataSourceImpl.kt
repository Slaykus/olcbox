package org.turnbox.app.data.datasource

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.turnbox.app.data.ACTIVE_LOCATION_CONFIG_FILE_NAME
import org.turnbox.app.data.LOCATIONS_BUNDLE_FILE_NAME
import org.turnbox.app.data.model.LocationBundleV3
import org.turnbox.app.vpn.data.KEY_IS_VPN_CONFIG_READY
import org.turnbox.app.vpn.data.KEY_VPN_CONFIG_PATH
import org.turnbox.app.vpn.data.vpnPrefDataStore
import java.io.File

private val KEY_LEGACY_SELECTED_LOCATION_ID = stringPreferencesKey("selected_hysteria_id")

class LocationsDataSourceImpl(
    private val context: Context
) : LocationsDataSource {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    override suspend fun loadLocationBundle(): LocationBundleV3? = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, LOCATIONS_BUNDLE_FILE_NAME)
        if (!file.exists()) return@withContext null
        runCatching {
            json.decodeFromString(LocationBundleV3.serializer(), file.readText()).normalized()
        }.getOrNull()
    }

    override suspend fun saveLocationBundle(bundle: LocationBundleV3): Unit = withContext(Dispatchers.IO) {
        val normalized = bundle.normalized()
        File(context.filesDir, LOCATIONS_BUNDLE_FILE_NAME).writeText(
            json.encodeToString(LocationBundleV3.serializer(), normalized)
        )
        updateActiveLocationConfig(normalized)
    }

    override suspend fun loadLegacyLocations(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val settings = context.filesDir.listFiles { file ->
            file.name.startsWith("hysteria_settings_") && file.name.endsWith(".json")
        }.orEmpty().map { file ->
            val storageId = file.name.removePrefix("hysteria_settings_").removeSuffix(".json")
            storageId to file.readText()
        }

        val master = File(context.filesDir, "hysteria.yaml")
            .takeIf { it.exists() }
            ?.let { listOf("legacy_master" to it.readText()) }
            .orEmpty()

        settings + master
    }

    override suspend fun loadLegacyActiveLocationId(): String? {
        return context.vpnPrefDataStore.data.first()[KEY_LEGACY_SELECTED_LOCATION_ID]?.ifBlank { null }
    }

    private suspend fun updateActiveLocationConfig(bundle: LocationBundleV3) {
        val active = bundle.locations.firstOrNull { it.storageId == bundle.activeLocationId }
        val file = File(context.filesDir, ACTIVE_LOCATION_CONFIG_FILE_NAME)

        if (active == null) {
            if (file.exists()) file.delete()
            context.vpnPrefDataStore.edit {
                it[KEY_IS_VPN_CONFIG_READY] = false
                it.remove(KEY_VPN_CONFIG_PATH)
            }
            return
        }

        file.writeText(
            json.encodeToString(LocationBundleV3.serializer(), bundle.copy(locations = listOf(active)))
        )
        context.vpnPrefDataStore.edit {
            it[KEY_IS_VPN_CONFIG_READY] = active.location.isComplete()
            it[KEY_VPN_CONFIG_PATH] = file.absolutePath
        }
    }
}
