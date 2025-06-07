package com.wobbz.fartloop.core.network

import kotlinx.coroutines.flow.Flow

/**
 * Base interface for all device discovery mechanisms.
 * Each discoverer (SSDP, mDNS, port scan) implements this interface.
 */
interface DeviceDiscoverer {
    /**
     * Discover UPnP/media devices on the network.
     *
     * @param timeoutMs Maximum time to spend discovering (milliseconds)
     * @return Flow of discovered devices
     */
    suspend fun discover(timeoutMs: Long): Flow<UpnpDevice>

    /**
     * Get a human-readable name for this discoverer (for logging).
     */
    val name: String
}
