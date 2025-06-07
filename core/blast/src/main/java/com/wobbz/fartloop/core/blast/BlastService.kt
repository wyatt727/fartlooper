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

    // Manual coroutine scope management since we can't use LifecycleService with Hilt
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        const val ACTION_START_BLAST = "com.wobbz.fartloop.ACTION_START_BLAST"
        const val ACTION_AUTO_BLAST = "com.wobbz.fartloop.ACTION_AUTO_BLAST"
        const val ACTION_STOP_BLAST = "com.wobbz.fartloop.ACTION_STOP_BLAST"
        const val ACTION_DISCOVER_ONLY = "com.wobbz.fartloop.ACTION_DISCOVER_ONLY"
        const val EXTRA_DISCOVERY_TIMEOUT = "discovery_timeout"
        const val EXTRA_CONCURRENCY = "concurrency"
        const val EXTRA_TRIGGER_REASON = "trigger_reason"

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
            val intent = Intent(context, BlastService::class.java).apply {
                action = ACTION_START_BLAST
                putExtra(EXTRA_DISCOVERY_TIMEOUT, discoveryTimeoutMs)
                putExtra(EXTRA_CONCURRENCY, concurrency)
            }
            context.startForegroundService(intent)
        }

        /**
         * Stop any ongoing blast operation.
         */
        fun stopBlast(context: Context) {
            val intent = Intent(context, BlastService::class.java).apply {
                action = ACTION_STOP_BLAST
            }
            context.startService(intent)
        }

        /**
         * Discover devices without blasting audio.
         */
        fun discoverDevices(
            context: Context,
            discoveryTimeoutMs: Long = 4000
        ) {
            val intent = Intent(context, BlastService::class.java).apply {
                action = ACTION_DISCOVER_ONLY
                putExtra(EXTRA_DISCOVERY_TIMEOUT, discoveryTimeoutMs)
            }
            context.startForegroundService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Timber.d("BlastService created")

        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START_BLAST -> {
                if (!isBlastInProgress) {
                    val discoveryTimeout = intent.getLongExtra(EXTRA_DISCOVERY_TIMEOUT, 4000)
                    val concurrency = intent.getIntExtra(EXTRA_CONCURRENCY, 3)
                    startBlastOperation(discoveryTimeout, concurrency, isAutoTriggered = false)
                } else {
                    Timber.w("Blast already in progress, ignoring start request")
                }
            }
            ACTION_AUTO_BLAST -> {
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
                stopBlastOperation()
            }
            ACTION_DISCOVER_ONLY -> {
                if (!isBlastInProgress) {
                    val discoveryTimeout = intent.getLongExtra(EXTRA_DISCOVERY_TIMEOUT, 4000)
                    startDiscoveryOnlyOperation(discoveryTimeout)
                } else {
                    Timber.w("Blast in progress, ignoring discovery-only request")
                }
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
        Timber.i("Starting discovery-only operation (timeout: ${discoveryTimeoutMs}ms)")

        isBlastInProgress = true // Prevent multiple simultaneous operations
        blastMetrics.resetForNewBlast()

        startForeground(NOTIFICATION_ID, createNotification("Discovering devices...", BlastPhase.DISCOVERING))

        serviceScope.launch {
            try {
                // Only run discovery phase
                val devices = discoverDevices(discoveryTimeoutMs)

                // Complete discovery operation
                completeDiscoveryOnly(devices)
            } catch (e: Exception) {
                Timber.e(e, "Discovery operation failed")
                handleDiscoveryError(e)
            }
        }
    }

    /**
     * Complete discovery-only operation.
     */
    private suspend fun completeDiscoveryOnly(devices: List<UpnpDevice>) {
        Timber.d("Discovery-only operation complete: ${devices.size} devices found")

        val summary = "Discovery complete: ${devices.size} devices found"
        updateNotification(summary, BlastPhase.COMPLETE)

        // Broadcast discovery completion
        val intent = Intent("com.wobbz.fartloop.DISCOVERY_COMPLETE").apply {
            putExtra("devices_found", devices.size)
        }
        sendBroadcast(intent)

        broadcastStageUpdate(BlastStage.COMPLETED)
        broadcastMetricsUpdate()

        // Stop service after a delay
        kotlinx.coroutines.delay(2000) // Show notification for 2 seconds

        isBlastInProgress = false

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Handle discovery-only operation errors.
     */
    private suspend fun handleDiscoveryError(error: Exception) {
        Timber.e(error, "Discovery operation failed")

        updateNotification("Discovery failed: ${error.message}", BlastPhase.COMPLETE)

        // Broadcast error
        val intent = Intent("com.wobbz.fartloop.DISCOVERY_ERROR").apply {
            putExtra("error", error.message)
        }
        sendBroadcast(intent)

        isBlastInProgress = false

        kotlinx.coroutines.delay(2000) // Show error for 2 seconds
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
     * Stage 2: Discover devices on the network.
     */
    private suspend fun discoverDevices(timeoutMs: Long): List<UpnpDevice> = withContext(Dispatchers.IO) {
        Timber.d("Stage 2: Discovering devices (timeout: ${timeoutMs}ms)")
        updateNotification("Discovering devices...", BlastPhase.DISCOVERING)
        broadcastStageUpdate(BlastStage.DISCOVERING)

        blastMetrics.startDiscoveryTiming()

        val devices = mutableListOf<UpnpDevice>()

        discoveryBus.discoverAll(timeoutMs)
            .flowOn(Dispatchers.IO)
            .catch { e ->
                Timber.e(e, "Discovery error")
            }
            .collect { device ->
                devices.add(device)
                Timber.d("Discovered device: ${device.friendlyName}")
                updateNotification("Found ${devices.size} devices...", BlastPhase.DISCOVERING)

                // Broadcast device discovery
                broadcastDeviceUpdate(device, DeviceStatus.DISCOVERED)
            }

        blastMetrics.completeDiscovery(devices.size)
        broadcastMetricsUpdate()
        Timber.i("Discovery complete: ${devices.size} devices found")

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
            val duration = (System.currentTimeMillis() - startTime).toInt()
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
        sendBroadcast(intent)

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
        sendBroadcast(intent)
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
        sendBroadcast(intent)
        Timber.d("BlastService: Broadcasted stage update: $stage")
    }

    /**
     * Broadcast metrics updates to UI components.
     */
    private fun broadcastMetricsUpdate() {
        val metrics = blastMetrics.currentMetrics.value
        val intent = Intent("com.wobbz.fartloop.BLAST_METRICS_UPDATE").apply {
            putExtra("httpStartupMs", metrics.httpStartupMs.toLong())
            putExtra("discoveryTimeMs", metrics.discoveryDurationMs.toLong())
            putExtra("devicesFound", metrics.devicesDiscovered)
            putExtra("successfulBlasts", metrics.successfulDevices)
            putExtra("failedBlasts", metrics.failedDevices)
            putExtra("isRunning", !metrics.isComplete)
        }
        sendBroadcast(intent)
        Timber.d("BlastService: Broadcasted metrics update")
    }

    /**
     * Broadcast device updates to UI components.
     */
    private fun broadcastDeviceUpdate(device: UpnpDevice, status: DeviceStatus) {
        val intent = Intent("com.wobbz.fartloop.BLAST_DEVICE_UPDATE").apply {
            putExtra("deviceId", "${device.ipAddress}:${device.port}")
            putExtra("deviceName", device.friendlyName)
            putExtra("deviceType", mapDeviceType(device).name)
            putExtra("ipAddress", device.ipAddress)
            putExtra("port", device.port)
            putExtra("status", status.name)
        }
        sendBroadcast(intent)
        Timber.d("BlastService: Broadcasted device update: ${device.friendlyName} -> $status")
    }

    /**
     * Map UpnpDevice to DeviceType for UI display.
     */
    private fun mapDeviceType(device: UpnpDevice): DeviceType {
        return when {
            device.friendlyName.contains("Sonos", ignoreCase = true) -> DeviceType.SONOS
            device.friendlyName.contains("Chromecast", ignoreCase = true) -> DeviceType.CHROMECAST
            device.friendlyName.contains("Samsung", ignoreCase = true) -> DeviceType.SAMSUNG
            device.port == 1400 -> DeviceType.SONOS
            device.port == 8008 || device.port == 8009 -> DeviceType.CHROMECAST
            else -> DeviceType.UPNP
        }
    }
}
