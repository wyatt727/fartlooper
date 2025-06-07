package com.wobbz.fartloop.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.Inet4Address
import javax.inject.Inject
import javax.inject.Singleton
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

/**
 * Discovers media devices using mDNS (Multicast DNS).
 * Looks for services like Chromecast, AirPlay, DLNA devices advertised via Bonjour/Zeroconf.
 *
 * Service types searched:
 * - _googlecast._tcp.local (Chromecast)
 * - _airplay._tcp.local (AirPlay devices)
 * - _dlna._tcp.local (DLNA devices)
 * - _raop._tcp.local (Remote Audio Output Protocol)
 */
@Singleton
class MdnsDiscoverer @Inject constructor() : DeviceDiscoverer {

    override val name = "mDNS"

    // Service types to search for
    private val serviceTypes = listOf(
        "_googlecast._tcp.local",  // Chromecast devices
        "_airplay._tcp.local",     // AirPlay devices
        "_dlna._tcp.local",        // DLNA devices
        "_raop._tcp.local",        // Remote Audio Output Protocol (older AirPlay)
        "_services._dns-sd._udp.local" // Service discovery
    )

    override suspend fun discover(timeoutMs: Long): Flow<UpnpDevice> = withContext(Dispatchers.IO) {
        callbackFlow {
            Timber.d("Starting mDNS discovery (timeout: ${timeoutMs}ms)")

            var jmdns: JmDNS? = null
            val serviceListeners = mutableListOf<Pair<String, ServiceListener>>()

            try {
                // Create JmDNS instance
                jmdns = JmDNS.create()

                // Create service listener for each service type
                for (serviceType in serviceTypes) {
                    val listener = object : ServiceListener {
                        override fun serviceAdded(event: ServiceEvent) {
                            Timber.v("mDNS service added: ${event.type} - ${event.name}")
                            // Don't process in serviceAdded - wait for serviceResolved
                        }

                        override fun serviceRemoved(event: ServiceEvent) {
                            // We don't handle removal during discovery
                        }

                        override fun serviceResolved(event: ServiceEvent) {
                            Timber.d("mDNS service resolved: ${event.type} - ${event.name}")
                            processServiceEvent(event)?.let { device ->
                                trySend(device)
                            }
                        }
                    }

                    serviceListeners.add(serviceType to listener)
                    jmdns.addServiceListener(serviceType, listener)

                    Timber.d("Listening for mDNS service type: $serviceType")
                }

                // List existing services as well (in case they were already discovered)
                for (serviceType in serviceTypes) {
                    val existingServices = jmdns.list(serviceType)
                    Timber.d("Found ${existingServices.size} existing $serviceType services")

                    for (serviceInfo in existingServices) {
                        processServiceInfo(serviceInfo, serviceType)?.let { device ->
                            trySend(device)
                        }
                    }
                }

            } catch (e: Exception) {
                Timber.e(e, "Error starting mDNS discovery")
            }

            // Clean up when flow is cancelled
            awaitClose {
                Timber.d("mDNS discovery completed, cleaning up")
                try {
                    serviceListeners.forEach { (serviceType, listener) ->
                        jmdns?.removeServiceListener(serviceType, listener)
                    }
                    jmdns?.close()
                } catch (e: Exception) {
                    Timber.w(e, "Error cleaning up mDNS")
                }
            }
        }
    }

    /**
     * Process a resolved mDNS service event and convert to UpnpDevice if applicable.
     */
    private fun processServiceEvent(event: ServiceEvent): UpnpDevice? {
        return processServiceInfo(event.info, event.type)
    }

    /**
     * Process service info and create UpnpDevice.
     * Only creates devices for services that support media control.
     */
    private fun processServiceInfo(serviceInfo: javax.jmdns.ServiceInfo?, serviceType: String): UpnpDevice? {
        if (serviceInfo == null) return null

        try {
            val name = serviceInfo.name
            val addresses = serviceInfo.inet4Addresses
            val port = serviceInfo.port

            if (addresses.isEmpty()) {
                Timber.v("mDNS service $name has no IPv4 addresses, skipping")
                return null
            }

            val ipAddress = addresses.first().hostAddress

            // Determine device type and control capabilities based on service type
            val (deviceType, controlUrl, manufacturerName) = when {
                serviceType.contains("googlecast") -> {
                    // Chromecast devices - use standard Chromecast control
                    Triple("Chromecast", "/apps", "Google")
                }
                serviceType.contains("airplay") -> {
                    // AirPlay devices - Note: AirPlay is not UPnP but we can represent it
                    Triple("AirPlay", "/airplay", serviceInfo.getPropertyString("am") ?: "Apple")
                }
                serviceType.contains("dlna") -> {
                    // DLNA devices advertised via mDNS
                    val deviceModel = serviceInfo.getPropertyString("model") ?: "DLNA Device"
                    Triple(deviceModel, "/MediaRenderer/AVTransport/Control", "Unknown")
                }
                serviceType.contains("raop") -> {
                    // Remote Audio Output Protocol (older AirPlay)
                    Triple("RAOP", "/raop", "Apple")
                }
                else -> {
                    Timber.v("Unknown mDNS service type: $serviceType")
                    return null
                }
            }

            // Extract additional metadata from TXT records
            val metadata = mutableMapOf<String, String>()
            metadata["serviceType"] = serviceType
            metadata["txtRecords"] = serviceInfo.propertyNames.joinToString(",")

            // Add specific properties based on service type
            when {
                serviceType.contains("googlecast") -> {
                    serviceInfo.getPropertyString("fn")?.let { metadata["friendlyName"] = it }
                    serviceInfo.getPropertyString("md")?.let { metadata["model"] = it }
                    serviceInfo.getPropertyString("id")?.let { metadata["deviceId"] = it }
                }
                serviceType.contains("airplay") -> {
                    serviceInfo.getPropertyString("am")?.let { metadata["audioModel"] = it }
                    serviceInfo.getPropertyString("cn")?.let { metadata["computerName"] = it }
                    serviceInfo.getPropertyString("ek")?.let { metadata["encryptionKey"] = it }
                }
            }

            val device = UpnpDevice(
                friendlyName = serviceInfo.getPropertyString("fn") ?: name,
                ipAddress = ipAddress,
                port = port,
                controlUrl = controlUrl,
                deviceType = deviceType,
                manufacturer = manufacturerName,
                udn = null, // mDNS devices typically don't have UDNs
                discoveryMethod = "mDNS",
                metadata = metadata
            )

            Timber.i("mDNS discovered: $device")
            return device

        } catch (e: Exception) {
            Timber.w(e, "Error processing mDNS service: $serviceInfo")
            return null
        }
    }
}
