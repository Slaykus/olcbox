package org.turnbox.app.data.repository

import org.turnbox.app.data.model.LocationBundleV3
import org.turnbox.app.data.model.LocationConfig
import org.turnbox.app.data.model.LocationEntry

interface LocationsRepository {
    suspend fun getBundle(): LocationBundleV3
    suspend fun saveBundle(bundle: LocationBundleV3)
    suspend fun exportBundle(): String
    suspend fun importText(text: String)
    suspend fun saveLocation(storageId: String, location: LocationConfig)
    suspend fun loadLocation(storageId: String): LocationConfig?
    suspend fun deleteLocation(storageId: String)
    suspend fun getAllLocations(): List<LocationEntry>
    suspend fun getActiveLocationId(): String?
    suspend fun setActiveLocationId(storageId: String?)
    suspend fun getActiveLocation(): LocationEntry?
}
