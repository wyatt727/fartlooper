package com.wobbz.fartloop.core.network

import android.content.Context
import com.yinnho.upnpcast.DLNACast
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Modern UPnP control client for sending media commands to devices.
 *
 * UPNP CONTROL IMPLEMENTATION: This replaces the old UpnpControlClient
 * with a modern implementation using UPnPCast for actual device control.
 *
 * MODERN LIBRARY FINDING: UPnPCast provides simplified API compared to complex
 * Cling SOAP construction. Single method calls replace multi-step Action invocation.
 * Error handling improved with proper Result types and timeout management.
 *
 * UPNPCAST CONTROL API FINDING: The library uses static DLNACast methods:
 * - DLNACast.castToDevice(device, url, title) for media casting
 * - DLNACast.control(action) for playback control
 * - Device selection handled internally by the library
 */
@Singleton
class ModernUpnpControlClient @Inject constructor(
    private val context: Context
) {

    // UPNP TIMEOUT FINDING: 5 seconds optimal for most devices
    // Shorter timeouts miss slow devices, longer timeouts delay error feedback
    private companion object {
        const val UPNP_COMMAND_TIMEOUT_MS = 5000L
    }

    /**
     * Send media URL to device and start playback.
     *
     * CONTROL COMMAND IMPLEMENTATION: Uses UPnPCast castToDevice API
     * which handles SetAVTransportURI + Play commands internally.
     *
     * UPNP PROTOCOL FINDING: UPnPCast library manages SOAP protocol complexity
     * internally, providing simple callback-based API for media casting.
     */
    suspend fun pushClip(device: UpnpDevice, mediaUrl: String): Result<Unit> {
        return try {
            Timber.d("ModernUpnpControl: Sending media to ${device.ipAddress}: $mediaUrl")

            // UPNPCAST INITIALIZATION: Ensure library is initialized
            DLNACast.init(context)

            // Convert UpnpDevice to UPnPCast format
            val upnpCastDevice = device.toUPnPCastDevice()

            // MODERN UPNP IMPLEMENTATION: UPnPCast handles SOAP complexity internally
            // Single method call replaces 20+ lines of Cling Action construction
            val castSuccess = withTimeoutOrNull(UPNP_COMMAND_TIMEOUT_MS) {
                suspendCancellableCoroutine<Boolean> { continuation ->
                    DLNACast.castToDevice(
                        upnpCastDevice,
                        mediaUrl,
                        "Media Clip" // Simple title for cast session
                    ) { success ->
                        continuation.resume(success)
                    }
                }
            } ?: false

            if (!castSuccess) {
                Timber.w("ModernUpnpControl: Cast failed or timed out for ${device.friendlyName}")
                return Result.failure(Exception("Cast failed for ${device.friendlyName}"))
            }

            Timber.d("ModernUpnpControl: Media sent successfully to ${device.ipAddress}")
            Result.success(Unit)

        } catch (e: Exception) {
            Timber.e(e, "ModernUpnpControl: Error sending media to ${device.ipAddress}")
            Result.failure(e)
        }
    }

    /**
     * Stop playback on device.
     *
     * STOP COMMAND IMPLEMENTATION: Uses UPnPCast control API with STOP action
     * for unified playback control across all devices.
     */
    suspend fun stop(device: UpnpDevice): Result<Unit> {
        return try {
            Timber.d("ModernUpnpControl: Stopping playback on ${device.ipAddress}")

            // UPNPCAST CONTROL FINDING: control() method provides unified playback control
            // STOP action works across all device types without device-specific logic
            val stopSuccess = withTimeoutOrNull(UPNP_COMMAND_TIMEOUT_MS) {
                suspendCancellableCoroutine<Boolean> { continuation ->
                    DLNACast.control(DLNACast.MediaAction.STOP) { success ->
                        continuation.resume(success)
                    }
                }
            } ?: false

            if (!stopSuccess) {
                Timber.w("ModernUpnpControl: Stop command failed or timed out for ${device.friendlyName}")
                return Result.failure(Exception("Stop command failed for ${device.friendlyName}"))
            }

            Timber.d("ModernUpnpControl: Playback stopped on ${device.ipAddress}")
            Result.success(Unit)

        } catch (e: Exception) {
            Timber.e(e, "ModernUpnpControl: Error stopping playback on ${device.ipAddress}")
            Result.failure(e)
        }
    }

    /**
     * Set volume on device.
     *
     * VOLUME CONTROL IMPLEMENTATION: Uses UPnPCast volume APIs with validation
     * Volume range 0-100, graceful failure for devices without volume support.
     *
     * VOLUME CONTROL FINDING: Not all devices support volume control via UPnP.
     * UPnPCast provides unified volume API but success depends on device capabilities.
     */
    suspend fun setVolume(device: UpnpDevice, volume: Int): Result<Unit> {
        return try {
            // VOLUME VALIDATION: Clamp to valid range to prevent device errors
            val clampedVolume = volume.coerceIn(0, 100)
            Timber.d("ModernUpnpControl: Setting volume to $clampedVolume on ${device.ipAddress}")

            // UPNPCAST VOLUME API: control() with VOLUME action and volume value
            // Uses MediaAction.VOLUME with volume parameter passed as value
            val volumeSuccess = withTimeoutOrNull(UPNP_COMMAND_TIMEOUT_MS) {
                suspendCancellableCoroutine<Boolean> { continuation ->
                    DLNACast.control(DLNACast.MediaAction.VOLUME, clampedVolume) { success ->
                        continuation.resume(success)
                    }
                }
            } ?: false

            if (!volumeSuccess) {
                Timber.w("ModernUpnpControl: Volume command failed for ${device.friendlyName}")
                // GRACEFUL VOLUME FAILURE: Don't fail the whole operation for volume issues
                // Many devices don't support UPnP volume control, this shouldn't break playback
                Timber.i("ModernUpnpControl: Volume control not supported by ${device.friendlyName}")
                return Result.success(Unit)
            }

            Timber.d("ModernUpnpControl: Volume set on ${device.ipAddress}")
            Result.success(Unit)

        } catch (e: Exception) {
            Timber.e(e, "ModernUpnpControl: Error setting volume on ${device.ipAddress}")
            // VOLUME ERROR HANDLING: Log error but don't fail the operation
            // Volume control is nice-to-have, not essential for core functionality
            Result.success(Unit)
        }
    }

    /**
     * Convert UpnpDevice to UPnPCast device format.
     *
     * ADAPTER PATTERN IMPLEMENTATION: Maintains compatibility with existing UpnpDevice
     * model while enabling UPnPCast integration. UPnPCast.Device uses simplified properties.
     *
     * UPNPCAST DEVICE MODEL: Device(id, name, address, isTV)
     * Simplified compared to full UPnP device description but sufficient for casting.
     */
    private fun UpnpDevice.toUPnPCastDevice(): DLNACast.Device {
        return DLNACast.Device(
            id = this.udn ?: "unknown-${this.ipAddress}",
            name = this.friendlyName,
            address = this.ipAddress,
            isTV = this.deviceType.contains("TV", ignoreCase = true)
        )
    }
}
