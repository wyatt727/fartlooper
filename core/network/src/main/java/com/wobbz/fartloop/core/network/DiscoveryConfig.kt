package com.wobbz.fartloop.core.network

/**
 * Configuration options for device discovery system.
 *
 * PERFORMANCE WIN: Making discovery configurable via preferences
 * This allows users to optimize discovery based on their network environment
 * and known device types, significantly improving discovery performance.
 *
 * Key configuration insights:
 * - Port scan timeout can be reduced for faster discovery on known networks
 * - Custom ports allow targeting specific manufacturer devices
 * - Method selection enables users to disable aggressive scanning if not needed
 * - Concurrency adjustment helps with network performance on different device classes
 */
data class DiscoveryConfig(
    /** Enable SSDP discovery (UPnP standard method) */
    val enableSsdp: Boolean = true,

    /** Enable mDNS discovery (Chromecast, AirPlay, modern devices) */
    val enableMdns: Boolean = true,

    /** Enable port scanning (aggressive fallback method) */
    val enablePortScan: Boolean = true,

    /** Timeout for port scan attempts (milliseconds) */
    val portScanTimeout: Long = 1000L,

    /** Maximum concurrent port scans to prevent network flooding */
    val concurrency: Int = 3,

    /**
     * Custom ports to scan in addition to default media service ports.
     * Useful for environments with non-standard device configurations.
     */
    val customPorts: List<Int> = emptyList(),

    /**
     * Priority order for port scanning - ports higher in list are scanned first.
     * This enables smart port ordering based on success statistics.
     */
    val portPriority: Map<Int, Int> = emptyMap(), // port -> priority (higher = first)

    /** Enable discovery result caching for improved performance */
    val enableCaching: Boolean = true,

    /** Cache TTL in milliseconds (default 60 seconds) */
    val cacheTtlMs: Long = 60_000L,

    /** Manufacturer-specific optimizations */
    val manufacturerSettings: Map<String, ManufacturerConfig> = emptyMap()
) {

    /** Get enabled discovery methods as a set */
    val enabledMethods: Set<DiscoveryMethod>
        get() = buildSet {
            if (enableSsdp) add(DiscoveryMethod.SSDP)
            if (enableMdns) add(DiscoveryMethod.MDNS)
            if (enablePortScan) add(DiscoveryMethod.PORT_SCAN)
        }

    companion object {
        /** Fast discovery preset - prioritizes speed over completeness */
        val FAST_PRESET = DiscoveryConfig(
            enableSsdp = true,
            enableMdns = true,
            enablePortScan = false, // Skip aggressive scanning
            portScanTimeout = 500L,
            concurrency = 5
        )

        /** Comprehensive discovery preset - finds everything but slower */
        val COMPREHENSIVE_PRESET = DiscoveryConfig(
            enableSsdp = true,
            enableMdns = true,
            enablePortScan = true,
            portScanTimeout = 2000L,
            concurrency = 2, // Lower concurrency for stability
            enableCaching = true
        )

        /** Developer preset - includes debugging and extra logging */
        val DEVELOPER_PRESET = DiscoveryConfig(
            enableSsdp = true,
            enableMdns = true,
            enablePortScan = true,
            portScanTimeout = 1500L,
            concurrency = 3,
            enableCaching = false, // Disable caching for testing
            customPorts = listOf(8081, 8082, 8083) // Common dev ports
        )
    }
}

/**
 * Manufacturer-specific configuration optimizations.
 *
 * FINDING: Different manufacturers have predictable patterns:
 * - Sonos devices consistently use 1400-1410 range
 * - Chromecast uses 8008-8099 but 8008 is most common
 * - Samsung TVs often use 8200-8205
 * This allows smart ordering and manufacturer detection.
 */
data class ManufacturerConfig(
    /** Preferred ports for this manufacturer (in priority order) */
    val preferredPorts: List<Int>,

    /** Expected response patterns for faster identification */
    val responsePatterns: List<String> = emptyList(),

    /** Timeout adjustments for this manufacturer */
    val timeoutMultiplier: Double = 1.0,

    /** Control URL patterns */
    val controlUrlPatterns: List<String> = emptyList()
) {
    companion object {
        val SONOS = ManufacturerConfig(
            preferredPorts = listOf(1400, 1401, 1402, 1403, 1404, 1405),
            responsePatterns = listOf("Sonos", "RINCON_", "ZP90", "ZP100", "ZP120"),
            controlUrlPatterns = listOf("/MediaRenderer/AVTransport/Control", "/upnp/control/AVTransport1")
        )

        val CHROMECAST = ManufacturerConfig(
            preferredPorts = listOf(8008, 8009, 8080, 8443),
            responsePatterns = listOf("GoogleCast", "Chromecast", "Google Inc."),
            controlUrlPatterns = listOf("/apps", "/setup/eureka_info")
        )

        val SAMSUNG = ManufacturerConfig(
            preferredPorts = listOf(8200, 8201, 8202, 8203, 9197, 9198),
            responsePatterns = listOf("Samsung", "Smart TV", "SAMSUNG"),
            controlUrlPatterns = listOf("/av/AVTransport", "/upnp/control/AVTransport1")
        )
    }
}
