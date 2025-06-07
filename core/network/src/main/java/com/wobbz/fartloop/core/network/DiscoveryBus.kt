package com.wobbz.fartloop.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central hub for device discovery that merges results from all discovery methods.
 *
 * Coordinates SSDP, mDNS, and port scan discoverers to provide a unified stream
 * of discovered devices. Handles deduplication and provides discovery metrics.
 *
 * ENHANCEMENT: Added configurable discovery with performance optimizations
 * Supports custom port lists, smart ordering, and method selection for
 * improved discovery performance in different network environments.
 */
@Singleton
class DiscoveryBus @Inject constructor(
    private val ssdpDiscoverer: SsdpDiscoverer,
    private val mdnsDiscoverer: MdnsDiscoverer,
    private val portScanDiscoverer: PortScanDiscoverer
) {

    // Track discovered devices to avoid duplicates
    private val discoveredDevices = mutableSetOf<String>()

    // Current discovery configuration
    private var currentConfig = DiscoveryConfig()

    // Discovery result cache for performance optimization
    private val discoveryCache = mutableMapOf<String, CachedDiscoveryResult>()

    /**
     * Cached discovery result with TTL
     */
    private data class CachedDiscoveryResult(
        val devices: List<UpnpDevice>,
        val timestamp: Long,
        val networkSignature: String
    ) {
        fun isExpired(ttlMs: Long): Boolean =
            (System.currentTimeMillis() - timestamp) > ttlMs
    }

    /**
     * Update discovery configuration for subsequent operations.
     *
     * PERFORMANCE WIN: Allows runtime configuration of discovery methods
     * Users can disable aggressive port scanning or adjust timeouts based on
     * network performance and known device environment.
     */
    fun updateConfig(config: DiscoveryConfig) {
        currentConfig = config
        Timber.i("Discovery configuration updated: enabled methods = ${config.enabledMethods}")

        // Update port scan discoverer with new configuration
        if (config.customPorts.isNotEmpty()) {
            portScanDiscoverer.updateCustomPorts(config.customPorts)
        }

        if (config.portPriority.isNotEmpty()) {
            portScanDiscoverer.updatePortPriority(config.portPriority)
        }
    }

    /**
     * Discover all available devices using configured discovery methods.
     *
     * @param timeoutMs Maximum time to spend discovering (applies to each method)
     * @param config Optional configuration override for this discovery session
     * @return Flow of unique devices discovered by enabled methods
     */
    suspend fun discoverAll(
        timeoutMs: Long,
        config: DiscoveryConfig? = null
    ): Flow<UpnpDevice> = withContext(Dispatchers.IO) {
        val activeConfig = config ?: currentConfig

        // Check cache first if enabled
        if (activeConfig.enableCaching) {
            val cachedResult = checkDiscoveryCache(activeConfig.cacheTtlMs)
            if (cachedResult != null) {
                Timber.d("Using cached discovery results (${cachedResult.size} devices)")
                return@withContext cachedResult.asFlow()
            }
        }
        callbackFlow {
            Timber.i("Starting configurable device discovery: ${activeConfig.enabledMethods} (timeout: ${timeoutMs}ms)")

            // Clear previous discovery state
            discoveredDevices.clear()
            val allDevices = mutableListOf<UpnpDevice>()

            // Create discovery jobs for enabled methods only
            val jobs = mutableListOf<kotlinx.coroutines.Job>()

            if (DiscoveryMethod.SSDP in activeConfig.enabledMethods) {
                jobs.add(launch {
                    try {
                        Timber.d("Starting SSDP discovery...")
                        ssdpDiscoverer.discover(timeoutMs).collect { device ->
                            if (addUniqueDevice(device)) {
                                allDevices.add(device)
                                trySend(device)
                            }
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "SSDP discovery failed")
                    }
                })
            }

            if (DiscoveryMethod.MDNS in activeConfig.enabledMethods) {
                jobs.add(launch {
                    try {
                        Timber.d("Starting mDNS discovery...")
                        mdnsDiscoverer.discover(timeoutMs).collect { device ->
                            if (addUniqueDevice(device)) {
                                allDevices.add(device)
                                trySend(device)
                            }
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "mDNS discovery failed")
                    }
                })
            }

            if (DiscoveryMethod.PORT_SCAN in activeConfig.enabledMethods) {
                jobs.add(launch {
                    try {
                        Timber.d("Starting configured port scan discovery...")
                        // Use configured timeout for port scanning
                        val scanTimeout = if (activeConfig.portScanTimeout != 1000L) {
                            activeConfig.portScanTimeout
                        } else timeoutMs

                        portScanDiscoverer.discover(scanTimeout).collect { device ->
                            if (addUniqueDevice(device)) {
                                allDevices.add(device)
                                trySend(device)
                            }
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Port scan discovery failed")
                    }
                })
            }

            // Wait for all discovery jobs to complete or timeout
            awaitClose {
                Timber.i("Discovery completed: found ${discoveredDevices.size} unique devices via ${activeConfig.enabledMethods}")

                // Cache results if enabled
                if (activeConfig.enableCaching && allDevices.isNotEmpty()) {
                    cacheDiscoveryResults(allDevices, activeConfig.cacheTtlMs)
                }

                jobs.forEach { it.cancel() }
            }
        }
    }

    /**
     * Discover devices using only specific discovery methods.
     * Useful for testing or when certain methods should be disabled.
     */
    suspend fun discoverWith(
        methods: Set<DiscoveryMethod>,
        timeoutMs: Long
    ): Flow<UpnpDevice> = withContext(Dispatchers.IO) {
        callbackFlow {
            Timber.i("Starting selective device discovery: $methods (timeout: ${timeoutMs}ms)")

            discoveredDevices.clear()
            val jobs = mutableListOf<kotlinx.coroutines.Job>()

            if (DiscoveryMethod.SSDP in methods) {
                jobs.add(launch {
                    try {
                        ssdpDiscoverer.discover(timeoutMs).collect { device ->
                            if (addUniqueDevice(device)) {
                                trySend(device)
                            }
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "SSDP discovery failed")
                    }
                })
            }

            if (DiscoveryMethod.MDNS in methods) {
                jobs.add(launch {
                    try {
                        mdnsDiscoverer.discover(timeoutMs).collect { device ->
                            if (addUniqueDevice(device)) {
                                trySend(device)
                            }
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "mDNS discovery failed")
                    }
                })
            }

            if (DiscoveryMethod.PORT_SCAN in methods) {
                jobs.add(launch {
                    try {
                        portScanDiscoverer.discover(timeoutMs).collect { device ->
                            if (addUniqueDevice(device)) {
                                trySend(device)
                            }
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Port scan discovery failed")
                    }
                })
            }

            awaitClose {
                Timber.i("Selective discovery completed, found ${discoveredDevices.size} unique devices")
                jobs.forEach { it.cancel() }
            }
        }
    }

    /**
     * Get discovery statistics for debugging and metrics.
     */
    fun getDiscoveryStats(): DiscoveryStats {
        return DiscoveryStats(
            totalDevicesFound = discoveredDevices.size,
            uniqueDeviceKeys = discoveredDevices.toList(),
            cacheHitCount = discoveryCache.size,
            currentConfig = currentConfig
        )
    }

    /**
     * Check discovery cache for recent results.
     *
     * PERFORMANCE WIN: Cache discovered devices for 60s to avoid re-scanning same network
     * Provides significant speed improvement for repeated discovery operations.
     */
    private fun checkDiscoveryCache(ttlMs: Long): List<UpnpDevice>? {
        val networkSignature = generateNetworkSignature()
        val cached = discoveryCache[networkSignature]

        return if (cached != null && !cached.isExpired(ttlMs)) {
            Timber.d("Cache hit: returning ${cached.devices.size} cached devices")
            cached.devices
        } else {
            if (cached != null) {
                Timber.v("Cache expired, removing stale entry")
                discoveryCache.remove(networkSignature)
            }
            null
        }
    }

    /**
     * Cache discovery results for performance optimization.
     */
    private fun cacheDiscoveryResults(devices: List<UpnpDevice>, ttlMs: Long) {
        if (devices.isEmpty()) return

        val networkSignature = generateNetworkSignature()
        val cachedResult = CachedDiscoveryResult(
            devices = devices.toList(),
            timestamp = System.currentTimeMillis(),
            networkSignature = networkSignature
        )

        discoveryCache[networkSignature] = cachedResult
        Timber.d("Cached ${devices.size} discovery results for network: $networkSignature")

        // Clean up old cache entries
        cleanupExpiredCache(ttlMs)
    }

    /**
     * Generate a signature for the current network to use as cache key.
     * FINDING: Network interface changes indicate we should invalidate cache
     */
    private fun generateNetworkSignature(): String {
        return try {
            val localHost = java.net.InetAddress.getLocalHost()
            val networkInterface = java.net.NetworkInterface.getByInetAddress(localHost)
            "${localHost.hostAddress}_${networkInterface?.name ?: "unknown"}"
        } catch (e: Exception) {
            "default_network_${System.currentTimeMillis() / 60000}" // Change every minute as fallback
        }
    }

    /**
     * Clean up expired cache entries to prevent memory leaks.
     */
    private fun cleanupExpiredCache(ttlMs: Long) {
        val expiredKeys = discoveryCache.entries.filter {
            it.value.isExpired(ttlMs)
        }.map { it.key }

        expiredKeys.forEach { key ->
            discoveryCache.remove(key)
        }

        if (expiredKeys.isNotEmpty()) {
            Timber.v("Cleaned up ${expiredKeys.size} expired cache entries")
        }
    }

    /**
     * Clear discovery cache manually.
     * Useful for testing or when network configuration changes.
     */
    fun clearCache() {
        discoveryCache.clear()
        Timber.d("Discovery cache cleared")
    }

    /**
     * Add a device to the discovered set if it's unique.
     * Uses IP:port combination as the uniqueness key.
     *
     * @return true if device was added (is unique), false if duplicate
     */
    private fun addUniqueDevice(device: UpnpDevice): Boolean {
        val deviceKey = "${device.ipAddress}:${device.port}"

        return if (discoveredDevices.add(deviceKey)) {
            Timber.d("Added unique device: $deviceKey (${device.friendlyName}) via ${device.discoveryMethod}")
            true
        } else {
            Timber.v("Duplicate device ignored: $deviceKey via ${device.discoveryMethod}")
            false
        }
    }
}

/**
 * Available discovery methods.
 */
enum class DiscoveryMethod {
    SSDP,
    MDNS,
    PORT_SCAN
}

/**
 * Discovery statistics for monitoring and debugging.
 *
 * ENHANCEMENT: Added cache metrics and configuration tracking
 * for better debugging and performance monitoring.
 */
data class DiscoveryStats(
    val totalDevicesFound: Int,
    val uniqueDeviceKeys: List<String>,
    val cacheHitCount: Int = 0,
    val currentConfig: DiscoveryConfig
)
