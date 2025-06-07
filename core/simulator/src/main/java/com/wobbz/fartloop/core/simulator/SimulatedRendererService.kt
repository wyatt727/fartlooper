package com.wobbz.fartloop.core.simulator

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import fi.iki.elonen.NanoHTTPD
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SimulatedRendererService provides a fake UPnP media renderer for testing.
 *
 * This service creates a local HTTP server that mimics a real UPnP/DLNA device
 * without requiring actual hardware. Essential for:
 * - Unit and integration testing
 * - Development when no physical devices available
 * - CI/CD pipeline testing
 * - Debugging blast operations
 *
 * Key behaviors implemented:
 * - Serves UPnP device description XML at /description.xml
 * - Responds to SOAP SetAVTransportURI and Play actions
 * - Logs all incoming requests for debugging
 * - Always returns success responses (no actual media playback)
 * - Runs on fixed port 1901 to avoid conflicts with real HTTP server
 */
@AndroidEntryPoint
class SimulatedRendererService : Service() {

    @Inject
    lateinit var simulatedRenderer: SimulatedRenderer

    override fun onCreate() {
        super.onCreate()
        Timber.d("SimulatedRendererService created")

        try {
            simulatedRenderer.startServer()
            Timber.i("Simulated UPnP renderer started on ${simulatedRenderer.serverUrl}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start simulated renderer")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.d("SimulatedRendererService destroyed")

        try {
            simulatedRenderer.stopServer()
        } catch (e: Exception) {
            Timber.w(e, "Error stopping simulated renderer")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        /**
         * Start the simulated renderer service.
         */
        fun start(context: Context) {
            val intent = Intent(context, SimulatedRendererService::class.java)
            context.startService(intent)
        }

        /**
         * Stop the simulated renderer service.
         */
        fun stop(context: Context) {
            val intent = Intent(context, SimulatedRendererService::class.java)
            context.stopService(intent)
        }
    }
}

/**
 * Core simulated UPnP renderer implementation using NanoHTTPD.
 *
 * Architecture notes:
 * - Uses fixed port 1901 to avoid conflicts with main HTTP server (8080+)
 * - Device UUID is deterministic for consistent discovery testing
 * - AVTransport service minimal implementation covers SetAVTransportURI and Play
 * - All SOAP responses are hardcoded success XML for predictable testing
 */
@Singleton
class SimulatedRenderer @Inject constructor(
    @ApplicationContext private val context: Context
) : NanoHTTPD("127.0.0.1", 1901) {

    val serverUrl: String get() = "http://127.0.0.1:1901"

    // Track simulated state for logging/debugging
    private var currentMediaUri: String? = null
    private var playState: String = "STOPPED"

    fun startServer() {
        start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        Timber.i("Simulated renderer server started on $serverUrl")
    }

    fun stopServer() {
        stop()
        Timber.i("Simulated renderer server stopped")
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        Timber.d("Simulated renderer request: $method $uri")

        return when {
            uri == "/description.xml" -> serveDeviceDescription()
            uri.contains("/control") -> handleControlRequest(session)
            else -> {
                Timber.w("Unknown request to simulated renderer: $uri")
                newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
            }
        }
    }

    /**
     * Serve UPnP device description XML.
     * Contains minimal AVTransport service definition for blast compatibility.
     */
    private fun serveDeviceDescription(): Response {
        val deviceXml = """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
    <specVersion>
        <major>1</major>
        <minor>0</minor>
    </specVersion>
    <device>
        <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
        <friendlyName>Fart-Looper Simulator</friendlyName>
        <manufacturer>Wobbz</manufacturer>
        <manufacturerURL>https://github.com/wobbz/fart-looper</manufacturerURL>
        <modelDescription>Simulated UPnP Media Renderer for Testing</modelDescription>
        <modelName>SimulatedRenderer</modelName>
        <modelNumber>1.0</modelNumber>
        <modelURL>https://github.com/wobbz/fart-looper</modelURL>
        <serialNumber>SIM-001</serialNumber>
        <UDN>uuid:12345678-1234-1234-1234-123456789abc</UDN>
        <serviceList>
            <service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
                <controlURL>/control/AVTransport</controlURL>
                <eventSubURL>/event/AVTransport</eventSubURL>
                <SCPDURL>/scpd/AVTransport</SCPDURL>
            </service>
            <service>
                <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
                <controlURL>/control/RenderingControl</controlURL>
                <eventSubURL>/event/RenderingControl</eventSubURL>
                <SCPDURL>/scpd/RenderingControl</SCPDURL>
            </service>
        </serviceList>
    </device>
</root>"""

        return newFixedLengthResponse(Response.Status.OK, "text/xml", deviceXml)
    }

    /**
     * Handle SOAP control requests.
     * Parses basic SOAP actions and returns appropriate XML responses.
     */
    private fun handleControlRequest(session: IHTTPSession): Response {
        // Read the SOAP request body
        val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
        val soapBody = if (contentLength > 0) {
            val buffer = ByteArray(contentLength)
            session.inputStream.read(buffer)
            String(buffer)
        } else {
            ""
        }

        Timber.d("SOAP request body: $soapBody")

        // Parse SOAP action from header or body
        val soapAction = session.headers["soapaction"] ?: extractSoapActionFromBody(soapBody)

        return when {
            soapAction.contains("SetAVTransportURI") -> handleSetAVTransportURI(soapBody)
            soapAction.contains("Play") -> handlePlay(soapBody)
            soapAction.contains("Stop") -> handleStop(soapBody)
            soapAction.contains("GetTransportInfo") -> handleGetTransportInfo()
            else -> {
                Timber.w("Unknown SOAP action: $soapAction")
                createSoapErrorResponse("401", "Invalid Action")
            }
        }
    }

    /**
     * Extract SOAP action from request body if not in header.
     */
    private fun extractSoapActionFromBody(body: String): String {
        // Simple regex to find SOAP action in body
        val actionRegex = """<u:(\w+)""".toRegex()
        val match = actionRegex.find(body)
        return match?.groupValues?.get(1) ?: "unknown"
    }

    /**
     * Handle SetAVTransportURI SOAP action.
     * Simulates setting the media URL for playback.
     */
    private fun handleSetAVTransportURI(soapBody: String): Response {
        // Extract CurrentURI from SOAP body (simple regex approach)
        val uriRegex = """<CurrentURI>(.*?)</CurrentURI>""".toRegex()
        val match = uriRegex.find(soapBody)
        currentMediaUri = match?.groupValues?.get(1)

        Timber.i("Simulated SetAVTransportURI: $currentMediaUri")

        val responseXml = """<?xml version="1.0"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
    <s:Body>
        <u:SetAVTransportURIResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
        </u:SetAVTransportURIResponse>
    </s:Body>
</s:Envelope>"""

        return newFixedLengthResponse(Response.Status.OK, "text/xml", responseXml)
    }

    /**
     * Handle Play SOAP action.
     * Simulates starting media playback.
     */
    private fun handlePlay(soapBody: String): Response {
        playState = "PLAYING"
        Timber.i("Simulated Play: $currentMediaUri (state: $playState)")

        val responseXml = """<?xml version="1.0"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
    <s:Body>
        <u:PlayResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
        </u:PlayResponse>
    </s:Body>
</s:Envelope>"""

        return newFixedLengthResponse(Response.Status.OK, "text/xml", responseXml)
    }

    /**
     * Handle Stop SOAP action.
     */
    private fun handleStop(soapBody: String): Response {
        playState = "STOPPED"
        Timber.i("Simulated Stop (state: $playState)")

        val responseXml = """<?xml version="1.0"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
    <s:Body>
        <u:StopResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
        </u:StopResponse>
    </s:Body>
</s:Envelope>"""

        return newFixedLengthResponse(Response.Status.OK, "text/xml", responseXml)
    }

    /**
     * Handle GetTransportInfo SOAP action.
     */
    private fun handleGetTransportInfo(): Response {
        val responseXml = """<?xml version="1.0"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
    <s:Body>
        <u:GetTransportInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
            <CurrentTransportState>$playState</CurrentTransportState>
            <CurrentTransportStatus>OK</CurrentTransportStatus>
            <CurrentSpeed>1</CurrentSpeed>
        </u:GetTransportInfoResponse>
    </s:Body>
</s:Envelope>"""

        return newFixedLengthResponse(Response.Status.OK, "text/xml", responseXml)
    }

    /**
     * Create SOAP error response.
     */
    private fun createSoapErrorResponse(errorCode: String, errorDescription: String): Response {
        val errorXml = """<?xml version="1.0"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
    <s:Body>
        <s:Fault>
            <faultcode>s:Client</faultcode>
            <faultstring>UPnPError</faultstring>
            <detail>
                <UPnPError xmlns="urn:schemas-upnp-org:control-1-0">
                    <errorCode>$errorCode</errorCode>
                    <errorDescription>$errorDescription</errorDescription>
                </UPnPError>
            </detail>
        </s:Fault>
    </s:Body>
</s:Envelope>"""

        return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/xml", errorXml)
    }
}
