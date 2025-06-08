package com.wobbz.fartloop.core.network

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener
import java.net.InetAddress

/**
 * mDNS device discoverer using jMDNS library.
 *
 * MDNS DISCOVERY IMPLEMENTATION: This replaces the old MdnsDiscoverer
 * with a modern implementation using jMDNS for actual service discovery.
 */
@Singleton
class JmDNSDiscoverer @Inject constructor() : DeviceDiscoverer {

    override val name: String = "jMDNS"

    private val serviceTypes = listOf(
        "_googlecast._tcp.local.",
        "_airplay._tcp.local.",
        "_raop._tcp.local.",
        "_dlna._tcp.local."
    )

    override suspend fun discover(timeout: Long): Flow<UpnpDevice> = callbackFlow {
        Timber.d("jMDNS Discovery: Starting mDNS discovery (timeout: ${timeout}ms)")

        try {
            val localAddress = InetAddress.getLocalHost()
            val jmdns = JmDNS.create(localAddress)

            val serviceListener = object : ServiceListener {
                override fun serviceAdded(event: ServiceEvent) {
                    Timber.d("jMDNS Discovery: Service added - ${event.info?.name}")
                }

                override fun serviceRemoved(event: ServiceEvent) {
                    Timber.d("jMDNS Discovery: Service removed - ${event.info?.name}")
                }

                override fun serviceResolved(event: ServiceEvent) {
                    val info = event.info
                    if (info != null) {
                        Timber.d("jMDNS Discovery: Service resolved - ${info.name} at ${info.hostAddresses?.firstOrNull()}")

                        // Determine device type and appropriate control URL from service type
                        val deviceType = mapServiceTypeToDeviceType(info.type)
                        val controlUrl = getFallbackControlUrl(info.port, deviceType)

                        // Convert jMDNS service info to UpnpDevice
                        val device = UpnpDevice(
                            friendlyName = info.name ?: "Unknown mDNS Device",
                            ipAddress = info.hostAddresses?.firstOrNull() ?: "unknown",
                            port = info.port,
                            controlUrl = controlUrl,
                            deviceType = deviceType,
                            discoveryMethod = "mDNS"
                        )

                        trySend(device)
                    }
                }
            }

            // Add listeners for all service types
            serviceTypes.forEach { serviceType ->
                jmdns.addServiceListener(serviceType, serviceListener)
            }

            // Keep discovery running for the timeout duration
            kotlinx.coroutines.delay(timeout)

            // Cleanup
            jmdns.close()

        } catch (e: Exception) {
            Timber.e(e, "jMDNS Discovery: Error during discovery")
        }

        close()
    }

    /**
     * Map mDNS service type to device type string
     */
    private fun mapServiceTypeToDeviceType(serviceType: String?): String {
        return when {
            serviceType?.contains("airplay", ignoreCase = true) == true -> "AIRPLAY"
            serviceType?.contains("chromecast", ignoreCase = true) == true -> "CHROMECAST"
            serviceType?.contains("upnp", ignoreCase = true) == true -> "UNKNOWN_UPNP"
            serviceType?.contains("http", ignoreCase = true) == true -> "UNKNOWN_UPNP"
            else -> "UNKNOWN_UPNP"
        }
    }

    /**
     * Get fallback control URL based on port and device type
     */
    private fun getFallbackControlUrl(port: Int, deviceType: String): String {
        return when {
            deviceType == "AIRPLAY" -> "/airplay/control"
            deviceType == "CHROMECAST" || port in listOf(8008, 8009) -> "/setup/eureka_info"
            port == 1400 -> "/MediaRenderer/AVTransport/Control" // Sonos
            else -> "/upnp/control/AVTransport1" // Default UPnP
        }
    }
}
