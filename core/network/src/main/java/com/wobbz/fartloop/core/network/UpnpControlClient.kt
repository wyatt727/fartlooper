package com.wobbz.fartloop.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.fourthline.cling.UpnpService
import org.fourthline.cling.controlpoint.ActionCallback
import org.fourthline.cling.model.action.ActionInvocation
import org.fourthline.cling.model.message.UpnpResponse
import org.fourthline.cling.model.meta.RemoteDevice
import org.fourthline.cling.model.meta.RemoteService
import org.fourthline.cling.model.types.ServiceType
import org.fourthline.cling.model.types.UDAServiceType
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Client for sending UPnP control commands to media devices.
 *
 * Handles the SOAP communication for SetAVTransportURI → Play sequence
 * that sends media to UPnP/DLNA devices. Includes proper error handling
 * and timing delays between commands.
 */
@Singleton
class UpnpControlClient @Inject constructor(
    private val upnpService: UpnpService
) {

    // AVTransport service type - standard for media control
    private val avTransportServiceType = UDAServiceType("AVTransport", 1)

    /**
     * Send a media clip to a UPnP device.
     *
     * Executes the standard UPnP sequence:
     * 1. SetAVTransportURI with the media URL
     * 2. Wait 200ms for URI to be set
     * 3. Play the media
     *
     * @param device Target device to send media to
     * @param mediaUrl URL where the device can access the media
     * @return Result indicating success or failure with details
     */
    suspend fun pushClip(device: UpnpDevice, mediaUrl: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            Timber.d("Pushing clip to ${device.friendlyName} ($mediaUrl)")

            // Find the device in Cling's registry
            val clingDevice = findClingDevice(device)
            if (clingDevice == null) {
                val error = "Device not found in UPnP registry: ${device.friendlyName}"
                Timber.w(error)
                return@withContext Result.failure(IllegalStateException(error))
            }

            // Find AVTransport service
            val avTransportService = clingDevice.findService(avTransportServiceType)
            if (avTransportService == null) {
                val error = "AVTransport service not found on device: ${device.friendlyName}"
                Timber.w(error)
                return@withContext Result.failure(IllegalStateException(error))
            }

            // Step 1: SetAVTransportURI
            val setUriResult = setAvTransportUri(avTransportService, mediaUrl)
            if (setUriResult.isFailure) {
                return@withContext setUriResult
            }

            // Step 2: Wait for URI to be processed
            delay(200)

            // Step 3: Play
            val playResult = playMedia(avTransportService)
            if (playResult.isFailure) {
                return@withContext playResult
            }

            val successMessage = "Successfully started playback on ${device.friendlyName}"
            Timber.i(successMessage)
            Result.success(successMessage)

        } catch (e: Exception) {
            val error = "Failed to push clip to ${device.friendlyName}: ${e.message}"
            Timber.e(e, error)
            Result.failure(e)
        }
    }

    /**
     * Stop playback on a UPnP device.
     * Useful for emergency stop or cleanup.
     */
    suspend fun stopPlayback(device: UpnpDevice): Result<String> = withContext(Dispatchers.IO) {
        try {
            Timber.d("Stopping playback on ${device.friendlyName}")

            val clingDevice = findClingDevice(device)
            if (clingDevice == null) {
                val error = "Device not found in UPnP registry: ${device.friendlyName}"
                return@withContext Result.failure(IllegalStateException(error))
            }

            val avTransportService = clingDevice.findService(avTransportServiceType)
            if (avTransportService == null) {
                val error = "AVTransport service not found on device: ${device.friendlyName}"
                return@withContext Result.failure(IllegalStateException(error))
            }

            // Send Stop command
            val stopAction = avTransportService.getAction("Stop")
            if (stopAction == null) {
                val error = "Stop action not available on ${device.friendlyName}"
                return@withContext Result.failure(IllegalStateException(error))
            }

            val actionInvocation = ActionInvocation(stopAction)
            actionInvocation.setInput("InstanceID", 0)

            val response = executeAction(actionInvocation)
            if (response.isSuccess) {
                val successMessage = "Successfully stopped playback on ${device.friendlyName}"
                Timber.i(successMessage)
                Result.success(successMessage)
            } else {
                Result.failure(Exception("Stop command failed: ${response.responseDetails}"))
            }

        } catch (e: Exception) {
            val error = "Failed to stop playback on ${device.friendlyName}: ${e.message}"
            Timber.e(e, error)
            Result.failure(e)
        }
    }

    /**
     * Get current transport state of a device (Playing, Stopped, etc.)
     */
    suspend fun getTransportState(device: UpnpDevice): Result<String> = withContext(Dispatchers.IO) {
        try {
            val clingDevice = findClingDevice(device)
            if (clingDevice == null) {
                return@withContext Result.failure(IllegalStateException("Device not found"))
            }

            val avTransportService = clingDevice.findService(avTransportServiceType)
            if (avTransportService == null) {
                return@withContext Result.failure(IllegalStateException("AVTransport service not found"))
            }

            val getStateAction = avTransportService.getAction("GetTransportInfo")
            if (getStateAction == null) {
                return@withContext Result.failure(IllegalStateException("GetTransportInfo action not available"))
            }

            val actionInvocation = ActionInvocation(getStateAction)
            actionInvocation.setInput("InstanceID", 0)

            val response = executeAction(actionInvocation)
            if (response.isSuccess) {
                val state = actionInvocation.getOutput("CurrentTransportState")?.value?.toString() ?: "UNKNOWN"
                Result.success(state)
            } else {
                Result.failure(Exception("GetTransportState failed: ${response.responseDetails}"))
            }

        } catch (e: Exception) {
            Timber.e(e, "Failed to get transport state for ${device.friendlyName}")
            Result.failure(e)
        }
    }

    /**
     * Execute SetAVTransportURI SOAP action.
     */
    private suspend fun setAvTransportUri(service: RemoteService, mediaUrl: String): Result<String> {
        try {
            val setUriAction = service.getAction("SetAVTransportURI")
            if (setUriAction == null) {
                return Result.failure(IllegalStateException("SetAVTransportURI action not found"))
            }

            val actionInvocation = ActionInvocation(setUriAction)
            actionInvocation.setInput("InstanceID", 0)
            actionInvocation.setInput("CurrentURI", mediaUrl)
            actionInvocation.setInput("CurrentURIMetaData", "")  // No metadata for now

            Timber.d("Executing SetAVTransportURI: $mediaUrl")
            val response = executeAction(actionInvocation)

            return if (response.isSuccess) {
                Timber.d("SetAVTransportURI successful")
                Result.success("URI set successfully")
            } else {
                val error = "SetAVTransportURI failed: ${response.responseDetails}"
                Timber.w(error)
                Result.failure(Exception(error))
            }

        } catch (e: Exception) {
            Timber.e(e, "Exception in SetAVTransportURI")
            return Result.failure(e)
        }
    }

    /**
     * Execute Play SOAP action.
     */
    private suspend fun playMedia(service: RemoteService): Result<String> {
        try {
            val playAction = service.getAction("Play")
            if (playAction == null) {
                return Result.failure(IllegalStateException("Play action not found"))
            }

            val actionInvocation = ActionInvocation(playAction)
            actionInvocation.setInput("InstanceID", 0)
            actionInvocation.setInput("Speed", "1")  // Normal speed

            Timber.d("Executing Play command")
            val response = executeAction(actionInvocation)

            return if (response.isSuccess) {
                Timber.d("Play command successful")
                Result.success("Playback started")
            } else {
                val error = "Play command failed: ${response.responseDetails}"
                Timber.w(error)
                Result.failure(Exception(error))
            }

        } catch (e: Exception) {
            Timber.e(e, "Exception in Play command")
            return Result.failure(e)
        }
    }

    /**
     * Execute a UPnP action and wait for response.
     * Converts callback-based Cling API to suspend function.
     */
    private suspend fun executeAction(actionInvocation: ActionInvocation): ActionResponse =
        suspendCoroutine { continuation ->
            val callback = object : ActionCallback(actionInvocation) {
                override fun success(invocation: ActionInvocation?) {
                    continuation.resume(ActionResponse(true, "Success", null))
                }

                override fun failure(
                    invocation: ActionInvocation?,
                    operation: UpnpResponse?,
                    defaultMsg: String?
                ) {
                    val errorDetails = buildString {
                        append("SOAP Error: ")
                        append(defaultMsg ?: "Unknown error")
                        operation?.let { resp ->
                            append(" (HTTP ${resp.responseCode})")
                            resp.responseDetails?.let { details ->
                                append(" - $details")
                            }
                        }
                    }
                    continuation.resume(ActionResponse(false, errorDetails, operation))
                }
            }

            upnpService.controlPoint.execute(callback)
        }

    /**
     * Find a Cling device that matches our UpnpDevice.
     * Uses IP and port for matching.
     */
    private fun findClingDevice(device: UpnpDevice): RemoteDevice? {
        val registry = upnpService.registry

        return registry.remoteDevices.find { clingDevice ->
            val deviceUrl = clingDevice.identity.descriptorURL
            deviceUrl.host == device.ipAddress &&
            (deviceUrl.port == device.port || (deviceUrl.port == -1 && device.port == 80))
        }
    }
}

/**
 * Response from a UPnP action execution.
 */
private data class ActionResponse(
    val isSuccess: Boolean,
    val responseDetails: String,
    val upnpResponse: UpnpResponse?
)
