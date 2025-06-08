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

    // Cache for device information extracted from XML descriptions
    private val deviceInfoCache = mutableMapOf<String, Map<String, String>>()

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
            val friendlyName = generateFriendlyName(deviceType, deviceIp ?: "unknown", server, location)

                        // CONTROL URL FINDING: Parse actual control URL from device description XML
            val parsedControlUrl = parseActualControlUrl(location)
            val controlUrl = parsedControlUrl ?: getFallbackControlUrl(deviceType)

            Timber.i("SsdpDiscoverer: Device ${deviceIp}:${port} -> Control URL: '$controlUrl' (from ${if (parsedControlUrl != null) "XML" else "fallback"})")

            // Get cached device information if available
            val cachedDeviceInfo = location?.let { deviceInfoCache[it] } ?: emptyMap()

            return UpnpDevice(
                friendlyName = friendlyName,
                ipAddress = deviceIp,
                port = port,
                controlUrl = controlUrl,
                deviceType = deviceType.name,
                manufacturer = extractManufacturer(server),
                udn = usn,
                discoveryMethod = "SSDP",
                metadata = cachedDeviceInfo
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
     * ACTUAL CONTROL URL PARSING: Parse the real control URL from device description XML.
     *
     * UPnP SPECIFICATION COMPLIANCE: According to UPnP spec, devices must advertise their
     * actual control URLs in their device description XML at the LOCATION URL.
     * We parse the XML to find the AVTransport service and extract its controlURL.
     *
     * XML STRUCTURE FINDING: UPnP device description follows this pattern:
     * <root>
     *   <device>
     *     <serviceList>
     *       <service>
     *         <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
     *         <controlURL>/actual/control/path</controlURL>
     *       </service>
     *     </serviceList>
     *   </device>
     * </root>
     */
    private fun parseActualControlUrl(locationUrl: String?): String? {
        if (locationUrl == null) return null

        return try {
            Timber.d("SsdpDiscoverer: Fetching device description from $locationUrl to extract control URL")

            val url = URL(locationUrl)
            val connection = url.openConnection()
            connection.connectTimeout = 3000 // 3 second timeout for XML fetch
            connection.readTimeout = 3000

                                    val xmlContent = connection.getInputStream().bufferedReader().readText()

            // Log XML for debugging (first 500 chars to avoid spam)
            Timber.d("SsdpDiscoverer: Device XML preview: ${xmlContent.take(500)}...")

            // Parse XML to find AVTransport service control URL
            val controlUrl = extractAVTransportControlUrl(xmlContent)

            // Extract comprehensive device information for Device Info dialog
            // Store it globally so it can be accessed when creating UpnpDevice
            val deviceInfo = extractDeviceInformation(xmlContent)
            deviceInfoCache[locationUrl] = deviceInfo

            if (controlUrl != null) {
                Timber.i("SsdpDiscoverer: ✅ Extracted real control URL: '$controlUrl' from $locationUrl")
                return controlUrl
            } else {
                Timber.w("SsdpDiscoverer: ❌ Could not find AVTransport control URL in device XML from $locationUrl")
                return null
            }

        } catch (e: Exception) {
            Timber.w(e, "SsdpDiscoverer: Failed to fetch/parse device description from $locationUrl")
            return null
        }
    }

    /**
     * XML PARSING FINDING: Extract AVTransport service control URL from UPnP device description XML.
     *
     * REGEX APPROACH: Using regex for lightweight XML parsing since we only need specific fields.
     * More reliable than full XML parsing for this specific use case.
     *
     * MULTIPLE SERVICE SUPPORT: Some devices have multiple AVTransport services, we take the first one.
     */
    private fun extractAVTransportControlUrl(xmlContent: String): String? {
        try {
            // Look for AVTransport service blocks in the XML
            // Pattern matches the service block containing AVTransport serviceType
            val servicePattern = """
                <service>.*?
                <serviceType[^>]*>.*?AVTransport.*?</serviceType>.*?
                <controlURL[^>]*>(.*?)</controlURL>.*?
                </service>
            """.trimIndent().replace("\n", "").toRegex(
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )

            val serviceMatch = servicePattern.find(xmlContent)
            val controlUrl = serviceMatch?.groupValues?.get(1)?.trim()

            if (!controlUrl.isNullOrBlank()) {
                Timber.d("SsdpDiscoverer: Found AVTransport control URL: '$controlUrl'")
                return controlUrl
            }

            // Fallback: try simpler pattern that just looks for any controlURL near AVTransport
            val simplePattern = "AVTransport.*?<controlURL[^>]*>(.*?)</controlURL>".toRegex(
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )

            val simpleMatch = simplePattern.find(xmlContent)
            val simpleControlUrl = simpleMatch?.groupValues?.get(1)?.trim()

            if (!simpleControlUrl.isNullOrBlank()) {
                Timber.d("SsdpDiscoverer: Found AVTransport control URL (simple pattern): '$simpleControlUrl'")
                return simpleControlUrl
            }

            // Last resort: look for any controlURL containing likely paths
            val anyControlPattern = "<controlURL[^>]*>(.*?(?:AVTransport|MediaRenderer).*?)</controlURL>".toRegex(
                RegexOption.IGNORE_CASE
            )

            val anyMatch = anyControlPattern.find(xmlContent)
            val anyControlUrl = anyMatch?.groupValues?.get(1)?.trim()

            if (!anyControlUrl.isNullOrBlank()) {
                Timber.d("SsdpDiscoverer: Found likely control URL: '$anyControlUrl'")
                return anyControlUrl
            }

            Timber.d("SsdpDiscoverer: No AVTransport control URL found in XML")
            return null

        } catch (e: Exception) {
            Timber.w(e, "SsdpDiscoverer: Error extracting control URL from XML")
            return null
        }
    }

    /**
     * COMPREHENSIVE DEVICE INFORMATION EXTRACTION: Parse detailed device info from UPnP XML.
     *
     * DEVICE INFO EXTRACTION FINDING: UPnP device description XML contains rich metadata
     * about the device including manufacturer details, model information, serial numbers,
     * supported services, and technical specifications that users find valuable.
     *
     * This extracts information for the Device Info dialog including:
     * - Device identity (friendly name, manufacturer, model)
     * - Technical specs (device type, UDN, serial number)
     * - Network info (services, URLs, capabilities)
     * - Manufacturer details (URLs, descriptions)
     */
    private fun extractDeviceInformation(xmlContent: String): Map<String, String> {
        val deviceInfo = mutableMapOf<String, String>()

        try {
            // Extract basic device information
            extractXmlField(xmlContent, "friendlyName")?.let {
                deviceInfo["friendlyName"] = it
            }
            extractXmlField(xmlContent, "manufacturer")?.let {
                deviceInfo["manufacturer"] = it
            }
            extractXmlField(xmlContent, "manufacturerURL")?.let {
                deviceInfo["manufacturerURL"] = it
            }
            extractXmlField(xmlContent, "modelDescription")?.let {
                deviceInfo["modelDescription"] = it
            }
            extractXmlField(xmlContent, "modelName")?.let {
                deviceInfo["modelName"] = it
            }
            extractXmlField(xmlContent, "modelNumber")?.let {
                deviceInfo["modelNumber"] = it
            }
            extractXmlField(xmlContent, "modelURL")?.let {
                deviceInfo["modelURL"] = it
            }
            extractXmlField(xmlContent, "serialNumber")?.let {
                deviceInfo["serialNumber"] = it
            }
            extractXmlField(xmlContent, "UDN")?.let {
                deviceInfo["UDN"] = it
            }
            extractXmlField(xmlContent, "deviceType")?.let {
                deviceInfo["deviceType"] = it
            }

            // Extract presentation URL if available
            extractXmlField(xmlContent, "presentationURL")?.let {
                deviceInfo["presentationURL"] = it
            }

            // Extract services information
            val services = extractServicesInformation(xmlContent)
            if (services.isNotEmpty()) {
                deviceInfo["services"] = services.joinToString(", ")
                deviceInfo["serviceCount"] = services.size.toString()
            }

            // Add XML parsing timestamp
            deviceInfo["xmlParsedAt"] = System.currentTimeMillis().toString()

            Timber.d("SsdpDiscoverer: Extracted ${deviceInfo.size} device info fields")

        } catch (e: Exception) {
            Timber.w(e, "SsdpDiscoverer: Error extracting device information from XML")
            deviceInfo["parseError"] = e.message ?: "Unknown parsing error"
        }

        return deviceInfo
    }

    /**
     * SIMPLE XML FIELD EXTRACTION: Extract a single field value from XML using regex.
     */
    private fun extractXmlField(xmlContent: String, fieldName: String): String? {
        return try {
            val pattern = "<$fieldName[^>]*>(.*?)</$fieldName>".toRegex(RegexOption.IGNORE_CASE)
            val match = pattern.find(xmlContent)
            match?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * SERVICES EXTRACTION: Extract list of available services from device XML.
     */
    private fun extractServicesInformation(xmlContent: String): List<String> {
        val services = mutableListOf<String>()

        try {
            // Find all service blocks
            val servicePattern = "<service>.*?</service>".toRegex(
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )

            servicePattern.findAll(xmlContent).forEach { serviceMatch ->
                val serviceBlock = serviceMatch.value

                // Extract serviceType from each service block
                val serviceType = extractXmlField(serviceBlock, "serviceType")
                if (serviceType != null) {
                    // Clean up the service type for better readability
                    val cleanServiceType = serviceType
                        .replace("urn:schemas-upnp-org:service:", "")
                        .replace("urn:upnp-org:serviceId:", "")
                    services.add(cleanServiceType)
                }
            }

        } catch (e: Exception) {
            Timber.w(e, "SsdpDiscoverer: Error extracting services information")
        }

        return services.distinct()
    }

    /**
     * FALLBACK CONTROL URL: Provide educated guesses when XML parsing fails.
     *
     * FALLBACK STRATEGY: Use device type to provide reasonable default control URLs
     * based on known patterns for major device manufacturers.
     */
    private fun getFallbackControlUrl(deviceType: DeviceType): String {
        val fallbackUrl = when (deviceType) {
            DeviceType.SONOS -> "/MediaRenderer/AVTransport/Control"
            DeviceType.CHROMECAST -> "/setup/eureka_info" // Google Cast API endpoint
            DeviceType.DLNA_RENDERER -> "/MediaRenderer/AVTransport/Control"
            DeviceType.ROKU -> "/keypress/Home" // ECP endpoint (limited functionality)
            else -> "/upnp/control/AVTransport1" // Most common UPnP control path
        }

        Timber.d("SsdpDiscoverer: Using fallback control URL for ${deviceType}: $fallbackUrl")
        return fallbackUrl
    }

    /**
     * FRIENDLY NAME GENERATION: Create human-readable device name.
     * Try to fetch actual friendly name from device description, fall back to generated name.
     */
    private fun generateFriendlyName(deviceType: DeviceType, ip: String, server: String?, location: String? = null): String {
        // Try to get actual friendly name from device description
        val actualFriendlyName = location?.let { fetchActualFriendlyName(it) }

        if (!actualFriendlyName.isNullOrBlank() && actualFriendlyName != "Unknown") {
            return actualFriendlyName
        }

        // Fall back to type-based naming
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
     * FRIENDLY NAME EXTRACTION: Fetch actual friendly name from device description XML.
     *
     * XML PARSING FINDING: Extract the device's advertised friendly name from the same
     * device description XML that contains the control URLs.
     */
    private fun fetchActualFriendlyName(locationUrl: String): String? {
        return try {
            Timber.d("SsdpDiscoverer: Fetching device description from $locationUrl to extract friendly name")

            val url = URL(locationUrl)
            val connection = url.openConnection()
            connection.connectTimeout = 2000 // 2 second timeout
            connection.readTimeout = 2000

            val xmlContent = connection.getInputStream().bufferedReader().readText()

            // Simple XML parsing to extract friendlyName
            val friendlyNamePattern = "<friendlyName>(.*?)</friendlyName>".toRegex(RegexOption.IGNORE_CASE)
            val match = friendlyNamePattern.find(xmlContent)
            val friendlyName = match?.groupValues?.get(1)?.trim()

            if (!friendlyName.isNullOrBlank()) {
                Timber.d("SsdpDiscoverer: ✅ Extracted friendly name: '$friendlyName' from $locationUrl")
                return friendlyName
            }

            Timber.d("SsdpDiscoverer: No friendly name found in device XML")
            null
        } catch (e: Exception) {
            Timber.d("SsdpDiscoverer: Could not fetch friendly name from $locationUrl: ${e.message}")
            null
        }
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
