package com.wobbz.fartloop.core.network

/**
 * Device type enumeration for categorizing discovered UPnP/DLNA devices.
 *
 * DEVICE TYPE FINDING: Different device types require different communication approaches:
 * - Sonos: Uses standard UPnP with specific control URLs
 * - Chromecast: Uses Google Cast protocol (different from standard UPnP)
 * - DLNA_RENDERER: Standard DLNA media renderers
 * - UNKNOWN_UPNP: Generic UPnP devices with unknown specific type
 */
enum class DeviceType {
    /** Sonos speakers and sound systems */
    SONOS,

    /** Google Chromecast devices */
    CHROMECAST,

    /** Standard DLNA media renderers */
    DLNA_RENDERER,

    /** Generic UPnP devices of unknown specific type */
    UNKNOWN_UPNP,

    /** Apple AirPlay compatible devices */
    AIRPLAY,

    /** Roku streaming devices */
    ROKU
}
