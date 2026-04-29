package org.turnbox.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LocationConfig(
    val name: String = "",
    val id: String = "",
    val key: String = "",
    @SerialName("bypass_provider")
    val bypassProvider: String = DEFAULT_BYPASS_PROVIDER
) {
    fun normalized(): LocationConfig = copy(
        name = name.trim(),
        id = id.trim(),
        key = key.trim(),
        bypassProvider = normalizeProvider(bypassProvider)
    )

    fun isComplete(): Boolean = id.isNotBlank() && key.isNotBlank()

    fun displayName(): String = name.ifBlank { id }

    fun providerName(): String = providerDisplayName(bypassProvider)

    companion object {
        const val PROVIDER_JAZZ = "jazz"
        const val PROVIDER_TELEMOST = "telemost"
        const val PROVIDER_WB_STREAM = "wb_stream"
        const val DEFAULT_BYPASS_PROVIDER = PROVIDER_WB_STREAM

        val supportedBypassProviders = listOf(
            PROVIDER_JAZZ,
            PROVIDER_TELEMOST,
            PROVIDER_WB_STREAM
        )

        fun normalizeProvider(value: String): String {
            return when (value.trim().lowercase()) {
                PROVIDER_JAZZ, "sberjazz", "sber_jazz" -> PROVIDER_JAZZ
                PROVIDER_TELEMOST, "yandex", "yandex_telemost" -> PROVIDER_TELEMOST
                PROVIDER_WB_STREAM, "wbstream", "wb-stream", "wildberries" -> PROVIDER_WB_STREAM
                else -> DEFAULT_BYPASS_PROVIDER
            }
        }

        fun providerDisplayName(provider: String): String {
            return when (normalizeProvider(provider)) {
                PROVIDER_JAZZ -> "Jazz"
                PROVIDER_TELEMOST -> "Telemost"
                PROVIDER_WB_STREAM -> "WB Stream"
                else -> "WB Stream"
            }
        }
    }
}

@Serializable
data class LocationEntry(
    @SerialName("storage_id")
    val storageId: String,
    val name: String = "",
    val id: String = "",
    val key: String = "",
    @SerialName("bypass_provider")
    val bypassProvider: String = LocationConfig.DEFAULT_BYPASS_PROVIDER
) {
    val location: LocationConfig
        get() = LocationConfig(name, id, key, bypassProvider).normalized()

    fun normalized(): LocationEntry {
        val config = location
        return copy(
            storageId = storageId.trim(),
            name = config.name,
            id = config.id,
            key = config.key,
            bypassProvider = config.bypassProvider
        )
    }

    companion object {
        fun from(storageId: String, location: LocationConfig): LocationEntry {
            val config = location.normalized()
            return LocationEntry(
                storageId = storageId,
                name = config.name,
                id = config.id,
                key = config.key,
                bypassProvider = config.bypassProvider
            ).normalized()
        }
    }
}

@Serializable
data class LocationBundleV3(
    val version: Int = 3,
    @SerialName("active_location_id")
    val activeLocationId: String? = null,
    val locations: List<LocationEntry> = emptyList()
) {
    fun normalized(): LocationBundleV3 {
        val normalizedLocations = locations
            .map { it.normalized() }
            .filter { it.storageId.isNotBlank() && it.location.isComplete() }
            .distinctBy { it.storageId }

        val active = activeLocationId
            ?.takeIf { id -> normalizedLocations.any { it.storageId == id } }
            ?: normalizedLocations.firstOrNull()?.storageId

        return copy(
            version = 3,
            activeLocationId = active,
            locations = normalizedLocations
        )
    }
}
