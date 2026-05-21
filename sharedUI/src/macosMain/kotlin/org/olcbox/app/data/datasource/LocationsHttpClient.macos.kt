package org.olcbox.app.data.datasource

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import org.olcbox.app.data.repository.SubscriptionFetchProxy

internal actual fun createLocationsHttpClient(subscriptionProxy: SubscriptionFetchProxy?): HttpClient {
    return HttpClient {
        expectSuccess = false

        install(HttpTimeout) {
            connectTimeoutMillis = 3_000
            requestTimeoutMillis = 8_000
            socketTimeoutMillis = 8_000
        }
    }
}

internal actual suspend fun <T> withSubscriptionProxyAuthentication(
    subscriptionProxy: SubscriptionFetchProxy?,
    block: suspend () -> T
): T = block()
