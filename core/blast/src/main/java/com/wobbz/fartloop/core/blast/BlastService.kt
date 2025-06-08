package com.wobbz.fartloop.core.blast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import com.wobbz.fartloop.core.media.HttpServerManager
import com.wobbz.fartloop.core.media.StorageUtil
import com.wobbz.fartloop.core.network.ModernDiscoveryBus
import com.wobbz.fartloop.core.network.ModernUpnpControlClient
import com.wobbz.fartloop.core.network.UpnpDevice
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import kotlinx.coroutines.delay

/**
 * Foreground service that orchestrates the complete blast pipeline.
 *
 * Pipeline stages:
 * 1. Start HTTP server for media serving
 * 2. Discover devices on network (SSDP + mDNS + port scan)
 * 3. Send media to each discovered device (SetAVTransportURI → Play)
 * 4. Track metrics and update notification
 * 5. Broadcast final results
 *
 * Runs as foreground service to survive Doze mode and provide user feedback.
 *
 * HILT COMPATIBILITY FINDING: Changed from LifecycleService to Service
 * Hilt doesn't support LifecycleService directly, so we manage coroutines manually
 * with a SupervisorJob scope that gets cancelled in onDestroy.
 */
@AndroidEntryPoint
class BlastService : Service() {

    @Inject lateinit var httpServerManager: HttpServerManager

    @Inject lateinit var storageUtil: StorageUtil

    @Inject lateinit var discoveryBus: ModernDiscoveryBus

    @Inject lateinit var upnpControlClient: ModernUpnpControlClient

    @Inject lateinit var blastMetrics: BlastMetricsCollector

    private var notificationManager: NotificationManager? = null
    private var isBlastInProgress = false

    // Track final discovery method stats for convenience overload
    private var finalDiscoveryMethodStats = DiscoveryMethodStats()

    // Track XML parsing progress for progress bars
    private var xmlParsingInProgress = 0
    private var xmlParsingCompleted = 0

    // Track discovered devices for post-discovery XML updates
    private var discoveredDevices: MutableList<UpnpDevice> = mutableListOf()

    // Manual coroutine scope management since we can't use LifecycleService with Hilt
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        const val ACTION_START_BLAST = "com.wobbz.fartloop.ACTION_START_BLAST"
        const val ACTION_AUTO_BLAST = "com.wobbz.fartloop.ACTION_AUTO_BLAST"
        const val ACTION_STOP_BLAST = "com.wobbz.fartloop.ACTION_STOP_BLAST"
        const val ACTION_DISCOVER_ONLY = "com.wobbz.fartloop.ACTION_DISCOVER_ONLY"
        const val ACTION_BLAST_SINGLE_DEVICE = "com.wobbz.fartloop.ACTION_BLAST_SINGLE_DEVICE"
        const val EXTRA_DISCOVERY_TIMEOUT = "discovery_timeout"
        const val EXTRA_CONCURRENCY = "concurrency"
        const val EXTRA_TRIGGER_REASON = "trigger_reason"
        const val EXTRA_DEVICE_IP = "device_ip"
        const val EXTRA_DEVICE_PORT = "device_port"
        const val EXTRA_DEVICE_NAME = "device_name"
        const val EXTRA_DEVICE_CONTROL_URL = "device_control_url"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "blast_service"

        /**
         * Start a blast operation.
         */
        fun startBlast(
            context: Context,
            discoveryTimeoutMs: Long = 4000,
            concurrency: Int = 3,
        ) {
            Timber.i("🔧 BlastService.startBlast() called - Creating foreground service intent")
            val intent = Intent(context, BlastService::class.java).apply {
                action = ACTION_START_BLAST
                putExtra(EXTRA_DISCOVERY_TIMEOUT, discoveryTimeoutMs)
                putExtra(EXTRA_CONCURRENCY, concurrency)
            }
            Timber.d("BlastService.startBlast() - Starting foreground service")
            context.startForegroundService(intent)
            Timber.i("BlastService.startBlast() - Foreground service start requested")
        }

        /**
         * Stop any ongoing blast operation.
         */
        fun stopBlast(context: Context) {
            Timber.i("🛑 BlastService.stopBlast() called")
            val intent = Intent(context, BlastService::class.java).apply {
                action = ACTION_STOP_BLAST
            }
            context.startService(intent)
            Timber.d("BlastService.stopBlast() - Stop service intent sent")
        }

        /**
         * Discover devices without blasting audio.
         */
        fun discoverDevices(
            context: Context,
            discoveryTimeoutMs: Long = 4000
        ) {
            try {
                Timber.i("🔍 BlastService.discoverDevices() called - timeout: ${discoveryTimeoutMs}ms")
                Timber.d("BlastService.discoverDevices() - context: ${context.javaClass.simpleName}")

                Timber.d("BlastService.discoverDevices() - About to create Intent")
                val intent = Intent(context, BlastService::class.java)
                Timber.d("BlastService.discoverDevices() - Intent created successfully")

                intent.action = ACTION_DISCOVER_ONLY
                Timber.d("BlastService.discoverDevices() - Action set to: $ACTION_DISCOVER_ONLY")

                intent.putExtra(EXTRA_DISCOVERY_TIMEOUT, discoveryTimeoutMs)
                Timber.d("BlastService.discoverDevices() - Extras added")

                Timber.d("BlastService.discoverDevices() - Starting foreground service for discovery")

                // ANDROID 14 FIX: Try startForegroundService first, then fallback to startService if needed
                try {
                    val result = context.startForegroundService(intent)
                    Timber.i("BlastService.discoverDevices() - startForegroundService() returned: $result")

                    if (result == null) {
                        Timber.w("BlastService.discoverDevices() - startForegroundService returned null, trying startService fallback")
                        val fallbackResult = context.startService(intent)
                        Timber.i("BlastService.discoverDevices() - startService() fallback returned: $fallbackResult")
                    }
                } catch (e: SecurityException) {
                    Timber.w(e, "BlastService.discoverDevices() - startForegroundService failed with SecurityException, trying startService fallback")
                    val fallbackResult = context.startService(intent)
                    Timber.i("BlastService.discoverDevices() - startService() fallback returned: $fallbackResult")
                } catch (e: IllegalStateException) {
                    Timber.w(e, "BlastService.discoverDevices() - startForegroundService failed with IllegalStateException, trying startService fallback")
                    val fallbackResult = context.startService(intent)
                    Timber.i("BlastService.discoverDevices() - startService() fallback returned: $fallbackResult")
                }

                Timber.i("BlastService.discoverDevices() - Method completed successfully")

            } catch (e: Exception) {
                Timber.e(e, "BlastService.discoverDevices() - Unexpected exception during service start")
                throw e
            }
        }

        /**
         * Blast audio to a specific device without discovery.
         * Device must already be known (from previous discovery).
         */
        fun blastToSingleDevice(
            context: Context,
            deviceIp: String,
            devicePort: Int,
            deviceName: String,
            deviceControlUrl: String = "/AVTransport/control" // Default UPnP control URL
        ) {
            Timber.i("🎯 BlastService.blastToSingleDevice() called - targeting: $deviceName ($deviceIp:$devicePort)")
            val intent = Intent(context, BlastService::class.java).apply {
                action = ACTION_BLAST_SINGLE_DEVICE
                putExtra(EXTRA_DEVICE_IP, deviceIp)
                putExtra(EXTRA_DEVICE_PORT, devicePort)
                putExtra(EXTRA_DEVICE_NAME, deviceName)
                putExtra(EXTRA_DEVICE_CONTROL_URL, deviceControlUrl)
            }
            Timber.d("BlastService.blastToSingleDevice() - Starting foreground service")
            context.startForegroundService(intent)
            Timber.i("BlastService.blastToSingleDevice() - Foreground service start requested")
        }
    }

    override fun onCreate() {
        super.onCreate()
        Timber.i("🚀 BlastService: onCreate() called - Service is starting")

        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        Timber.d("BlastService: Notification manager initialized and channel created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        Timber.i("🎯 BlastService: onStartCommand() called with action: ${intent?.action}")

        when (intent?.action) {
            ACTION_START_BLAST -> {
                Timber.i("BlastService: Starting blast operation")
                if (!isBlastInProgress) {
                    val discoveryTimeout = intent.getLongExtra(EXTRA_DISCOVERY_TIMEOUT, 4000)
                    val concurrency = intent.getIntExtra(EXTRA_CONCURRENCY, 3)
                    Timber.d("BlastService: Blast parameters - timeout: ${discoveryTimeout}ms, concurrency: $concurrency")
                    startBlastOperation(discoveryTimeout, concurrency, isAutoTriggered = false)
                } else {
                    Timber.w("Blast already in progress, ignoring start request")
                }
            }
            ACTION_AUTO_BLAST -> {
                Timber.i("BlastService: Starting auto-blast operation")
                if (!isBlastInProgress) {
                    val reason = intent.getStringExtra(EXTRA_TRIGGER_REASON) ?: "auto"
                    Timber.i("Starting auto-blast triggered by: $reason")
                    // Use default timeouts for auto-triggered blasts
                    startBlastOperation(4000, 3, isAutoTriggered = true)
                } else {
                    Timber.w("Blast already in progress, ignoring auto-blast request")
                }
            }
            ACTION_STOP_BLAST -> {
                Timber.i("BlastService: Stopping blast operation")
                stopBlastOperation()
            }
            ACTION_DISCOVER_ONLY -> {
                Timber.i("BlastService: Starting discovery-only operation")
                if (!isBlastInProgress) {
                    val discoveryTimeout = intent.getLongExtra(EXTRA_DISCOVERY_TIMEOUT, 4000)
                    Timber.d("BlastService: Discovery timeout: ${discoveryTimeout}ms")

                    // ANDROID 14 FIX: Ensure service runs in foreground for discovery
                    try {
                        startForeground(NOTIFICATION_ID, createNotification("Starting discovery...", BlastPhase.DISCOVERING))
                        Timber.d("BlastService: Promoted to foreground service for discovery")
                    } catch (e: Exception) {
                        Timber.w(e, "BlastService: Failed to promote to foreground, continuing as background service")
                    }

                    startDiscoveryOnlyOperation(discoveryTimeout)
                } else {
                    Timber.w("Blast in progress, ignoring discovery-only request")
                }
            }
            ACTION_BLAST_SINGLE_DEVICE -> {
                Timber.i("BlastService: Starting single device blast operation")
                if (!isBlastInProgress) {
                    val deviceIp = intent.getStringExtra(EXTRA_DEVICE_IP) ?: ""
                    val devicePort = intent.getIntExtra(EXTRA_DEVICE_PORT, 0)
                    val deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "Unknown Device"
                    val deviceControlUrl = intent.getStringExtra(EXTRA_DEVICE_CONTROL_URL) ?: "/AVTransport/control"

                    Timber.d("BlastService: Single device blast parameters - IP: $deviceIp, Port: $devicePort, Name: $deviceName")

                    if (deviceIp.isNotEmpty() && devicePort > 0) {
                        startSingleDeviceBlastOperation(deviceIp, devicePort, deviceName, deviceControlUrl)
                    } else {
                        Timber.e("BlastService: Invalid device parameters for single device blast")
                        val intent = Intent("com.wobbz.fartloop.BLAST_ERROR").apply {
                            putExtra("error", "Invalid device parameters")
                        }
                        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
                    }
                } else {
                    Timber.w("Blast in progress, ignoring single device blast request")
                }
            }
            null -> {
                Timber.w("BlastService: Received null intent")
            }
            else -> {
                Timber.w("BlastService: Unknown action: ${intent.action}")
            }
        }

        return START_REDELIVER_INTENT // Restart if killed
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Timber.d("BlastService destroyed")

        // Cancel all coroutines and cleanup
        serviceScope.launch {
            try {
                httpServerManager.stopServer()
            } catch (e: Exception) {
                Timber.w(e, "Error stopping HTTP server during service destroy")
            }
        }

        // Cancel the service scope to clean up all coroutines
        serviceScope.cancel()
    }

    /**
     * Start the complete blast operation pipeline.
     */
    private fun startBlastOperation(discoveryTimeoutMs: Long, concurrency: Int, isAutoTriggered: Boolean = false) {
        val trigger = if (isAutoTriggered) "auto-triggered" else "manual"
        Timber.i("Starting $trigger blast operation (discovery: ${discoveryTimeoutMs}ms, concurrency: $concurrency)")

        isBlastInProgress = true
        blastMetrics.resetForNewBlast()

        // Different notification text for auto vs manual blasts
        val startMessage = if (isAutoTriggered) "Auto-blast starting..." else "Starting blast..."
        startForeground(NOTIFICATION_ID, createNotification(startMessage, BlastPhase.HTTP_STARTING))

        serviceScope.launch {
            try {
                // Stage 1: Start HTTP Server
                startHttpServer()

                // Stage 2: Discover Devices
                val devices = discoverDevices(discoveryTimeoutMs)

                // Stage 3: Blast to Devices
                blastToDevices(devices, concurrency)

                // Stage 4: Complete
                completeBlast()
            } catch (e: Exception) {
                Timber.e(e, "Blast operation failed")
                handleBlastError(e)
            }
        }
    }

    /**
     * Stop any ongoing blast operation.
     */
    private fun stopBlastOperation() {
        Timber.i("Stopping blast operation")

        isBlastInProgress = false
        broadcastStageUpdate(BlastStage.IDLE)

        serviceScope.launch {
            try {
                httpServerManager.stopServer()
            } catch (e: Exception) {
                Timber.w(e, "Error stopping HTTP server")
            }

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /**
     * Start a discovery-only operation without blasting audio.
     */
    private fun startDiscoveryOnlyOperation(discoveryTimeoutMs: Long) {
        Timber.i("🔍 BlastService: startDiscoveryOnlyOperation() called with timeout: ${discoveryTimeoutMs}ms")

        isBlastInProgress = true // Prevent multiple simultaneous operations
        blastMetrics.resetForNewBlast()

        // Note: startForeground is now called in onStartCommand to avoid duplicate calls
        Timber.d("BlastService: Discovery operation initialized")

        serviceScope.launch {
            try {
                Timber.i("BlastService: Launching discovery coroutine")
                // Only run discovery phase
                val devices = discoverDevices(discoveryTimeoutMs)

                Timber.i("BlastService: Discovery completed with ${devices.size} devices")
                updateNotification("Discovery complete: ${devices.size} devices found", BlastPhase.COMPLETE)

                // Broadcast discovery completion
                val intent = Intent("com.wobbz.fartloop.DISCOVERY_COMPLETE").apply {
                    putExtra("devices_found", devices.size)
                }
                LocalBroadcastManager.getInstance(this@BlastService).sendBroadcast(intent)

                // DISCOVERY-ONLY FIX: Return to IDLE state instead of COMPLETED
                // This allows users to start blasting to the discovered devices
                broadcastStageUpdate(BlastStage.IDLE)
                broadcastMetricsUpdate()

                Timber.d("BlastService: Discovery-only operation complete: ${devices.size} devices found")
            } catch (e: Exception) {
                Timber.e(e, "BlastService: Discovery operation failed")

                val errorMessage = "Discovery failed: ${e.message}"
                updateNotification(errorMessage, BlastPhase.COMPLETE)

                // Broadcast discovery error
                val intent = Intent("com.wobbz.fartloop.DISCOVERY_ERROR").apply {
                    putExtra("error", e.message)
                }
                LocalBroadcastManager.getInstance(this@BlastService).sendBroadcast(intent)

                // DISCOVERY-ONLY ERROR FIX: Return to IDLE state instead of COMPLETED
                // This allows users to retry discovery after an error
                broadcastStageUpdate(BlastStage.IDLE)
                broadcastMetricsUpdate()
            } finally {
                isBlastInProgress = false

                // Stop service after a delay to show the final notification
                kotlinx.coroutines.delay(2000)

                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        Timber.d("BlastService: Discovery coroutine launched, method completing")
    }

    /**
     * Start a single device blast operation without discovery.
     * Uses HTTP server and blasts only to the specified device.
     */
    private fun startSingleDeviceBlastOperation(
        deviceIp: String,
        devicePort: Int,
        deviceName: String,
        deviceControlUrl: String
    ) {
        Timber.i("🎯 BlastService: startSingleDeviceBlastOperation() called - targeting: $deviceName ($deviceIp:$devicePort)")

        isBlastInProgress = true
        blastMetrics.resetForNewBlast()

        startForeground(NOTIFICATION_ID, createNotification("Blasting to $deviceName...", BlastPhase.HTTP_STARTING))

        serviceScope.launch {
            try {
                // Stage 1: Start HTTP Server
                startHttpServer()

                // Stage 2: Create single device target
                // First, try to find this device in our discovered devices list to preserve metadata
                val discoveredDevice = discoveredDevices.find {
                    it.ipAddress == deviceIp && it.port == devicePort
                }

                val targetDevice = if (discoveredDevice != null) {
                    // Use the discovered device with all its metadata and XML-parsed info
                    Timber.i("BlastService: Using discovered device with ${discoveredDevice.metadata.size} metadata fields")
                    discoveredDevice
                } else {
                    // Fall back to creating a basic device if not found in discovery
                    Timber.w("BlastService: Device not found in discovery, creating basic device")
                    UpnpDevice(
                        ipAddress = deviceIp,
                        port = devicePort,
                        friendlyName = deviceName,
                        controlUrl = deviceControlUrl,
                        deviceType = "",
                        manufacturer = "",
                        discoveryMethod = "Manual"
                    )
                }

                // Stage 3: Blast to Single Device
                Timber.d("BlastService: Starting single device blast to $deviceName")
                updateNotification("Sending to $deviceName...", BlastPhase.BLASTING)
                broadcastStageUpdate(BlastStage.BLASTING)

                val mediaUrl = httpServerManager.getMediaUrl()
                if (mediaUrl == null) {
                    throw Exception("No media URL available - check media source and HTTP server")
                }

                Timber.i("BlastService: Blasting media URL to single device: $mediaUrl")

                // Broadcast device update to show connecting state
                broadcastDeviceUpdate(targetDevice, DeviceStatus.CONNECTING)

                // Blast to the single device
                blastMetrics.startBlastTiming()
                blastToSingleDevice(targetDevice, mediaUrl)

                // Stage 4: Complete
                completeSingleDeviceBlast(deviceName)

            } catch (e: Exception) {
                Timber.e(e, "Single device blast operation failed")
                handleBlastError(e)
            }
        }
    }

    /**
     * Complete a single device blast operation.
     */
    private suspend fun completeSingleDeviceBlast(deviceName: String) {
        Timber.d("Completing single device blast to $deviceName")

        updateNotification("Blast to $deviceName complete", BlastPhase.COMPLETE)
        broadcastStageUpdate(BlastStage.COMPLETED)

        blastMetrics.completeBlast()
        broadcastMetricsUpdate()

        // Broadcast completion
        val intent = Intent("com.wobbz.fartloop.SINGLE_DEVICE_BLAST_COMPLETE").apply {
            putExtra("device_name", deviceName)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

        // Cleanup
        isBlastInProgress = false

        // Keep notification for a short time, then stop service
        kotlinx.coroutines.delay(3000)

        try {
            httpServerManager.stopServer()
        } catch (e: Exception) {
            Timber.w(e, "Error stopping HTTP server after single device blast")
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        Timber.i("Single device blast to $deviceName completed successfully")
    }

    /**
     * Stage 1: Start HTTP server for media serving.
     */
    private suspend fun startHttpServer() = withContext(Dispatchers.IO) {
        Timber.d("Stage 1: Starting HTTP server")
        updateNotification("Starting HTTP server...", BlastPhase.HTTP_STARTING)
        broadcastStageUpdate(BlastStage.HTTP_STARTING)

        blastMetrics.startHttpTiming()

        val result = httpServerManager.startServer()
        if (result.isFailure) {
            throw Exception("Failed to start HTTP server: ${result.exceptionOrNull()?.message}")
        }

        blastMetrics.completeHttpStartup()
        broadcastMetricsUpdate()
        Timber.i("HTTP server started: ${result.getOrNull()}")
    }

    /**
     * Stage 2: Discover devices using multiple discovery methods.
     */
    private suspend fun discoverDevices(timeoutMs: Long): List<UpnpDevice> = withContext(Dispatchers.IO) {
        Timber.d("Stage 2: Discovering devices (timeout: ${timeoutMs}ms)")
        updateNotification("Discovering devices...", BlastPhase.DISCOVERING)
        broadcastStageUpdate(BlastStage.DISCOVERING)

        blastMetrics.startDiscoveryTiming()

        // Set up callbacks for SsdpDiscoverer to handle device updates and XML parsing progress
        setupSsdpCallbacks()

        val devices = mutableListOf<UpnpDevice>()
        val discoveryStartTime = System.currentTimeMillis()

        // Track XML parsing progress for progress bars
        var xmlParsingInProgress = 0
        var xmlParsingCompleted = 0

        // Start continuous progress updates for UI feedback
        val progressUpdateJob = serviceScope.launch {
            while (System.currentTimeMillis() - discoveryStartTime < timeoutMs) {
                val currentTime = System.currentTimeMillis() - discoveryStartTime

                // Create time-based progress metrics to show continuous movement
                val progressMetrics = blastMetrics.currentMetrics.value.copy(
                    discoveryDurationMs = currentTime.toInt(),
                    devicesDiscovered = devices.size
                )

                val timeBasedBlastMetrics = BlastMetrics(
                    httpStartupMs = progressMetrics.httpStartupMs.toLong(),
                    discoveryTimeMs = currentTime,
                    totalDevicesFound = devices.size,
                    connectionsAttempted = progressMetrics.totalDevicesTargeted,
                    successfulBlasts = progressMetrics.successfulDevices,
                    failedBlasts = progressMetrics.failedDevices,
                    averageLatencyMs = blastMetrics.getAverageSoapTime().toLong(),
                    isRunning = true,
                    xmlParsingInProgress = xmlParsingInProgress,
                    xmlParsingCompleted = xmlParsingCompleted,
                    discoveryMethodStats = finalDiscoveryMethodStats
                )

                blastMetrics.updateCurrentMetrics(progressMetrics)
                broadcastMetricsUpdate(timeBasedBlastMetrics)

                // Update every 500ms for smooth progress animation
                delay(500)
            }
        }

        // Track per-method discovery stats for metrics
        val discoveryMethodCounts = mutableMapOf<String, Int>(
            "SSDP" to 0,
            "mDNS" to 0,
            "PortScan" to 0
        )
        val discoveryMethodTimes = mutableMapOf<String, Long>(
            "SSDP" to 0L,
            "mDNS" to 0L,
            "PortScan" to 0L
        )

        // Store discovered device IDs to prevent duplicates while preserving better friendly names
        val discoveredDeviceIds = mutableSetOf<String>()

        try {
            discoveryBus.discoverAll(timeoutMs)
                .flowOn(Dispatchers.IO)
                .catch { e ->
                    Timber.e(e, "Discovery error")
                }
                .collect { device ->
                    val deviceId = "${device.ipAddress}:${device.port}"

                    // Check for duplicates - prefer devices with better friendly names but preserve metadata
                    val existingDevice = devices.find { it.ipAddress == device.ipAddress && it.port == device.port }
                    if (existingDevice != null) {
                        // Create merged device that preserves the best of both
                        val shouldReplaceCore = when {
                            // Always prefer SSDP discoveries over others for core device info
                            device.discoveryMethod == "SSDP" && existingDevice.discoveryMethod != "SSDP" -> true
                            // Prefer mDNS over PortScan for core device info
                            device.discoveryMethod == "mDNS" && existingDevice.discoveryMethod == "PortScan" -> true
                            // Within same method, prefer names that don't start with generic patterns
                            device.discoveryMethod == existingDevice.discoveryMethod -> {
                                val newIsGeneric = device.friendlyName.startsWith("Device at ") ||
                                                 device.friendlyName.startsWith("Network Device at ") ||
                                                 device.friendlyName.contains(" at ")
                                val existingIsGeneric = existingDevice.friendlyName.startsWith("Device at ") ||
                                                       existingDevice.friendlyName.startsWith("Network Device at ") ||
                                                       existingDevice.friendlyName.contains(" at ")

                                // Replace if new is non-generic and existing is generic
                                !newIsGeneric && existingIsGeneric
                            }
                            else -> false
                        }

                        // Merge metadata - preserve existing metadata and add new metadata
                        val mergedMetadata = mutableMapOf<String, String>()
                        mergedMetadata.putAll(existingDevice.metadata)
                        mergedMetadata.putAll(device.metadata)

                        // Choose the better device as base, but preserve metadata
                        val mergedDevice = if (shouldReplaceCore) {
                            device.copy(metadata = mergedMetadata)
                        } else {
                            existingDevice.copy(metadata = mergedMetadata)
                        }

                        devices.remove(existingDevice)
                        devices.add(mergedDevice)

                        Timber.d("Merged device: ${mergedDevice.friendlyName} (${mergedDevice.discoveryMethod}) with ${mergedMetadata.size} metadata fields")
                    } else if (discoveredDeviceIds.add(deviceId)) {
                        devices.add(device)
                        Timber.d("Discovered device: ${device.friendlyName} via ${device.discoveryMethod}")

                        // Track discovery method stats
                        val method = device.discoveryMethod
                        discoveryMethodCounts[method] = (discoveryMethodCounts[method] ?: 0) + 1
                    }

                    updateNotification("Found ${devices.size} devices...", BlastPhase.DISCOVERING)

                    // Broadcast device discovery
                    broadcastDeviceUpdate(device, DeviceStatus.DISCOVERED)

                    Timber.d("Discovery progress: ${devices.size} devices found via ${device.discoveryMethod}")
                }
        } finally {
            // Cancel the progress update job when discovery is complete
            progressUpdateJob.cancel()
        }

        // Final discovery method stats
        val finalDiscoveryTime = System.currentTimeMillis() - discoveryStartTime
        val finalDiscoveryStats = DiscoveryMethodStats(
            ssdpDevicesFound = discoveryMethodCounts["SSDP"] ?: 0,
            mdnsDevicesFound = discoveryMethodCounts["mDNS"] ?: 0,
            portScanDevicesFound = discoveryMethodCounts["PortScan"] ?: 0,
            ssdpTimeMs = if (discoveryMethodCounts["SSDP"]!! > 0) finalDiscoveryTime else 0L,
            mdnsTimeMs = if (discoveryMethodCounts["mDNS"]!! > 0) finalDiscoveryTime else 0L,
            portScanTimeMs = if (discoveryMethodCounts["PortScan"]!! > 0) finalDiscoveryTime else 0L
        )

        // Store for convenience overload
        finalDiscoveryMethodStats = finalDiscoveryStats

        blastMetrics.completeDiscovery(devices.size)

        // Create final BlastMetrics with complete discovery method stats
        val finalMetrics = blastMetrics.currentMetrics.value
        val completedBlastMetrics = BlastMetrics(
            httpStartupMs = finalMetrics.httpStartupMs.toLong(),
            discoveryTimeMs = finalDiscoveryTime,
            totalDevicesFound = devices.size,
            connectionsAttempted = finalMetrics.totalDevicesTargeted,
            successfulBlasts = finalMetrics.successfulDevices,
            failedBlasts = finalMetrics.failedDevices,
            averageLatencyMs = blastMetrics.getAverageSoapTime().toLong(),
            isRunning = true,
            xmlParsingInProgress = xmlParsingInProgress,
            xmlParsingCompleted = xmlParsingCompleted,
            discoveryMethodStats = finalDiscoveryStats
        )

        broadcastMetricsUpdate(completedBlastMetrics)

        Timber.i("Discovery complete: ${devices.size} devices found")
        Timber.i("Discovery method breakdown - SSDP: ${finalDiscoveryStats.ssdpDevicesFound}, mDNS: ${finalDiscoveryStats.mdnsDevicesFound}, PortScan: ${finalDiscoveryStats.portScanDevicesFound}")
        Timber.i("Most effective method: ${finalDiscoveryStats.mostEffectiveMethod}")

        // Store devices for potential XML updates after discovery
        discoveredDevices.clear()
        discoveredDevices.addAll(devices)

        return@withContext devices
    }

    /**
     * Stage 3: Send media to all discovered devices.
     */
    private suspend fun blastToDevices(devices: List<UpnpDevice>, concurrency: Int) = withContext(Dispatchers.IO) {
        Timber.d("Stage 3: Blasting to ${devices.size} devices (concurrency: $concurrency)")
        updateNotification("Sending to ${devices.size} devices...", BlastPhase.BLASTING)
        broadcastStageUpdate(BlastStage.BLASTING)

        if (devices.isEmpty()) {
            Timber.w("No devices found to blast to")
            return@withContext
        }

        blastMetrics.startBlastTiming()

        // Get media URL from HTTP server
        val mediaUrl = httpServerManager.getMediaUrl()
        if (mediaUrl == null) {
            throw Exception("No media URL available - check media source and HTTP server")
        }

        Timber.i("Blasting media URL: $mediaUrl")

        // Process devices with controlled concurrency
        val semaphore = kotlinx.coroutines.sync.Semaphore(concurrency)
        val jobs = devices.map { device ->
            serviceScope.launch(Dispatchers.IO) {
                semaphore.acquire()
                try {
                    blastToSingleDevice(device, mediaUrl)
                } finally {
                    semaphore.release()
                }
            }
        }

        // Wait for all blasts to complete
        jobs.forEach { it.join() }

        broadcastMetricsUpdate()
        Timber.i("Blast phase complete")
    }

    /**
     * Send media to a single device and record metrics.
     */
    private suspend fun blastToSingleDevice(device: UpnpDevice, mediaUrl: String) = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        try {
            Timber.d("Blasting to ${device.friendlyName}")
            broadcastDeviceUpdate(device, DeviceStatus.CONNECTING)

            val success = upnpControlClient.pushClip(device.ipAddress, device.port, device.controlUrl, mediaUrl)
            val duration = (System.currentTimeMillis() - startTime).toInt()

            if (success) {
                blastMetrics.recordDeviceSuccess(device.friendlyName, duration)
                broadcastDeviceUpdate(device, DeviceStatus.SUCCESS)
                Timber.i("✅ Success: ${device.friendlyName} (${duration}ms)")
            } else {
                val error = "Cast failed for ${device.friendlyName}"
                blastMetrics.recordDeviceFailure(device.friendlyName, error)
                broadcastDeviceUpdate(device, DeviceStatus.FAILED)
                Timber.w("❌ Failed: ${device.friendlyName} - $error")
            }
        } catch (e: Exception) {
            blastMetrics.recordDeviceFailure(device.friendlyName, e.message ?: "Exception")
            broadcastDeviceUpdate(device, DeviceStatus.FAILED)
            Timber.e(e, "❌ Exception blasting to ${device.friendlyName}")
        }
    }

    /**
     * Stage 4: Complete the blast operation.
     */
    private suspend fun completeBlast() {
        Timber.d("Stage 4: Completing blast operation")
        broadcastStageUpdate(BlastStage.COMPLETING)

        blastMetrics.completeBlast()

        val metrics = blastMetrics.currentMetrics.value
        val successRate = blastMetrics.getSuccessRate()

        val summary = "Blast complete: ${metrics.successfulDevices}/${metrics.totalDevicesTargeted} devices (${successRate.toInt()}%)"
        updateNotification(summary, BlastPhase.COMPLETE)

        Timber.i(summary)
        Timber.i(
            "Metrics: HTTP ${metrics.httpStartupMs}ms, Discovery ${metrics.discoveryDurationMs}ms, Total ${metrics.totalBlastDurationMs}ms",
        )

        // Broadcast final results
        broadcastStageUpdate(BlastStage.COMPLETED)
        broadcastBlastComplete(metrics)
        broadcastMetricsUpdate()

        // Stop HTTP server and service after a delay
        kotlinx.coroutines.delay(3000) // Show final notification for 3 seconds

        httpServerManager.stopServer()
        isBlastInProgress = false

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Handle blast operation errors.
     */
    private suspend fun handleBlastError(error: Exception) {
        Timber.e(error, "Blast operation failed")

        updateNotification("Blast failed: ${error.message}", BlastPhase.COMPLETE)

        // Broadcast error
        val intent = Intent("com.wobbz.fartloop.BLAST_ERROR").apply {
            putExtra("error", error.message)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

        // Cleanup
        try {
            httpServerManager.stopServer()
        } catch (e: Exception) {
            Timber.w(e, "Error stopping HTTP server after blast failure")
        }

        isBlastInProgress = false

        kotlinx.coroutines.delay(3000) // Show error for 3 seconds
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Broadcast blast completion with metrics.
     */
    private fun broadcastBlastComplete(metrics: MetricsSnapshot) {
        val intent = Intent("com.wobbz.fartloop.BLAST_COMPLETE").apply {
            putExtra("successful_devices", metrics.successfulDevices)
            putExtra("failed_devices", metrics.failedDevices)
            putExtra("total_duration_ms", metrics.totalBlastDurationMs)
            putExtra("success_rate", blastMetrics.getSuccessRate())
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    /**
     * Update the foreground notification.
     */
    private fun updateNotification(message: String, phase: BlastPhase) {
        val notification = createNotification(message, phase)
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Create notification for the foreground service.
     */
    private fun createNotification(message: String, phase: BlastPhase): Notification {
        // Create intent to launch the main app activity
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = if (intent != null) {
            PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else {
            null
        }

        val icon = when (phase) {
            BlastPhase.HTTP_STARTING -> android.R.drawable.ic_menu_upload
            BlastPhase.DISCOVERING -> android.R.drawable.ic_menu_search
            BlastPhase.BLASTING -> android.R.drawable.ic_media_play
            BlastPhase.COMPLETE -> android.R.drawable.ic_menu_info_details
            BlastPhase.IDLE -> android.R.drawable.ic_menu_info_details
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Fart-Looper Blast")
            .setContentText(message)
            .setSmallIcon(icon)
            .setOngoing(phase != BlastPhase.COMPLETE)
            .setPriority(NotificationCompat.PRIORITY_LOW) // Low importance to avoid heads-up

        if (pendingIntent != null) {
            builder.setContentIntent(pendingIntent)
        }

        return builder.build()
    }

    /**
     * Create notification channel for Android O+.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Blast Operations",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows progress of media blast operations"
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    /**
     * Broadcast stage updates to UI components.
     */
    private fun broadcastStageUpdate(stage: BlastStage) {
        val intent = Intent("com.wobbz.fartloop.BLAST_STAGE_UPDATE").apply {
            putExtra("stage", stage.name)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        Timber.d("BlastService: Broadcasted stage update: $stage")
    }

    /**
     * Broadcast current metrics to UI - enhanced with per-method discovery stats.
     * BROADCAST KEY FIX: Using consistent key names that match HomeViewModel expectations
     */
    private fun broadcastMetricsUpdate(metrics: BlastMetrics) {
        val intent = Intent("com.wobbz.fartloop.BLAST_METRICS_UPDATE").apply {
            putExtra("httpStartupMs", metrics.httpStartupMs)
            putExtra("discoveryTimeMs", metrics.discoveryTimeMs)
            putExtra("devicesFound", metrics.totalDevicesFound)
            putExtra("successfulBlasts", metrics.successfulBlasts)
            putExtra("failedBlasts", metrics.failedBlasts)
            putExtra("isRunning", metrics.isRunning)
            putExtra("connectionsAttempted", metrics.connectionsAttempted)
            putExtra("averageLatencyMs", metrics.averageLatencyMs)
            putExtra("ssdpDevicesFound", metrics.discoveryMethodStats.ssdpDevicesFound)
            putExtra("mdnsDevicesFound", metrics.discoveryMethodStats.mdnsDevicesFound)
            putExtra("portScanDevicesFound", metrics.discoveryMethodStats.portScanDevicesFound)
            putExtra("ssdpTimeMs", metrics.discoveryMethodStats.ssdpTimeMs)
            putExtra("mdnsTimeMs", metrics.discoveryMethodStats.mdnsTimeMs)
            putExtra("portScanTimeMs", metrics.discoveryMethodStats.portScanTimeMs)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        Timber.d("BlastService: Broadcasted metrics update - ${metrics.totalDevicesFound} devices, HTTP ${metrics.httpStartupMs}ms, Discovery ${metrics.discoveryTimeMs}ms")
    }

    /**
     * Convenience overload that uses current metrics
     */
    private fun broadcastMetricsUpdate() {
        val currentMetrics = blastMetrics.currentMetrics.value
        val blastMetricsCompat = BlastMetrics(
            httpStartupMs = currentMetrics.httpStartupMs.toLong(),
            discoveryTimeMs = currentMetrics.discoveryDurationMs.toLong(),
            totalDevicesFound = currentMetrics.devicesDiscovered,
            connectionsAttempted = currentMetrics.totalDevicesTargeted,
            successfulBlasts = currentMetrics.successfulDevices,
            failedBlasts = currentMetrics.failedDevices,
            averageLatencyMs = blastMetrics.getAverageSoapTime().toLong(),
            isRunning = !currentMetrics.isComplete,
            discoveryMethodStats = finalDiscoveryMethodStats
        )
        broadcastMetricsUpdate(blastMetricsCompat)
    }

    /**
     * Broadcast device updates to UI components.
     *
     * METADATA ENHANCEMENT: For SSDP devices, check if enhanced XML metadata
     * is available in the SsdpDiscoverer cache and include it in the broadcast.
     */
    private fun broadcastDeviceUpdate(device: UpnpDevice, status: DeviceStatus) {
        // For SSDP devices, try to get enhanced metadata from cache
        var metadata = device.metadata
        if (device.discoveryMethod == "SSDP" && metadata.isEmpty()) {
            // Try to get enhanced metadata from SsdpDiscoverer cache
            // This is a simple implementation - in practice, we'd need dependency injection
            // For now, we'll just use the device's existing metadata
            metadata = device.metadata
        }

        val intent = Intent("com.wobbz.fartloop.BLAST_DEVICE_UPDATE").apply {
            putExtra("deviceId", "${device.ipAddress}:${device.port}")
            putExtra("deviceName", device.friendlyName)
            putExtra("deviceType", mapDeviceType(device).name)
            putExtra("ipAddress", device.ipAddress)
            putExtra("port", device.port)
            putExtra("controlUrl", device.controlUrl)
            putExtra("status", status.name)

            // Include device metadata from XML parsing
            if (metadata.isNotEmpty()) {
                putExtra("metadataSize", metadata.size)
                metadata.forEach { (key, value) ->
                    putExtra("metadata_$key", value)
                }
            }
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        Timber.d("BlastService: Broadcasted device update: ${device.friendlyName} -> $status (metadata: ${metadata.size} fields)")
    }

    /**
     * Map UpnpDevice to DeviceType for UI display.
     *
     * DEVICE TYPE MAPPING FIX: Use the actual deviceType from UpnpDevice instead of guessing
     * from friendly name and port. The deviceType was properly determined during discovery.
     */
    private fun mapDeviceType(device: UpnpDevice): DeviceType {
        // First, try to use the actual deviceType from discovery
        return when (device.deviceType.uppercase()) {
            "SONOS" -> DeviceType.SONOS
            "CHROMECAST" -> DeviceType.CHROMECAST
            "DLNA_RENDERER" -> DeviceType.UPNP  // Map DLNA to generic UPnP in blast layer
            "AIRPLAY" -> DeviceType.AIRPLAY
            "UNKNOWN_UPNP" -> DeviceType.UPNP
            "ROKU" -> DeviceType.UPNP  // Map Roku to generic UPnP since it's not in blast enum
            else -> {
                // Fallback to heuristic-based detection for older devices
                when {
                    device.friendlyName.contains("Sonos", ignoreCase = true) -> DeviceType.SONOS
                    device.friendlyName.contains("Chromecast", ignoreCase = true) -> DeviceType.CHROMECAST
                    device.friendlyName.contains("Samsung", ignoreCase = true) -> DeviceType.SAMSUNG
                    device.port == 1400 -> DeviceType.SONOS
                    device.port == 8008 || device.port == 8009 -> DeviceType.CHROMECAST
                    else -> DeviceType.UPNP
                }
            }
        }
    }

    /**
     * Set up callbacks for SsdpDiscoverer to handle device updates and XML parsing progress
     */
    private fun setupSsdpCallbacks() {
        val ssdpDiscoverer = discoveryBus.getSsdpDiscoverer()

        // Set device update callback for when XML parsing completes with real control URLs
        ssdpDiscoverer.setDeviceUpdateCallback { updatedDevice ->
            Timber.d("BlastService: Received device update from XML parsing: ${updatedDevice.friendlyName}")

            // Update the device in our stored list
            val deviceIndex = discoveredDevices.indexOfFirst {
                it.ipAddress == updatedDevice.ipAddress && it.port == updatedDevice.port
            }

            if (deviceIndex >= 0) {
                // Merge with existing device metadata
                val existingDevice = discoveredDevices[deviceIndex]
                val mergedMetadata = mutableMapOf<String, String>()
                mergedMetadata.putAll(existingDevice.metadata)
                mergedMetadata.putAll(updatedDevice.metadata)

                val mergedDevice = updatedDevice.copy(metadata = mergedMetadata)
                discoveredDevices[deviceIndex] = mergedDevice

                Timber.i("BlastService: Updated stored device ${mergedDevice.friendlyName} with XML data (${mergedMetadata.size} metadata fields)")
            } else {
                Timber.w("BlastService: Could not find device ${updatedDevice.friendlyName} in stored list for XML update")
            }

            // Broadcast the updated device with real control URL and metadata
            broadcastDeviceUpdate(updatedDevice, DeviceStatus.DISCOVERED)

            Timber.i("BlastService: Updated device ${updatedDevice.friendlyName} with real control URL: ${updatedDevice.controlUrl}")
        }

        // Set XML parsing progress callback for progress bar updates
        ssdpDiscoverer.setXmlParsingProgressCallback { totalParsing, completed ->
            xmlParsingInProgress = totalParsing
            xmlParsingCompleted = completed

            Timber.d("BlastService: XML parsing progress: $completed completed, $totalParsing in progress")

            // Update metrics with XML parsing progress
            val currentMetrics = blastMetrics.currentMetrics.value
            val updatedBlastMetrics = BlastMetrics(
                httpStartupMs = currentMetrics.httpStartupMs.toLong(),
                discoveryTimeMs = currentMetrics.discoveryDurationMs.toLong(),
                totalDevicesFound = currentMetrics.devicesDiscovered,
                connectionsAttempted = currentMetrics.totalDevicesTargeted,
                successfulBlasts = currentMetrics.successfulDevices,
                failedBlasts = currentMetrics.failedDevices,
                averageLatencyMs = blastMetrics.getAverageSoapTime().toLong(),
                isRunning = !currentMetrics.isComplete,
                xmlParsingInProgress = totalParsing,
                xmlParsingCompleted = completed,
                discoveryMethodStats = finalDiscoveryMethodStats
            )

            broadcastMetricsUpdate(updatedBlastMetrics)
        }
    }
}
