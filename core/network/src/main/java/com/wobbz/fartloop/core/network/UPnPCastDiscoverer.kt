package com.wobbz.fartloop.core.network

import android.content.Context
import com.yinnho.upnpcast.DLNACast
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UPnP device discoverer using modern UPnPCast library.
 *
 * UPNP DISCOVERY IMPLEMENTATION: This replaces the old Cling-based SsdpDiscoverer
 * with a modern implementation using UPnPCast for actual device discovery.
 *
 * UPNPCAST API FINDING: The library uses DLNACast object with static methods.
 * Discovery API: DLNACast.search(timeout) { devices -> callback }
 * Device objects are returned as DLNACast.Device with simplified properties.
 */
@Singleton
class UPnPCastDiscoverer @Inject constructor(
    private val context: Context
) : DeviceDiscoverer {

    override val name: String = "UPnPCast"

    override suspend fun discover(timeout: Long): Flow<UpnpDevice> = callbackFlow {
        Timber.d("UPnPCast Discovery: Starting device discovery (timeout: ${timeout}ms)")

        try {
            // UPNPCAST INITIALIZATION FINDING: Library requires context initialization
            // Must be called before any discovery operations
            DLNACast.init(context)

            // UPNPCAST DISCOVERY API FINDING: search() method provides simplified discovery
            // Returns devices via callback, supports timeout parameter for discovery duration
            DLNACast.search(timeout) { devices ->
                Timber.d("UPnPCast Discovery: Found ${devices.size} devices")

                devices.forEach { device ->
                    Timber.d("UPnPCast Discovery: Processing device - ${device.name} at ${device.address}")

                    // UPNP DEVICE FILTERING: Focus on media renderer devices
                    // UPnPCast automatically filters for DLNA-compatible devices
                    // isTV property helps identify primary display devices vs audio-only
                    val upnpDevice = UpnpDevice(
                        friendlyName = device.name,
                        ipAddress = device.address,
                        port = 0, // UPnPCast handles port internally
                        controlUrl = "/", // UPnPCast manages control URLs
                        deviceType = if (device.isTV) "MediaRenderer-TV" else "MediaRenderer",
                        manufacturer = null, // Not exposed by UPnPCast API
                        udn = device.id,
                        discoveryMethod = "UPnPCast"
                    )

                    trySend(upnpDevice)
                }
            }

            // DISCOVERY TIMEOUT FINDING: UPnPCast handles timeout internally
            // Discovery completes automatically after specified timeout duration
            // No need for manual delay or cleanup

            Timber.d("UPnPCast Discovery: Discovery setup completed")

        } catch (e: Exception) {
            Timber.e(e, "UPnPCast Discovery: Error during discovery setup")
        }

        close()
    }
}
