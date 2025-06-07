package com.wobbz.fartloop.core.network

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Modern UPnP control client for sending media commands to devices.
 *
 * UPNP CONTROL IMPLEMENTATION: This replaces the old UpnpControlClient
 * with a modern implementation using proper SOAP requests for actual device control.
 *
 * SOAP PROTOCOL FINDING: UPnP devices require properly formatted SOAP requests:
 * 1. SetAVTransportURI - Sets the media URL to play
 * 2. Play - Starts playback of the set media
 * 3. Proper HTTP headers with SOAPAction and Content-Type
 */
@Singleton
class ModernUpnpControlClient @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val upnpMutex = Mutex()

    /**
     * Sends media URL to a UPnP device for playback using proper SOAP requests.
     *
     * SOAP FINDING: Real UPnP requires SetAVTransportURI + Play commands, not simplified APIs.
     * TIMEOUT FINDING: UPnP SOAP requests can hang, so we add our own timeout.
     * DEVICE VERIFICATION: Check device reachability before attempting cast.
     * CONTROL URL FINDING: Each device has its own specific control endpoint from device description.
     */
    suspend fun pushClip(deviceIp: String, port: Int, controlUrl: String, mediaUrl: String): Boolean = upnpMutex.withLock {
        return try {
            Timber.d("ModernUpnpControl: Sending SOAP requests to $deviceIp: $mediaUrl")

            // REACHABILITY CHECK: Verify device is accessible before UPnP operations
            if (!pingDevice(deviceIp, port)) {
                Timber.w("ModernUpnpControl: Device $deviceIp:$port not reachable, skipping cast")
                return false
            }

            val result = withTimeoutOrNull(10000L) {
                sendUpnpSoapRequests(deviceIp, port, controlUrl, mediaUrl)
            }

            if (result == true) {
                Timber.i("ModernUpnpControl: ✅ Successfully cast to $deviceIp")
                true
            } else {
                Timber.w("ModernUpnpControl: ❌ Cast failed to $deviceIp")
                false
            }
        } catch (e: Exception) {
            Timber.e(e, "ModernUpnpControl: Exception casting to $deviceIp")
            false
        }
    }

    /**
     * DEVICE REACHABILITY FINDING: Before attempting UPnP cast, verify the device
     * is actually reachable on the network to avoid timeout issues.
     *
     * SONOS FINDING: Sonos devices return HTTP 403 for security but still accept UPnP commands
     * GOOGLE CAST FINDING: Chromecast devices return HTTP 404 but are still functional
     */
    private suspend fun pingDevice(deviceIp: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            // KNOWN UPNP PORTS: Skip ping for well-known UPnP device ports that may have security restrictions
            when (port) {
                1400 -> {
                    Timber.d("ModernUpnpControl: Skipping ping for Sonos device $deviceIp:$port (known to return 403)")
                    return@withContext true
                }
                8008, 8009 -> {
                    Timber.d("ModernUpnpControl: Skipping ping for Google Cast device $deviceIp:$port")
                    return@withContext true
                }
            }

            val url = URL("http://$deviceIp:$port/")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 3000
            connection.readTimeout = 3000

            val responseCode = connection.responseCode
            Timber.d("ModernUpnpControl: Device $deviceIp:$port ping result: $responseCode (reachable: ${responseCode in 200..599})")

            // ACCEPT COMMON UPNP RESPONSES: Many UPnP devices return 4xx codes but are still functional
            val reachable = responseCode in 200..599  // Accept any HTTP response (including 403, 404, 501)
            reachable
        } catch (e: Exception) {
            Timber.d("ModernUpnpControl: Device $deviceIp:$port ping failed: ${e.message}")
            false
        }
    }

    /**
     * SOAP PROTOCOL IMPLEMENTATION: Send proper UPnP SOAP requests for media casting.
     *
     * UPNP SOAP FINDING: Real UPnP devices expect:
     * 1. SetAVTransportURI to set the media URL
     * 2. Play to start playback
     * 3. Proper SOAP envelope with correct namespaces and headers
     */
    private suspend fun sendUpnpSoapRequests(deviceIp: String, port: Int, controlUrl: String, mediaUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // Step 1: Send SetAVTransportURI SOAP request
            val setUriSuccess = sendSoapRequest(
                deviceIp = deviceIp,
                port = port,
                controlUrl = controlUrl,
                soapAction = "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI",
                soapBody = createSetAVTransportURIBody(mediaUrl)
            )

            if (!setUriSuccess) {
                Timber.w("ModernUpnpControl: SetAVTransportURI failed for $deviceIp")
                return@withContext false
            }

            Timber.d("ModernUpnpControl: SetAVTransportURI successful for $deviceIp")

            // Step 2: Send Play SOAP request
            val playSuccess = sendSoapRequest(
                deviceIp = deviceIp,
                port = port,
                controlUrl = controlUrl,
                soapAction = "urn:schemas-upnp-org:service:AVTransport:1#Play",
                soapBody = createPlayBody()
            )

            if (playSuccess) {
                Timber.d("ModernUpnpControl: Play command successful for $deviceIp")
                return@withContext true
            } else {
                Timber.w("ModernUpnpControl: Play command failed for $deviceIp")
                return@withContext false
            }

        } catch (e: Exception) {
            Timber.e(e, "ModernUpnpControl: SOAP request exception for $deviceIp")
            return@withContext false
        }
    }

    /**
     * SOAP REQUEST FINDING: Send properly formatted SOAP request to UPnP device.
     * UPnP devices expect specific headers and XML format.
     * CONTROL URL FINDING: Use device-specific control URL instead of hardcoded path.
     */
    private suspend fun sendSoapRequest(deviceIp: String, port: Int, controlUrl: String, soapAction: String, soapBody: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // CONTROL URL CONSTRUCTION: Build full control URL from device IP, port, and specific control path
            val fullControlUrl = if (controlUrl.startsWith("http")) {
                controlUrl
            } else {
                "http://$deviceIp:$port$controlUrl"
            }

            Timber.d("ModernUpnpControl: Using control URL: $fullControlUrl")
            val url = URL(fullControlUrl)
            val connection = url.openConnection() as HttpURLConnection

            // UPNP SOAP HEADERS: Required headers for UPnP SOAP communication
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
            connection.setRequestProperty("SOAPAction", "\"$soapAction\"")
            connection.setRequestProperty("User-Agent", "FartLooper/1.0")
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.doOutput = true

            // Send SOAP envelope
            connection.outputStream.use { outputStream ->
                outputStream.write(soapBody.toByteArray(Charsets.UTF_8))
                outputStream.flush()
            }

            val responseCode = connection.responseCode
            Timber.d("ModernUpnpControl: SOAP response for $soapAction: $responseCode")

            if (responseCode in 200..299) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                Timber.d("ModernUpnpControl: SOAP response body: ${response.take(200)}...")
                return@withContext true
            } else {
                val errorResponse = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "No error details"
                Timber.w("ModernUpnpControl: SOAP error $responseCode: ${errorResponse.take(200)}...")
                return@withContext false
            }

        } catch (e: Exception) {
            Timber.e(e, "ModernUpnpControl: SOAP request failed")
            return@withContext false
        }
    }

    /**
     * SOAP ENVELOPE FINDING: Create SetAVTransportURI SOAP request body.
     * This sets the media URL that the device should play.
     */
    private fun createSetAVTransportURIBody(mediaUrl: String): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
    <s:Body>
        <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
            <InstanceID>0</InstanceID>
            <CurrentURI>$mediaUrl</CurrentURI>
            <CurrentURIMetaData></CurrentURIMetaData>
        </u:SetAVTransportURI>
    </s:Body>
</s:Envelope>"""
    }

    /**
     * SOAP ENVELOPE FINDING: Create Play SOAP request body.
     * This starts playback of the previously set media URL.
     */
    private fun createPlayBody(): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
    <s:Body>
        <u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
            <InstanceID>0</InstanceID>
            <Speed>1</Speed>
        </u:Play>
    </s:Body>
</s:Envelope>"""
    }
}
