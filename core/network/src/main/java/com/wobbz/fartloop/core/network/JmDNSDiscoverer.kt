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

                        // Convert jMDNS service info to UpnpDevice
                        val device = UpnpDevice(
                            friendlyName = info.name ?: "Unknown mDNS Device",
                            ipAddress = info.hostAddresses?.firstOrNull() ?: "unknown",
                            port = info.port,
                            controlUrl = "/",
                            deviceType = info.type ?: "Unknown",
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
}
