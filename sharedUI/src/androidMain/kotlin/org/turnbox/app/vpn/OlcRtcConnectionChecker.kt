package org.turnbox.app.vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import mobile.Mobile
import org.turnbox.app.data.model.LocationConfig
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

internal object OlcRtcConnectionChecker {
    private val mutex = Mutex()

    suspend fun check(locationConfig: LocationConfig, isVpnAlreadyRunning: Boolean): Long? {
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val config = locationConfig.normalized()
                if (!config.isComplete()) return@withLock null

                if (isVpnAlreadyRunning || Mobile.isRunning()) {
                    return@withLock 1L
                }

                val socksPort = (20001..30000).random()
                val startedAt = System.currentTimeMillis()

                try {
                    Mobile.setProviders()
                    Mobile.start(
                        config.bypassProvider,
                        config.id,
                        config.key,
                        socksPort.toLong(),
                        "",
                        ""
                    )
                    Mobile.waitReady(CONNECTION_CHECK_TIMEOUT_MS)
                    if (probeSocksConnect(socksPort)) {
                        System.currentTimeMillis() - startedAt
                    } else {
                        null
                    }
                } catch (_: Exception) {
                    null
                } finally {
                    runCatching { Mobile.stop() }
                }
            }
        }
    }

    private fun probeSocksConnect(socksPort: Int): Boolean {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", socksPort), SOCKS_PROBE_TIMEOUT_MS)
            socket.soTimeout = SOCKS_PROBE_TIMEOUT_MS

            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())

            output.write(byteArrayOf(0x05, 0x01, 0x00))
            output.flush()

            if (input.readUnsignedByte() != 0x05) return false
            if (input.readUnsignedByte() == 0xFF) return false

            val host = SOCKS_PROBE_HOST.encodeToByteArray()
            output.write(byteArrayOf(0x05, 0x01, 0x00, 0x03, host.size.toByte()))
            output.write(host)
            output.writeShort(SOCKS_PROBE_PORT)
            output.flush()

            if (input.readUnsignedByte() != 0x05) return false
            if (input.readUnsignedByte() != 0x00) return false
            input.readUnsignedByte()

            when (input.readUnsignedByte()) {
                0x01 -> input.skipFully(4)
                0x03 -> input.skipFully(input.readUnsignedByte())
                0x04 -> input.skipFully(16)
                else -> return false
            }
            input.skipFully(2)
            return true
        }
    }

    private fun DataInputStream.skipFully(byteCount: Int) {
        var remaining = byteCount
        while (remaining > 0) {
            val skipped = skipBytes(remaining)
            if (skipped <= 0) throw java.io.EOFException()
            remaining -= skipped
        }
    }

    private const val CONNECTION_CHECK_TIMEOUT_MS = 8_000L
    private const val SOCKS_PROBE_TIMEOUT_MS = 5_000
    private const val SOCKS_PROBE_HOST = "example.com"
    private const val SOCKS_PROBE_PORT = 443
}
