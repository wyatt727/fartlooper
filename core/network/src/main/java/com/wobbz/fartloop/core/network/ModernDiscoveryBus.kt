package com.wobbz.fartloop.core.network

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Modern discovery bus combining SSDP, jMDNS, and port scanning discoverers.
 *
 * DISCOVERY BUS IMPLEMENTATION: This replaces the old DiscoveryBus with
 * a modern implementation using manual SSDP, jMDNS, and port scanning for comprehensive coverage.
 * UPnPCast library was replaced with manual SSDP implementation for better reliability.
 */
@Singleton
class ModernDiscoveryBus @Inject constructor(
    private val ssdpDiscoverer: SsdpDiscoverer,
    private val mdnsDiscoverer: JmDNSDiscoverer,
    private val portScanDiscoverer: PortScanDiscoverer
) {

    /**
     * Discover all devices using multiple discovery methods.
     *
     * DISCOVERY STRATEGY: Runs SSDP, mDNS, and port scan discovery in parallel,
     * merging results into a single flow for comprehensive device coverage.
     * SSDP is most important for UPnP devices like Sonos and Chromecast.
     */
    suspend fun discoverAll(timeout: Long): Flow<UpnpDevice> {
        Timber.d("ModernDiscoveryBus: Starting parallel discovery (timeout: ${timeout}ms)")

        return merge(
            ssdpDiscoverer.discover(timeout),
            mdnsDiscoverer.discover(timeout),
            portScanDiscoverer.discover(timeout)
        )
    }
}
