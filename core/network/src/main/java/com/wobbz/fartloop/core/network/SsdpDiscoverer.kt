package com.wobbz.fartloop.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import org.fourthline.cling.UpnpService
import org.fourthline.cling.model.meta.Device
import org.fourthline.cling.model.meta.Service
import org.fourthline.cling.model.types.ServiceType
import org.fourthline.cling.model.types.UDAServiceType
import org.fourthline.cling.registry.DefaultRegistryListener
import org.fourthline.cling.registry.Registry
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Discovers UPnP devices using SSDP (Simple Service Discovery Protocol).
 * Uses Cling library to perform M-SEARCH broadcasts and listen for responses.
 *
 * Looks specifically for devices with AVTransport service for media control.
 */
@Singleton
class SsdpDiscoverer @Inject constructor(
    private val upnpService: UpnpService
) : DeviceDiscoverer {

    override val name = "SSDP"

    // AVTransport service type - what we're looking for
    private val avTransportServiceType = UDAServiceType("AVTransport", 1)

    override suspend fun discover(timeoutMs: Long): Flow<UpnpDevice> = withContext(Dispatchers.IO) {
        callbackFlow {
            Timber.d("Starting SSDP discovery (timeout: ${timeoutMs}ms)")

            val registryListener = object : DefaultRegistryListener() {
                override fun deviceAdded(registry: Registry, device: Device<*, *, *>) {
                    processDiscoveredDevice(device)?.let { upnpDevice ->
                        trySend(upnpDevice)
                    }
                }

                override fun deviceRemoved(registry: Registry, device: Device<*, *, *>) {
                    // We don't need to handle removal for discovery
                }
            }

            // Add listener to catch devices discovered during our search
            upnpService.registry.addListener(registryListener)

            // Also check for devices already in registry (from previous discoveries)
            val existingDevices = upnpService.registry.devices
            Timber.d("Checking ${existingDevices.size} existing devices in registry")

            for (device in existingDevices) {
                processDiscoveredDevice(device)?.let { upnpDevice ->
                    trySend(upnpDevice)
                }
            }

            // Perform SSDP search for AVTransport devices
            try {
                Timber.d("Sending SSDP M-SEARCH for AVTransport devices")
                upnpService.controlPoint.search(avTransportServiceType)

                // Also search for general MediaRenderer devices
                val mediaRendererType = UDAServiceType("MediaRenderer", 1)
                upnpService.controlPoint.search(mediaRendererType)

                // Search for all UPnP devices as fallback
                upnpService.controlPoint.search()

            } catch (e: Exception) {
                Timber.e(e, "Error performing SSDP search")
            }

            // Clean up when flow is cancelled
            awaitClose {
                Timber.d("SSDP discovery completed, removing listener")
                upnpService.registry.removeListener(registryListener)
            }
        }
    }

    /**
     * Process a discovered device and convert it to our UpnpDevice format.
     * Only returns devices that have AVTransport service.
     */
    private fun processDiscoveredDevice(device: Device<*, *, *>): UpnpDevice? {
        try {
            // Look for AVTransport service
            val avTransportService = findAvTransportService(device)
            if (avTransportService == null) {
                Timber.v("Device ${device.displayString} has no AVTransport service, skipping")
                return null
            }

            // Extract device information
            val details = device.details
            val identity = device.identity

            val friendlyName = details?.friendlyName ?: "Unknown Device"
            val deviceType = details?.deviceType?.displayString ?: "Unknown"
            val manufacturer = details?.manufacturerDetails?.manufacturer ?: "Unknown"

            // Get network address
            val ipAddress = identity.descriptorURL.host
            val port = identity.descriptorURL.port.let { if (it == -1) 80 else it }

            // Get control URL for AVTransport service
            val controlUrl = avTransportService.controlURI.toString()

            val upnpDevice = UpnpDevice(
                friendlyName = friendlyName,
                ipAddress = ipAddress,
                port = port,
                controlUrl = controlUrl,
                deviceType = deviceType,
                manufacturer = manufacturer,
                udn = identity.udn.identifierString,
                discoveryMethod = "SSDP",
                metadata = mapOf(
                    "descriptorUrl" to identity.descriptorURL.toString(),
                    "serviceType" to avTransportService.serviceType.toString(),
                    "serviceId" to avTransportService.serviceId.toString()
                )
            )

            Timber.i("SSDP discovered: $upnpDevice")
            return upnpDevice

        } catch (e: Exception) {
            Timber.w(e, "Error processing SSDP device: ${device.displayString}")
            return null
        }
    }

    /**
     * Find AVTransport service in device or its embedded devices.
     */
    private fun findAvTransportService(device: Device<*, *, *>): Service<*, *, *>? {
        // Check device's own services
        device.findService(avTransportServiceType)?.let { return it }

        // Check embedded devices recursively
        device.embeddedDevices?.forEach { embeddedDevice ->
            findAvTransportService(embeddedDevice)?.let { return it }
        }

        return null
    }
}
