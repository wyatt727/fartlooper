package com.wobbz.fartloop.core.blast

/**
 * Represents a discovered UPnP/DLNA/Chromecast device with connection state
 * Used by DeviceChip components to show real-time status
 */
data class DiscoveredDevice(
    val id: String,                    // Unique device identifier
    val name: String,                  // Human-readable device name
    val type: DeviceType,              // Type of device (UPnP, Chromecast, etc.)
    val ipAddress: String,             // Device IP address
    val port: Int,                     // Control port
    val status: DeviceStatus,          // Current connection/blast status
    val lastSeen: Long = System.currentTimeMillis()  // Last discovery timestamp
)

/**
 * Device type enum for different protocols discovered
 */
enum class DeviceType {
    UPNP,      // Standard UPnP/DLNA device
    CHROMECAST, // Google Chromecast
    SONOS,     // Sonos speaker
    AIRPLAY,   // Apple AirPlay device
    SAMSUNG,   // Samsung smart TV/device
    UNKNOWN    // Unidentified device type
}

/**
 * Device status for real-time UI updates
 * Maps to DeviceChipColors in the design system
 */
enum class DeviceStatus {
    DISCOVERED,  // Just found via discovery
    CONNECTING,  // Attempting SOAP connection
    CONNECTED,   // Successfully connected
    BLASTING,    // Currently playing audio
    SUCCESS,     // Blast completed successfully
    FAILED,      // Connection or blast failed
    IDLE         // Not currently active
}

/**
 * Blast progress stages for bottom sheet animation
 * Maps to the workflow described in PDR Section 7.2
 */
enum class BlastStage {
    IDLE,           // Not running
    HTTP_STARTING,  // Spinning up NanoHTTPD server
    DISCOVERING,    // SSDP + mDNS + port scan discovery
    BLASTING,       // Sending SOAP commands to devices
    COMPLETING,     // Final cleanup and metrics collection
    COMPLETED       // Finished (success or failure)
}

/**
 * Blast phase for notification display
 */
enum class BlastPhase {
    IDLE,           // Not running
    HTTP_STARTING,  // Starting HTTP server
    DISCOVERING,    // Discovering devices
    BLASTING,       // Blasting to devices
    COMPLETE        // Operation complete
}

/**
 * Real-time metrics for the MetricsOverlay HUD
 * Based on PDR specifications for telemetry tracking
 */
data class BlastMetrics(
    val httpStartupMs: Long = 0L,           // Time to start HTTP server (target: <40ms)
    val discoveryTimeMs: Long = 0L,         // Time to complete discovery (target: ~2100ms)
    val totalDevicesFound: Int = 0,         // Total devices discovered
    val connectionsAttempted: Int = 0,      // Number of connection attempts
    val successfulBlasts: Int = 0,          // Successfully completed blasts
    val failedBlasts: Int = 0,              // Failed blast attempts
    val averageLatencyMs: Long = 0L,        // Average SOAP response time
    val isRunning: Boolean = false,         // Whether blast is currently active

    // Enhanced metrics for detailed performance analysis
    val deviceResponseTimes: Map<String, Long> = emptyMap(),        // Device ID -> response time in ms
    val successRateByManufacturer: Map<String, Float> = emptyMap(), // Manufacturer -> success ratio (0.0-1.0)
    val portScanEfficiency: Map<Int, Int> = emptyMap(),             // Port -> successful discovery count
    val networkLatency: Long = 0L,                                  // Network round-trip time
    val dnsResolutionTime: Long = 0L,                               // DNS lookup time for discovery
    val discoveryMethodStats: DiscoveryMethodStats = DiscoveryMethodStats()  // Per-method performance
) {
    /**
     * Calculate success ratio for pie chart display
     */
    val successRatio: Float get() = if (connectionsAttempted > 0) {
        successfulBlasts.toFloat() / connectionsAttempted.toFloat()
    } else 0f

    /**
     * Total blast time for progress tracking
     */
    val totalBlastTimeMs: Long get() = httpStartupMs + discoveryTimeMs + averageLatencyMs

    /**
     * Get the fastest responding device (for performance insights)
     */
    val fastestDeviceMs: Long get() = deviceResponseTimes.values.minOrNull() ?: 0L

    /**
     * Get the slowest responding device (for troubleshooting)
     */
    val slowestDeviceMs: Long get() = deviceResponseTimes.values.maxOrNull() ?: 0L

    /**
     * Calculate manufacturer performance ranking
     * Returns list of manufacturers sorted by success rate (best first)
     */
    val manufacturerPerformanceRanking: List<Pair<String, Float>> get() =
        successRateByManufacturer.toList().sortedByDescending { it.second }

    /**
     * Get most efficient discovery ports (highest success rate)
     */
    val mostEfficientPorts: List<Pair<Int, Int>> get() =
        portScanEfficiency.toList().sortedByDescending { it.second }.take(5)

    /**
     * Identify performance bottlenecks in the blast pipeline
     */
    val performanceBottleneck: String get() = when {
        httpStartupMs > 200L -> "HTTP_STARTUP" // Server taking too long
        discoveryTimeMs > 5000L -> "DISCOVERY" // Network discovery slow
        averageLatencyMs > 1000L -> "DEVICE_RESPONSE" // Devices responding slowly
        successRatio < 0.7f -> "DEVICE_COMPATIBILITY" // Many devices failing
        else -> "NONE" // Performance within acceptable ranges
    }
}

/**
 * Discovery method performance statistics
 */
data class DiscoveryMethodStats(
    val ssdpDevicesFound: Int = 0,     // Devices found via SSDP
    val mdnsDevicesFound: Int = 0,     // Devices found via mDNS
    val portScanDevicesFound: Int = 0, // Devices found via port scanning
    val ssdpTimeMs: Long = 0L,         // Time spent on SSDP discovery
    val mdnsTimeMs: Long = 0L,         // Time spent on mDNS discovery
    val portScanTimeMs: Long = 0L      // Time spent on port scanning
) {
    /**
     * Calculate efficiency ratio for each discovery method
     * Higher values indicate better devices-found-per-time ratio
     */
    val ssdpEfficiency: Float get() = if (ssdpTimeMs > 0) ssdpDevicesFound.toFloat() / ssdpTimeMs * 1000 else 0f
    val mdnsEfficiency: Float get() = if (mdnsTimeMs > 0) mdnsDevicesFound.toFloat() / mdnsTimeMs * 1000 else 0f
    val portScanEfficiency: Float get() = if (portScanTimeMs > 0) portScanDevicesFound.toFloat() / portScanTimeMs * 1000 else 0f

    /**
     * Get the most effective discovery method for this network
     */
    val mostEffectiveMethod: String get() = when {
        ssdpEfficiency >= mdnsEfficiency && ssdpEfficiency >= portScanEfficiency -> "SSDP"
        mdnsEfficiency >= portScanEfficiency -> "mDNS"
        else -> "PORT_SCAN"
    }
}
