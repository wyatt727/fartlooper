package com.wobbz.fartloop.core.media

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    @ApplicationContext private val context: Context,
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
        Timber.d("HttpServerManager: startServer() called")

        try {
            if (isServerRunning) {
                Timber.w("HttpServerManager: Server already running on port $listeningPort")
                return@withContext Result.success(baseUrl)
            }

            Timber.d("HttpServerManager: Starting HTTP server initialization...")

            // Step 1: Determine local IP with timeout
            Timber.d("HttpServerManager: Step 1 - Determining local IP address...")
            val localIp = try {
                withTimeoutOrNull(5000L) {
                    getLocalIpAddress()
                }
            } catch (e: Exception) {
                Timber.e(e, "HttpServerManager: Error getting local IP")
                "127.0.0.1"
            } ?: run {
                Timber.w("HttpServerManager: IP detection timed out, using localhost")
                "127.0.0.1"
            }

            Timber.i("HttpServerManager: Using IP address: $localIp")

            // Step 2: Start the NanoHTTPD server with timeout protection
            Timber.d("HttpServerManager: Step 2 - Starting NanoHTTPD server...")

            val serverStarted = try {
                withTimeoutOrNull(8000L) { // 8 second timeout for server startup
                    Timber.d("HttpServerManager: Calling NanoHTTPD.start()...")
                    start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)

                    Timber.d("HttpServerManager: NanoHTTPD.start() completed, checking status...")

                    // Give server a moment to initialize
                    kotlinx.coroutines.delay(200)

                    // Verify server is actually alive
                    val isAlive = try {
                        isAlive()
                    } catch (e: Exception) {
                        Timber.w(e, "HttpServerManager: Error checking if server is alive")
                        false
                    }

                    Timber.d("HttpServerManager: Server alive check: $isAlive")
                    isAlive
                }
            } catch (e: Exception) {
                Timber.e(e, "HttpServerManager: Exception during server startup")
                false
            }

            if (serverStarted == null) {
                Timber.e("HttpServerManager: Server startup timed out after 8 seconds")
                isServerRunning = false
                return@withContext Result.failure(IOException("HTTP server startup timed out"))
            }

            if (!serverStarted) {
                Timber.e("HttpServerManager: Server failed to start or is not alive")
                isServerRunning = false
                return@withContext Result.failure(IOException("HTTP server failed to start"))
            }

            // Step 3: Construct base URL
            Timber.d("HttpServerManager: Step 3 - Constructing base URL...")
            isServerRunning = true

            val baseUrl = try {
                "http://$localIp:$listeningPort"
            } catch (e: Exception) {
                Timber.e(e, "HttpServerManager: Error constructing base URL")
                "http://127.0.0.1:$listeningPort"
            }

            Timber.i("HttpServerManager: ✅ HTTP server started successfully on $baseUrl")
            Result.success(baseUrl)

        } catch (e: IOException) {
            Timber.e(e, "HttpServerManager: IOException during server startup")
            isServerRunning = false
            Result.failure(e)
        } catch (e: Exception) {
            Timber.e(e, "HttpServerManager: Unexpected error during server startup")
            isServerRunning = false
            Result.failure(IOException("Unexpected error: ${e.message}", e))
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
            // HTTP STATUS FINDING: BAD_GATEWAY not available in this NanoHTTPD version, use INTERNAL_ERROR
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
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
        Timber.d("HttpServerManager: getLocalIpAddress() starting...")

        return try {
            Timber.d("HttpServerManager: Enumerating network interfaces...")

            // METHOD 1: Try to find Wi-Fi interface first (most reliable on Android)
            val networkInterfaces = java.net.NetworkInterface.getNetworkInterfaces()
            val wifiCandidates = mutableListOf<String>()
            val otherCandidates = mutableListOf<String>()

            var interfaceCount = 0
            for (networkInterface in networkInterfaces) {
                interfaceCount++

                if (!networkInterface.isUp || networkInterface.isLoopback) {
                    Timber.d("HttpServerManager: Skipping interface ${networkInterface.displayName} (down or loopback)")
                    continue
                }

                val displayName = networkInterface.displayName.lowercase()
                Timber.d("HttpServerManager: Checking interface: ${networkInterface.displayName}")

                // Prefer Wi-Fi interfaces
                val isWifi = displayName.contains("wlan") || displayName.contains("wifi") || displayName.contains("wl")

                var addressCount = 0
                for (address in networkInterface.inetAddresses) {
                    addressCount++

                    if (address.isLoopbackAddress || address.isLinkLocalAddress) continue
                    if (address !is java.net.Inet4Address) continue

                    val ip = address.hostAddress
                    if (ip != null) {
                        Timber.d("HttpServerManager: Found IP on ${networkInterface.displayName}: $ip (isWifi: $isWifi)")

                        if (isWifi) {
                            wifiCandidates.add(ip)
                        } else {
                            otherCandidates.add(ip)
                        }
                    }
                }

                Timber.d("HttpServerManager: Interface ${networkInterface.displayName} had $addressCount addresses")
            }

            Timber.d("HttpServerManager: Processed $interfaceCount interfaces")
            Timber.d("HttpServerManager: WiFi candidates: $wifiCandidates")
            Timber.d("HttpServerManager: Other candidates: $otherCandidates")

            // Return best candidate
            when {
                wifiCandidates.isNotEmpty() -> {
                    val chosen = wifiCandidates.first()
                    Timber.i("HttpServerManager: Using Wi-Fi IP address: $chosen")
                    chosen
                }
                otherCandidates.isNotEmpty() -> {
                    val chosen = otherCandidates.first()
                    Timber.i("HttpServerManager: Using fallback IP address: $chosen")
                    chosen
                }
                else -> {
                    Timber.w("HttpServerManager: No suitable network interfaces found, using localhost")
                    "127.0.0.1"
                }
            }

        } catch (e: Exception) {
            Timber.e(e, "HttpServerManager: Error determining local IP address, using localhost")
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
