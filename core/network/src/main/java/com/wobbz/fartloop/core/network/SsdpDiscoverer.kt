package com.wobbz.fartloop.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.net.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SSDP (Simple Service Discovery Protocol) discoverer for UPnP devices.
 *
 * SSDP PROTOCOL FINDING: This implements proper SSDP discovery without external libraries.
 * SSDP works by sending UDP multicast M-SEARCH requests to 239.255.255.250:1900
 * and listening for HTTP-like responses from UPnP devices.
 *
 * RELIABILITY FINDING: SSDP is the standard UPnP discovery method and much more
 * reliable than port scanning for finding UPnP devices like Sonos, Chromecast, etc.
 */
@Singleton
class SsdpDiscoverer @Inject constructor() {

    companion object {
        private const val SSDP_ADDRESS = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val SSDP_TIMEOUT = 3000 // 3 seconds per search

        // SSDP M-SEARCH request for all UPnP devices
        private const val SSDP_SEARCH_REQUEST =
            "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "ST: upnp:rootdevice\r\n" +
            "MX: 3\r\n\r\n"
    }

    /**
     * Discover UPnP devices using SSDP multicast discovery.
     *
     * SSDP DISCOVERY FINDING: Sends M-SEARCH multicast request and listens for responses.
     * Each response contains device location URL and basic device information.
     */
    suspend fun discover(timeout: Long): Flow<UpnpDevice> = flow {
        Timber.d("SsdpDiscoverer: Starting SSDP discovery (timeout: ${timeout}ms)")

        val discoveredDevices = mutableSetOf<String>()
        val endTime = System.currentTimeMillis() + timeout

        while (System.currentTimeMillis() < endTime) {
            try {
                val devices = performSsdpSearch()
                devices.forEach { device ->
                    val deviceKey = "${device.ipAddress}:${device.port}"
                    if (discoveredDevices.add(deviceKey)) {
                        Timber.d("SsdpDiscoverer: Found new UPnP device: $deviceKey")
                        emit(device)
                    }
                }

                // Wait before next search
                delay(2000)

            } catch (e: Exception) {
                Timber.e(e, "SsdpDiscoverer: SSDP search failed")
                delay(1000)
            }
        }

        Timber.d("SsdpDiscoverer: SSDP discovery complete, found ${discoveredDevices.size} devices")
    }

    /**
     * SSDP SEARCH IMPLEMENTATION: Send multicast M-SEARCH and collect responses.
     *
     * PROTOCOL FINDING: SSDP uses UDP multicast to 239.255.255.250:1900 with
     * HTTP-like request/response format for device discovery.
     */
    private suspend fun performSsdpSearch(): List<UpnpDevice> = withContext(Dispatchers.IO) {
        val devices = mutableListOf<UpnpDevice>()
        var socket: MulticastSocket? = null

        try {
            // Create multicast socket for SSDP
            socket = MulticastSocket()
            socket.soTimeout = SSDP_TIMEOUT

            val group = InetAddress.getByName(SSDP_ADDRESS)
            val searchPacket = DatagramPacket(
                SSDP_SEARCH_REQUEST.toByteArray(),
                SSDP_SEARCH_REQUEST.length,
                group,
                SSDP_PORT
            )

            Timber.d("SsdpDiscoverer: Sending SSDP M-SEARCH multicast")
            socket.send(searchPacket)

            // Listen for responses
            val responseBuffer = ByteArray(4096)
            val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)

            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < SSDP_TIMEOUT) {
                try {
                    socket.receive(responsePacket)
                    val response = String(responsePacket.data, 0, responsePacket.length)
                    val deviceIp = responsePacket.address.hostAddress

                    Timber.d("SsdpDiscoverer: Received SSDP response from $deviceIp")

                    val device = parseSsdpResponse(response, deviceIp)
                    if (device != null) {
                        devices.add(device)
                        Timber.d("SsdpDiscoverer: Parsed device: ${device.friendlyName} at ${device.ipAddress}:${device.port}")
                    }

                } catch (e: SocketTimeoutException) {
                    // Normal timeout, continue listening
                    break
                } catch (e: Exception) {
                    Timber.w(e, "SsdpDiscoverer: Error receiving SSDP response")
                }
            }

        } catch (e: Exception) {
            Timber.e(e, "SsdpDiscoverer: SSDP search exception")
        } finally {
            socket?.close()
        }

        return@withContext devices
    }

    /**
     * SSDP RESPONSE PARSING: Extract device information from SSDP HTTP-like response.
     *
     * RESPONSE FORMAT FINDING: SSDP responses contain LOCATION header with device description URL,
     * SERVER header with device info, and USN with unique service name.
     */
    private fun parseSsdpResponse(response: String, deviceIp: String): UpnpDevice? {
        try {
            val lines = response.split("\r\n")

            // Check if this is a valid SSDP response
            if (lines.firstOrNull()?.startsWith("HTTP/1.1 200") != true) {
                return null
            }

            var location: String? = null
            var server: String? = null
            var usn: String? = null

            lines.forEach { line ->
                when {
                    line.startsWith("LOCATION:", ignoreCase = true) -> {
                        location = line.substringAfter(":").trim()
                    }
                    line.startsWith("SERVER:", ignoreCase = true) -> {
                        server = line.substringAfter(":").trim()
                    }
                    line.startsWith("USN:", ignoreCase = true) -> {
                        usn = line.substringAfter(":").trim()
                    }
                }
            }

            // Extract port from location URL or use common UPnP ports
            val port = extractPortFromLocation(location) ?: detectDevicePort(server, deviceIp)

            val deviceType = determineDeviceType(server, usn, location)
            val friendlyName = generateFriendlyName(deviceType, deviceIp, server)

            // CONTROL URL FINDING: Use device-type-specific control URLs instead of hardcoded paths
            val controlUrl = determineControlUrl(deviceType, location)

            return UpnpDevice(
                friendlyName = friendlyName,
                ipAddress = deviceIp,
                port = port,
                controlUrl = controlUrl,
                deviceType = deviceType.name,
                manufacturer = extractManufacturer(server),
                udn = usn,
                discoveryMethod = "SSDP"
            )

        } catch (e: Exception) {
            Timber.w(e, "SsdpDiscoverer: Failed to parse SSDP response from $deviceIp")
            return null
        }
    }

    /**
     * LOCATION URL PARSING: Extract port number from LOCATION header URL.
     */
    private fun extractPortFromLocation(location: String?): Int? {
        if (location == null) return null

        return try {
            val url = URL(location)
            if (url.port != -1) url.port else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * DEVICE PORT DETECTION: Determine likely control port based on device type.
     *
     * PORT MAPPING FINDING: Different UPnP device types use standard ports:
     * - Sonos: 1400
     * - Chromecast: 8008, 8009
     * - Generic UPnP: 80, 8080, 7000
     */
    private fun detectDevicePort(server: String?, deviceIp: String): Int {
        val serverLower = server?.lowercase() ?: ""

        return when {
            "sonos" in serverLower -> 1400
            "cast" in serverLower || "chromecast" in serverLower -> 8008
            else -> {
                // Try to detect port by testing common UPnP ports
                val commonPorts = listOf(80, 8080, 7000, 8000, 49152)
                commonPorts.firstOrNull { port ->
                    isPortOpen(deviceIp, port)
                } ?: 80
            }
        }
    }

    /**
     * PORT AVAILABILITY CHECK: Quick check if a port is open on the device.
     */
    private fun isPortOpen(ip: String, port: Int): Boolean {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(ip, port), 1000)
            socket.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * DEVICE TYPE DETECTION: Determine device type from SSDP response headers.
     */
    private fun determineDeviceType(server: String?, usn: String?, location: String?): DeviceType {
        val combined = listOfNotNull(server, usn, location).joinToString(" ").lowercase()

        return when {
            "sonos" in combined -> DeviceType.SONOS
            "cast" in combined || "chromecast" in combined -> DeviceType.CHROMECAST
            "dlna" in combined -> DeviceType.DLNA_RENDERER
            "roku" in combined -> DeviceType.ROKU
            else -> DeviceType.UNKNOWN_UPNP
        }
    }

    /**
     * CONTROL URL DETERMINATION: Get device-type-specific control URLs.
     *
     * CONTROL PATH FINDING: Different UPnP devices use different control paths:
     * - Sonos: /MediaRenderer/AVTransport/Control
     * - Generic UPnP/DLNA: /upnp/control/AVTransport1, /MediaRenderer/AVTransport/Control
     * - Chromecast: No UPnP control (uses Google Cast protocol)
     * - Roku: Uses ECP (External Control Protocol), not UPnP
     */
    private fun determineControlUrl(deviceType: DeviceType, location: String?): String {
        return when (deviceType) {
            DeviceType.SONOS -> "/MediaRenderer/AVTransport/Control"
            DeviceType.CHROMECAST -> "/setup/eureka_info" // Won't work but better than generic
            DeviceType.DLNA_RENDERER -> "/MediaRenderer/AVTransport/Control"
            DeviceType.ROKU -> "/keypress/Home" // ECP endpoint (likely won't work for media)
            else -> {
                // Try common UPnP control paths in order of likelihood
                // Most devices use one of these standard paths
                "/upnp/control/AVTransport1"
            }
        }
    }

    /**
     * FRIENDLY NAME GENERATION: Create human-readable device name.
     */
    private fun generateFriendlyName(deviceType: DeviceType, ip: String, server: String?): String {
        val base = when (deviceType) {
            DeviceType.SONOS -> "Sonos Speaker"
            DeviceType.CHROMECAST -> "Chromecast"
            DeviceType.DLNA_RENDERER -> "DLNA Device"
            DeviceType.ROKU -> "Roku Device"
            DeviceType.AIRPLAY -> "AirPlay Device"
            else -> "UPnP Device"
        }

        return "$base at $ip"
    }

    /**
     * MANUFACTURER EXTRACTION: Extract manufacturer from SERVER header.
     */
    private fun extractManufacturer(server: String?): String {
        if (server == null) return "Unknown"

        return when {
            "sonos" in server.lowercase() -> "Sonos"
            "google" in server.lowercase() || "cast" in server.lowercase() -> "Google"
            else -> server.split("/").firstOrNull()?.trim() ?: "Unknown"
        }
    }
}
