package com.wobbz.fartloop.core.network

import kotlinx.coroutines.flow.Flow

/**
 * Interface for device discovery implementations.
 *
 * DEVICE DISCOVERY ARCHITECTURE FINDING: Each discovery method (UPnP, mDNS, port scan)
 * implements this interface to provide a unified Flow-based API for device discovery.
 * This enables parallel discovery execution and easy addition of new discovery methods.
 *
 * DISCOVERY TIMEOUT FINDING: Timeout parameter allows per-method optimization.
 * UPnP/SSDP typically responds quickly (200-500ms) while mDNS can take longer (500-1000ms).
 * Port scanning may need extended timeouts (1000-3000ms) for comprehensive coverage.
 */
interface DeviceDiscoverer {

    /**
     * Human-readable name for this discovery method.
     * Used for logging and debugging purposes.
     */
    val name: String

    /**
     * Discover devices using this discovery method.
     *
     * DISCOVERY FLOW FINDING: Returns a Flow to enable real-time device discovery feedback.
     * Devices are emitted as soon as they're discovered rather than waiting for completion.
     * This provides better user experience with progressive device list updates.
     *
     * @param timeout Maximum time to spend discovering devices in milliseconds
     * @return Flow of discovered UPnP/DLNA devices
     */
    suspend fun discover(timeout: Long): Flow<UpnpDevice>
}
