package com.wobbz.fartloop.core.media

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.FileInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HTTP server for serving media files to UPnP devices.
 *
 * Features:
 * - Auto-selects free port starting from 8080
 * - Serves local files from cache (/media/current.mp3)
 * - Proxies remote streams (/media/stream)
 * - Hot-swap support - can change media while server is running
 * - Thread-safe and coroutine-friendly
 */
@Singleton
class HttpServerManager @Inject constructor(
    private val context: Context,
    private val storageUtil: StorageUtil
) : NanoHTTPD(0) {  // Auto-select port starting from 8080

    // Cache directory for local files
    private val cacheDir = context.cacheDir.resolve("audio").apply { mkdirs() }
    private val currentFile = cacheDir.resolve("current.mp3")

    // Current remote URL being proxied (if any)
    @Volatile
    private var remoteProxyUrl: String? = null

    // Server state
    @Volatile
    private var isServerRunning = false

    /**
     * Get the base URL for this server.
     * Uses local IP address to be accessible from other devices on network.
     */
    val baseUrl: String
        get() {
            if (!isServerRunning) {
                throw IllegalStateException("Server is not running - cannot get base URL")
            }

            // Get local IP address (Wi-Fi interface preferred)
            val localAddress = getLocalIpAddress()
            return "http://$localAddress:$listeningPort"
        }

    /**
     * Start the HTTP server.
     * Auto-selects the first available port starting from 8080.
     */
    suspend fun startServer(): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (isServerRunning) {
                Timber.w("Server already running on port $listeningPort")
                return@withContext Result.success(baseUrl)
            }

            // Try to start on auto-selected port
            start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            isServerRunning = true

            val url = baseUrl
            Timber.i("HTTP server started successfully on $url")
            Result.success(url)
        } catch (e: IOException) {
            Timber.e(e, "Failed to start HTTP server")
            isServerRunning = false
            Result.failure(e)
        }
    }

    /**
     * Stop the HTTP server.
     */
    suspend fun stopServer() = withContext(Dispatchers.IO) {
        try {
            if (isServerRunning) {
                stop()
                isServerRunning = false
                remoteProxyUrl = null
                Timber.i("HTTP server stopped")
            }
        } catch (e: Exception) {
            Timber.e(e, "Error stopping HTTP server")
        }
    }

    /**
     * Set a remote URL to proxy through /media/stream endpoint.
     * This allows hot-swapping between local files and remote streams.
     */
    fun setRemoteProxy(url: String?) {
        remoteProxyUrl = url
        Timber.d("Remote proxy URL set to: $url")
    }

    /**
     * Get the appropriate media URL for UPnP devices to access.
     * - If local file exists: returns /media/current.mp3
     * - If remote proxy set: returns /media/stream
     * - Otherwise: returns null
     */
    fun getMediaUrl(): String? {
        if (!isServerRunning) return null

        return when (val mediaSource = storageUtil.getCurrentMediaSource()) {
            is MediaSource.Local -> "$baseUrl/media/current.mp3"
            is MediaSource.Remote -> {
                setRemoteProxy(mediaSource.url)
                "$baseUrl/media/stream"
            }
            null -> null
        }
    }

    /**
     * NanoHTTPD serve method - handles all incoming HTTP requests.
     */
    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        Timber.d("HTTP request: ${session.method} $uri")

        return when (uri) {
            "/media/current.mp3" -> serveLocalFile()
            "/media/stream" -> serveRemoteStream()
            "/debug" -> serveDebugInfo()
            "/health" -> serveHealthCheck()
            "/reset-default" -> serveResetDefault()
            "/" -> serveInfo()
            else -> serve404()
        }
    }

    /**
     * Serve the current local media file.
     */
    private fun serveLocalFile(): Response {
        return try {
            if (!currentFile.exists()) {
                Timber.w("Current media file does not exist: ${currentFile.absolutePath}")
                return newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    "text/plain",
                    "No media file available"
                )
            }

            val inputStream = FileInputStream(currentFile)
            val response = newChunkedResponse(
                Response.Status.OK,
                "audio/mpeg",
                inputStream
            )

            // Add headers for better compatibility
            response.addHeader("Accept-Ranges", "bytes")
            response.addHeader("Content-Length", currentFile.length().toString())
            response.addHeader("Cache-Control", "no-cache")

            Timber.d("Serving local file: ${currentFile.name} (${currentFile.length()} bytes)")
            response
        } catch (e: Exception) {
            Timber.e(e, "Error serving local file")
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain",
                "Error serving media file: ${e.message}"
            )
        }
    }

    /**
     * Proxy a remote stream through the server.
     */
    private fun serveRemoteStream(): Response {
        val proxyUrl = remoteProxyUrl
        if (proxyUrl == null) {
            Timber.w("No remote proxy URL set for /media/stream")
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "text/plain",
                "No remote stream configured"
            )
        }

        return try {
            val url = URL(proxyUrl)
            val connection = url.openConnection()
            connection.connectTimeout = 10000
            connection.readTimeout = 30000

            val inputStream = connection.getInputStream()
            val contentType = connection.contentType ?: "audio/mpeg"

            val response = newChunkedResponse(
                Response.Status.OK,
                contentType,
                inputStream
            )

            // Forward relevant headers
            response.addHeader("Cache-Control", "no-cache")

            Timber.d("Proxying remote stream: $proxyUrl")
            response
        } catch (e: Exception) {
            Timber.e(e, "Error proxying remote stream: $proxyUrl")
            newFixedLengthResponse(
                Response.Status.BAD_GATEWAY,
                "text/plain",
                "Error accessing remote stream: ${e.message}"
            )
        }
    }

    /**
     * Serve basic server info at root path.
     */
    private fun serveInfo(): Response {
        val info = buildString {
            appendLine("Fart-Looper HTTP Server")
            appendLine("======================")
            appendLine("Status: Running")
            appendLine("Port: $listeningPort")
            appendLine("Base URL: $baseUrl")
            appendLine()
            appendLine("Endpoints:")
            appendLine("  /media/current.mp3 - Serve local media file")
            appendLine("  /media/stream      - Proxy remote stream")
            appendLine()
            appendLine("Current media: ${storageUtil.getCurrentMediaSource()}")
            appendLine("Cache stats: ${storageUtil.getCacheStats()}")
        }

        return newFixedLengthResponse(
            Response.Status.OK,
            "text/plain",
            info
        )
    }

    /**
     * Serve 404 for unknown paths.
     */
    private fun serve404(): Response {
        return newFixedLengthResponse(
            Response.Status.NOT_FOUND,
            "text/plain",
            "404 - Path not found"
        )
    }

    /**
     * Get the local IP address of this device.
     * Prefers Wi-Fi interface over others.
     */
    private fun getLocalIpAddress(): String {
        return try {
            // Try to get a reasonable local address
            // This is a simplified approach - in production might want more sophisticated network interface detection
            val localhost = InetAddress.getLocalHost()
            if (localhost.hostAddress != "127.0.0.1") {
                localhost.hostAddress
            } else {
                // Fallback - iterate through network interfaces to find a good one
                java.net.NetworkInterface.getNetworkInterfaces().asSequence()
                    .flatMap { it.inetAddresses.asSequence() }
                    .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress && it is java.net.Inet4Address }
                    ?.hostAddress ?: "127.0.0.1"
            }
        } catch (e: Exception) {
            Timber.w(e, "Could not determine local IP address, using localhost")
            "127.0.0.1"
        }
    }

    /**
     * Serve comprehensive debug information for development.
     * DEVELOPMENT ENDPOINT: Shows current server state, discovered devices, and metrics
     * Useful for debugging network issues and performance analysis.
     */
    private fun serveDebugInfo(): Response {
        val debugInfo = buildString {
            appendLine("=== Fart-Looper Debug Information ===")
            appendLine("Generated: ${java.time.LocalDateTime.now()}")
            appendLine()

            appendLine("=== HTTP Server Status ===")
            appendLine("Running: $isServerRunning")
            appendLine("Port: $listeningPort")
            appendLine("Base URL: ${if (isServerRunning) baseUrl else "Not Available"}")
            appendLine("Remote Proxy URL: ${remoteProxyUrl ?: "None"}")
            appendLine()

            appendLine("=== Media Information ===")
            appendLine("Current Media Source: ${storageUtil.getCurrentMediaSource()}")
            appendLine("Local File Exists: ${currentFile.exists()}")
            if (currentFile.exists()) {
                appendLine("Local File Size: ${currentFile.length()} bytes")
                appendLine("Local File Path: ${currentFile.absolutePath}")
            }
            appendLine("Cache Directory: ${cacheDir.absolutePath}")
            appendLine("Cache Stats: ${storageUtil.getCacheStats()}")
            appendLine()

            appendLine("=== Asset Information ===")
            appendLine("Default Asset: fart.mp3 (loaded from app/src/main/assets/)")
            appendLine("Asset Loading: Automatic on first run, provides default clip out-of-box")
            appendLine()

            appendLine("=== Network Information ===")
            appendLine("Local IP Address: ${getLocalIpAddress()}")
            appendLine("Available Network Interfaces:")
            try {
                java.net.NetworkInterface.getNetworkInterfaces().asSequence()
                    .filter { it.isUp && !it.isLoopback }
                    .forEach { iface ->
                        appendLine("  - ${iface.displayName}: ${iface.inetAddresses.asSequence().joinToString()}")
                    }
            } catch (e: Exception) {
                appendLine("  Error listing interfaces: ${e.message}")
            }
            appendLine()

            appendLine("=== System Information ===")
            appendLine("JVM Version: ${System.getProperty("java.version")}")
            appendLine("OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")}")
            appendLine("Available Processors: ${Runtime.getRuntime().availableProcessors()}")
            appendLine("Free Memory: ${Runtime.getRuntime().freeMemory() / 1024 / 1024} MB")
            appendLine("Total Memory: ${Runtime.getRuntime().totalMemory() / 1024 / 1024} MB")
            appendLine()

            appendLine("=== Recent Activity ===")
            appendLine("Server Start Time: ${if (isServerRunning) "Running since startup" else "Not running"}")
            appendLine("Last Media Change: ${storageUtil.getLastModified()}")
            appendLine()

            appendLine("=== Debug Endpoints ===")
            appendLine("GET /debug - This debug information")
            appendLine("GET /health - Simple health check")
            appendLine("GET /media/current.mp3 - Current media file")
            appendLine("GET /media/stream - Remote stream proxy")
            appendLine("GET /reset-default - Reset to default fart.mp3 asset")
            appendLine("GET / - Basic server information")
        }

        return newFixedLengthResponse(
            Response.Status.OK,
            "text/plain; charset=utf-8",
            debugInfo
        )
    }

    /**
     * Serve health check endpoint for monitoring.
     * MONITORING ENDPOINT: Simple health status for automated monitoring
     */
    private fun serveHealthCheck(): Response {
        val healthStatus = buildString {
            appendLine("HTTP Server Health Check")
            appendLine("=======================")
            appendLine("Status: ${if (isServerRunning) "HEALTHY" else "DOWN"}")
            appendLine("Port: $listeningPort")
            appendLine("Timestamp: ${System.currentTimeMillis()}")

            // Quick validation checks
            val hasMedia = storageUtil.getCurrentMediaSource() != null
            val cacheAccessible = cacheDir.exists() && cacheDir.canRead()

            appendLine("Media Available: $hasMedia")
            appendLine("Cache Accessible: $cacheAccessible")

            val overallHealth = isServerRunning && cacheAccessible
            appendLine("Overall Health: ${if (overallHealth) "OK" else "ISSUES"}")
        }

        val status = if (isServerRunning) Response.Status.OK else Response.Status.SERVICE_UNAVAILABLE

        return newFixedLengthResponse(
            status,
            "text/plain; charset=utf-8",
            healthStatus
        )
    }

    /**
     * Reset to default fart.mp3 asset endpoint.
     * DEBUG ENDPOINT: Allows resetting to default asset for testing/debugging
     */
    private fun serveResetDefault(): Response {
        return try {
            // Reset to default asset synchronously (this is a debug endpoint)
            kotlinx.coroutines.runBlocking {
                storageUtil.loadDefaultAsset()
            }

            newFixedLengthResponse(
                Response.Status.OK,
                "text/plain",
                "Reset to default fart.mp3 asset successfully"
            )
        } catch (e: Exception) {
            Timber.e(e, "Error resetting to default asset")
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain",
                "Error resetting to default: ${e.message}"
            )
        }
    }

    /**
     * Check if the server is currently running.
     */
    fun isRunning(): Boolean = isServerRunning
}
