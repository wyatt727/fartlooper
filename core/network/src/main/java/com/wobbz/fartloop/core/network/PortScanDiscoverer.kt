package com.wobbz.fartloop.core.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Port scanning discoverer that looks for devices on common media renderer ports.
 * This is a fallback discovery method when UPnP/mDNS discovery fails.
 */
@Singleton
class PortScanDiscoverer @Inject constructor() : DeviceDiscoverer {

    override val name: String = "PortScan"

    // Common ports used by media renderers and streaming devices
    private val commonPorts = listOf(
        8080, 8008, 8009, // Chromecast
        1400, 3400, 3401, // Sonos
        7000, 7001, // Various DLNA
        49152, 49153, 49154, // UPnP dynamic ports
        8200, 9080, // Other common HTTP ports
    )

    override suspend fun discover(timeout: Long): Flow<UpnpDevice> = callbackFlow {
        Timber.d("PortScan Discovery: Starting port scan discovery (timeout: ${timeout}ms)")

        try {
            val localNetwork = getLocalNetworkBase()
            if (localNetwork == null) {
                Timber.w("PortScan Discovery: Could not determine local network")
                close()
                return@callbackFlow
            }

            Timber.d("PortScan Discovery: Scanning network $localNetwork.x")

            val scanJobs = mutableListOf<kotlinx.coroutines.Job>()

            // Scan the detected IP range (e.g., 192.168.4.1 to 192.168.4.254)
            for (hostSuffix in 1..254) {
                val ip = "$localNetwork.$hostSuffix"

                val job = launch(Dispatchers.IO) {
                    scanHostPorts(ip) { device ->
                        // Send device back to the flow
                        trySend(device)
                    }
                }
                scanJobs.add(job)
            }

            // Wait for all scans to complete or timeout
            withTimeoutOrNull(timeout) {
                scanJobs.forEach { it.join() }
            } ?: run {
                Timber.d("PortScan Discovery: Scan timed out, cancelling remaining jobs")
                scanJobs.forEach { it.cancel() }
            }

            Timber.d("PortScan Discovery: Port scan completed")

        } catch (e: Exception) {
            Timber.e(e, "PortScan Discovery: Error during port scan")
        }

        close()
    }

    private suspend fun scanHostPorts(ip: String, onDeviceFound: (UpnpDevice) -> Unit) {
        for (port in commonPorts) {
            try {
                if (isPortOpen(ip, port, 200)) { // 200ms timeout per port
                    Timber.d("PortScan Discovery: Found open port $ip:$port")

                    // Create a basic device entry for any responsive port
                    val device = UpnpDevice(
                        friendlyName = "Device at $ip:$port",
                        ipAddress = ip,
                        port = port,
                        controlUrl = "/",
                        deviceType = "Unknown-PortScan",
                        manufacturer = "Unknown",
                        udn = "portscan-$ip-$port",
                        discoveryMethod = "PortScan"
                    )

                    onDeviceFound(device)
                    break // Only report one port per host to avoid duplicates
                }
            } catch (e: Exception) {
                // Ignore individual port scan failures
            }
        }
    }

    private suspend fun isPortOpen(host: String, port: Int, timeoutMs: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            withTimeoutOrNull(timeoutMs.toLong()) {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                    true
                }
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun getLocalNetworkBase(): String? {
        return try {
            val networkInterfaces = java.net.NetworkInterface.getNetworkInterfaces()
            for (networkInterface in networkInterfaces) {
                if (!networkInterface.isUp || networkInterface.isLoopback) continue

                for (address in networkInterface.inetAddresses) {
                    if (address.isLoopbackAddress || address.isLinkLocalAddress) continue
                    if (address !is java.net.Inet4Address) continue

                    val ip = address.hostAddress ?: continue

                    // Extract network base (e.g., "192.168.1" from "192.168.1.100")
                    val parts = ip.split(".")
                    if (parts.size == 4) {
                        val networkBase = "${parts[0]}.${parts[1]}.${parts[2]}"
                        Timber.d("PortScan Discovery: Using network base: $networkBase")
                        return networkBase
                    }
                }
            }
            null
        } catch (e: Exception) {
            Timber.e(e, "Error determining local network base")
            null
        }
    }
}
