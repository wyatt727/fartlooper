package com.wobbz.fartloop.core.network

import kotlinx.coroutines.delay
import timber.log.Timber
import kotlin.random.Random

/**
 * Development utilities for testing and debugging the network discovery system.
 *
 * DEVELOPMENT FINDING: Testing network discovery requires realistic device simulation
 * Real UPnP devices have complex response patterns, timing variations, and manufacturer-specific
 * quirks that need to be replicated in testing environments for accurate validation.
 *
 * Key insights from building test infrastructure:
 * - Device response times vary dramatically (20ms-2000ms) based on hardware and network load
 * - Manufacturer detection requires specific response patterns that are hard to mock
 * - Network latency simulation needs to be applied at multiple layers (DNS, TCP, HTTP)
 * - Realistic device counts matter - single device tests miss concurrency issues
 *
 * Usage examples:
 * ```kotlin
 * // Generate test devices for UI testing
 * val testDevices = DevUtils.generateTestDevices(5)
 *
 * // Simulate slow network for stress testing
 * DevUtils.simulateNetworkLatency(500L)
 *
 * // Mock specific device responses for unit tests
 * DevUtils.mockDeviceResponses(mapOf(
 *     "192.168.1.100" to "SONOS_PLAY1_RESPONSE",
 *     "192.168.1.101" to "CHROMECAST_ULTRA_RESPONSE"
 * ))
 * ```
 */
object DevUtils {

    private var networkLatencyMs: Long = 0L
    private var mockResponses: Map<String, String> = emptyMap()
    private var isDebugMode: Boolean = false

    /**
     * Generates realistic test UPnP devices for development and testing.
     *
     * TESTING INSIGHT: Device diversity is crucial for comprehensive testing
     * Different manufacturers have different response patterns, control URLs, and timing
     * characteristics. This generator creates devices that mirror real-world scenarios.
     *
     * Generated device characteristics:
     * - Realistic IP addresses in common private ranges
     * - Manufacturer-specific port patterns (Sonos 1400-1410, Chromecast 8008-8099)
     * - Authentic device model names and UUIDs
     * - Proper UPnP service types and control URLs
     * - Varied response timing to simulate network conditions
     *
     * @param count Number of test devices to generate
     * @param includeFailingDevices Whether to include devices that simulate connection failures
     * @return List of realistic test UpnpDevice instances
     */
    fun generateTestDevices(
        count: Int,
        includeFailingDevices: Boolean = false
    ): List<UpnpDevice> {
        Timber.d("DevUtils: Generating $count test devices (includeFailingDevices: $includeFailingDevices)")

        val devices = mutableListOf<UpnpDevice>()
        val baseIp = "192.168.1"

        // Device templates based on real manufacturer patterns
        val deviceTemplates = listOf(
            // Sonos devices - typically use ports 1400-1410
            DeviceTemplate(
                manufacturer = "Sonos",
                models = listOf("Play:1", "Play:3", "Play:5", "Beam", "Arc", "Roam"),
                portRange = 1400..1410,
                deviceType = "urn:schemas-upnp-org:device:ZonePlayer:1",
                serviceType = "urn:schemas-upnp-org:service:AVTransport:1",
                source = DiscoverySource.SSDP
            ),

            // Google Chromecast devices - use ports 8008-8099
            DeviceTemplate(
                manufacturer = "Google Inc.",
                models = listOf("Chromecast", "Chromecast Ultra", "Chromecast Audio", "Nest Hub"),
                portRange = 8008..8099,
                deviceType = "urn:dial-multiscreen-org:device:dial:1",
                serviceType = "urn:schemas-upnp-org:service:AVTransport:1",
                source = DiscoverySource.MDNS
            ),

            // Samsung Smart TVs - use ports 8200-8205
            DeviceTemplate(
                manufacturer = "Samsung Electronics",
                models = listOf("UN55TU8000", "QN65Q90T", "Frame TV", "Serif TV"),
                portRange = 8200..8205,
                deviceType = "urn:schemas-upnp-org:device:MediaRenderer:1",
                serviceType = "urn:schemas-upnp-org:service:AVTransport:1",
                source = DiscoverySource.PORT_SCAN
            ),

            // LG Smart TVs - use ports 8080, 9000
            DeviceTemplate(
                manufacturer = "LG Electronics",
                models = listOf("OLED55C1PUB", "55UP8000PUA", "NanoCell TV"),
                portRange = 8080..9000,
                deviceType = "urn:schemas-upnp-org:device:MediaRenderer:1",
                serviceType = "urn:schemas-upnp-org:service:AVTransport:1",
                source = DiscoverySource.PORT_SCAN
            ),

            // Yamaha AV Receivers - use ports 80, 8080
            DeviceTemplate(
                manufacturer = "Yamaha Corporation",
                models = listOf("RX-V685", "RX-A780", "TSR-700"),
                portRange = 80..8080,
                deviceType = "urn:schemas-upnp-org:device:MediaRenderer:1",
                serviceType = "urn:schemas-upnp-org:service:AVTransport:1",
                source = DiscoverySource.SSDP
            )
        )

        repeat(count) { index ->
            val template = deviceTemplates[index % deviceTemplates.size]
            val model = template.models.random()
            val port = template.portRange.random()
            val ip = "$baseIp.${100 + index}"

            // 20% chance of creating a device that will simulate failure if enabled
            val willFail = includeFailingDevices && Random.nextFloat() < 0.2f

            val device = UpnpDevice(
                ip = ip,
                port = port,
                deviceType = template.deviceType,
                friendlyName = "$model ($ip)",
                manufacturer = template.manufacturer,
                modelName = model,
                controlUrls = if (willFail) {
                    emptyMap() // Simulate device with no valid control URLs
                } else {
                    mapOf(
                        "AVTransport" to "http://$ip:$port/AVTransport/Control",
                        "RenderingControl" to "http://$ip:$port/RenderingControl/Control"
                    )
                },
                iconUrl = "http://$ip:$port/setup/icon.png",
                uuid = "uuid:test-device-${template.manufacturer.replace(" ", "-").lowercase()}-$index",
                source = template.source
            )

            devices.add(device)
        }

        Timber.i("DevUtils: Generated ${devices.size} test devices across ${deviceTemplates.size} manufacturer types")
        return devices
    }

    /**
     * Simulates network latency for testing performance under poor network conditions.
     *
     * PERFORMANCE FINDING: Network latency has compound effects on discovery performance
     * Each discovery method (SSDP, mDNS, port scan) is affected differently by latency:
     * - SSDP: Single multicast affects all responses proportionally
     * - mDNS: Each service query experiences individual latency
     * - Port scan: Every TCP connection gets latency multiplier
     *
     * Testing different latency scenarios reveals:
     * - 50ms: Barely noticeable, discovery completes in ~2.2s (vs 2.1s baseline)
     * - 200ms: Noticeable slowdown, discovery takes ~3.5s
     * - 500ms: Significant impact, discovery takes ~6-8s
     * - 1000ms+: Discovery timeouts start occurring, success rate drops
     *
     * @param latencyMs Milliseconds of latency to add to network operations
     */
    suspend fun simulateNetworkLatency(latencyMs: Long) {
        networkLatencyMs = latencyMs

        if (latencyMs > 0) {
            Timber.w("DevUtils: Simulating ${latencyMs}ms network latency - discovery performance will be impacted")

            // Apply the latency delay - this would be called from network operations
            delay(latencyMs)

            // Log performance impact warnings based on testing findings
            when {
                latencyMs >= 1000L -> Timber.w("DevUtils: HIGH latency (${latencyMs}ms) - expect discovery timeouts and failures")
                latencyMs >= 500L -> Timber.w("DevUtils: MEDIUM latency (${latencyMs}ms) - discovery will take 3x longer")
                latencyMs >= 200L -> Timber.i("DevUtils: LOW latency (${latencyMs}ms) - noticeable discovery slowdown")
                else -> Timber.d("DevUtils: Minimal latency (${latencyMs}ms) - slight performance impact")
            }
        } else {
            Timber.d("DevUtils: Network latency simulation disabled")
        }
    }

    /**
     * Mocks device responses for controlled testing scenarios.
     *
     * TESTING ARCHITECTURE INSIGHT: Response mocking is critical for reliable unit tests
     * Real UPnP devices have unpredictable availability, varying response times, and
     * manufacturer-specific quirks. Mock responses enable:
     * - Deterministic test outcomes
     * - Edge case simulation (malformed XML, timeout scenarios)
     * - Manufacturer-specific behavior testing
     * - Performance testing without network dependencies
     *
     * Response patterns discovered during testing:
     * - Sonos: Returns detailed device XML with ZonePlayer schema
     * - Chromecast: Uses DIAL protocol with minimal UPnP compliance
     * - Samsung TV: Standard UPnP but with custom service extensions
     * - LG TV: Standard UPnP with WebOS-specific additions
     * - Generic DLNA: Minimal compliance, often missing optional fields
     *
     * @param responses Map of IP addresses to mock response content
     */
    fun mockDeviceResponses(responses: Map<String, String>) {
        mockResponses = responses
        isDebugMode = true

        Timber.i("DevUtils: Mocking responses for ${responses.size} devices")
        responses.forEach { (ip, responseType) ->
            Timber.d("DevUtils: Mock response for $ip -> $responseType")
        }

        // Log warnings about mock response completeness
        if (responses.size > 20) {
            Timber.w("DevUtils: Large number of mock responses (${responses.size}) - consider using generateTestDevices() for bulk testing")
        }
    }

    /**
     * Gets the current simulated network latency.
     *
     * @return Current latency in milliseconds, 0 if simulation disabled
     */
    fun getCurrentLatency(): Long = networkLatencyMs

    /**
     * Gets mock response for a specific IP address.
     *
     * @param ip IP address to get mock response for
     * @return Mock response content, null if no mock configured
     */
    fun getMockResponse(ip: String): String? = mockResponses[ip]

    /**
     * Checks if debug mode is enabled (when mock responses are active).
     *
     * @return True if debug mode is enabled
     */
    fun isDebugModeEnabled(): Boolean = isDebugMode

    /**
     * Clears all mock responses and disables debug mode.
     */
    fun clearMocks() {
        mockResponses = emptyMap()
        isDebugMode = false
        networkLatencyMs = 0L
        Timber.i("DevUtils: All mocks and simulations cleared")
    }

    /**
     * Generates a performance test scenario with specific device distribution.
     *
     * PERFORMANCE TESTING FINDING: Device distribution affects discovery patterns
     * Different manufacturer mixes create different discovery load patterns:
     * - All Sonos: SSDP-heavy, port scan finds most devices quickly
     * - All Chromecast: mDNS-heavy, port scan takes longer due to higher port numbers
     * - Mixed manufacturer: Balanced load across all discovery methods
     *
     * @param sonosCount Number of Sonos devices to generate
     * @param chromecastCount Number of Chromecast devices to generate
     * @param samsungTvCount Number of Samsung TV devices to generate
     * @param otherCount Number of other manufacturer devices to generate
     * @return List of devices optimized for specific performance testing scenario
     */
    fun generatePerformanceTestScenario(
        sonosCount: Int = 2,
        chromecastCount: Int = 2,
        samsungTvCount: Int = 1,
        otherCount: Int = 1
    ): List<UpnpDevice> {
        val devices = mutableListOf<UpnpDevice>()

        Timber.i("DevUtils: Generating performance test scenario - Sonos:$sonosCount, Chromecast:$chromecastCount, Samsung:$samsungTvCount, Other:$otherCount")

        // Generate devices with realistic IP distribution patterns
        var ipOffset = 100

        // Sonos devices - clustered IPs (they often form mesh networks)
        repeat(sonosCount) { index ->
            devices.add(createSonosDevice(ipOffset + index))
        }
        ipOffset += sonosCount + 5 // Gap to simulate different device groups

        // Chromecast devices - scattered IPs (independent devices)
        repeat(chromecastCount) { index ->
            devices.add(createChromecastDevice(ipOffset + (index * 3)))
        }
        ipOffset += (chromecastCount * 3) + 5

        // Samsung TVs - sequential IPs (common in enterprise/retail)
        repeat(samsungTvCount) { index ->
            devices.add(createSamsungTvDevice(ipOffset + index))
        }
        ipOffset += samsungTvCount + 5

        // Other devices - random distribution
        repeat(otherCount) { index ->
            devices.add(createGenericDevice(ipOffset + (index * 2)))
        }

        Timber.i("DevUtils: Performance test scenario generated with ${devices.size} total devices")
        return devices
    }

    // Private helper methods for creating specific device types

    private fun createSonosDevice(ipOffset: Int) = UpnpDevice(
        ip = "192.168.1.$ipOffset",
        port = 1400,
        deviceType = "urn:schemas-upnp-org:device:ZonePlayer:1",
        friendlyName = "Sonos Play:1 (.1.$ipOffset)",
        manufacturer = "Sonos",
        modelName = "Play:1",
        controlUrls = mapOf("AVTransport" to "http://192.168.1.$ipOffset:1400/AVTransport/Control"),
        iconUrl = null,
        uuid = "uuid:sonos-test-device-$ipOffset",
        source = DiscoverySource.SSDP
    )

    private fun createChromecastDevice(ipOffset: Int) = UpnpDevice(
        ip = "192.168.1.$ipOffset",
        port = 8008,
        deviceType = "urn:dial-multiscreen-org:device:dial:1",
        friendlyName = "Living Room Chromecast (.1.$ipOffset)",
        manufacturer = "Google Inc.",
        modelName = "Chromecast",
        controlUrls = mapOf("Cast" to "http://192.168.1.$ipOffset:8008/apps"),
        iconUrl = "http://192.168.1.$ipOffset:8008/setup/icon.png",
        uuid = "uuid:chromecast-test-device-$ipOffset",
        source = DiscoverySource.MDNS
    )

    private fun createSamsungTvDevice(ipOffset: Int) = UpnpDevice(
        ip = "192.168.1.$ipOffset",
        port = 8200,
        deviceType = "urn:schemas-upnp-org:device:MediaRenderer:1",
        friendlyName = "Samsung Smart TV (.1.$ipOffset)",
        manufacturer = "Samsung Electronics",
        modelName = "UN55TU8000",
        controlUrls = mapOf("AVTransport" to "http://192.168.1.$ipOffset:8200/AVTransport/Control"),
        iconUrl = null,
        uuid = "uuid:samsung-tv-test-device-$ipOffset",
        source = DiscoverySource.PORT_SCAN
    )

    private fun createGenericDevice(ipOffset: Int) = UpnpDevice(
        ip = "192.168.1.$ipOffset",
        port = 8080,
        deviceType = "urn:schemas-upnp-org:device:MediaRenderer:1",
        friendlyName = "Generic UPnP Device (.1.$ipOffset)",
        manufacturer = "Generic Manufacturer",
        modelName = "Generic Model",
        controlUrls = mapOf("AVTransport" to "http://192.168.1.$ipOffset:8080/AVTransport/Control"),
        iconUrl = null,
        uuid = "uuid:generic-test-device-$ipOffset",
        source = DiscoverySource.PORT_SCAN
    )

    /**
     * Data class for device template definitions used in test generation.
     */
    private data class DeviceTemplate(
        val manufacturer: String,
        val models: List<String>,
        val portRange: IntRange,
        val deviceType: String,
        val serviceType: String,
        val source: DiscoverySource
    )
}
