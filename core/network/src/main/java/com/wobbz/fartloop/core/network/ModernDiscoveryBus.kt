package com.wobbz.fartloop.core.network

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Modern discovery bus combining UPnPCast and jMDNS discoverers.
 *
 * DISCOVERY BUS IMPLEMENTATION: This replaces the old DiscoveryBus with
 * a modern implementation using UPnPCast and jMDNS libraries.
 */
@Singleton
class ModernDiscoveryBus @Inject constructor(
    private val upnpDiscoverer: UPnPCastDiscoverer,
    private val mdnsDiscoverer: JmDNSDiscoverer
) {

    /**
     * Discover all devices using multiple discovery methods.
     *
     * DISCOVERY STRATEGY: Runs UPnP and mDNS discovery in parallel,
     * merging results into a single flow for comprehensive device coverage.
     */
    suspend fun discoverAll(timeout: Long): Flow<UpnpDevice> {
        Timber.d("ModernDiscoveryBus: Starting parallel discovery (timeout: ${timeout}ms)")

        return merge(
            upnpDiscoverer.discover(timeout),
            mdnsDiscoverer.discover(timeout)
        )
    }
}
