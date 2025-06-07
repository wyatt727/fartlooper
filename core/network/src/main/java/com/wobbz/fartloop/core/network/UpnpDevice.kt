package com.wobbz.fartloop.core.network

/**
 * Represents a discovered UPnP/DLNA device that can receive media commands.
 * Contains all necessary information to send SOAP control messages.
 */
data class UpnpDevice(
    /** Device friendly name (e.g. "Living Room Sonos") */
    val friendlyName: String,

    /** IP address of the device */
    val ipAddress: String,

    /** Port for HTTP communication */
    val port: Int,

    /** Control URL for AVTransport service (for SetAVTransportURI/Play commands) */
    val controlUrl: String,

    /** Device type/model (e.g. "Sonos PLAY:1", "Chromecast") */
    val deviceType: String,

    /** Manufacturer name */
    val manufacturer: String? = null,

    /** Device UDN (Unique Device Name) if available */
    val udn: String? = null,

    /** Discovery method that found this device */
    val discoveryMethod: String,

    /** Additional metadata */
    val metadata: Map<String, String> = emptyMap()
) {
    /** Full base URL for this device */
    val baseUrl: String get() = "http://$ipAddress:$port"

    /** Full control URL (base + control path) */
    val fullControlUrl: String get() = if (controlUrl.startsWith("http")) {
        controlUrl
    } else {
        "$baseUrl$controlUrl"
    }

    override fun toString(): String {
        return "UpnpDevice(name='$friendlyName', ip=$ipAddress:$port, type='$deviceType', discovery='$discoveryMethod')"
    }
}
