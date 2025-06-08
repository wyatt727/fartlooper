package com.wobbz.fartloop.feature.home.model

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.wobbz.fartloop.core.blast.BlastMetrics
import com.wobbz.fartloop.core.blast.BlastService
import com.wobbz.fartloop.core.blast.BlastStage
import com.wobbz.fartloop.core.blast.DeviceStatus
import com.wobbz.fartloop.core.blast.DeviceType
import com.wobbz.fartloop.core.blast.DiscoveredDevice
import com.wobbz.fartloop.core.blast.DiscoveryMethodStats
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * ViewModel for the Home screen managing blast operations and UI state.
 *
 * VIEWMODEL ARCHITECTURE FINDING: Centralized state management for blast operations
 * prevents UI state inconsistencies during complex async workflows.
 * StateFlow provides reactive UI updates while maintaining lifecycle awareness.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    // Broadcast receiver for BlastService updates
    private val blastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.action?.let { action ->
                Timber.d("HomeViewModel: Received broadcast: $action")
                when (action) {
                    "com.wobbz.fartloop.BLAST_STAGE_UPDATE" -> {
                        val stageName = intent.getStringExtra("stage") ?: return
                        val stage = try {
                            BlastStage.valueOf(stageName)
                        } catch (e: IllegalArgumentException) {
                            Timber.w("HomeViewModel: Unknown blast stage: $stageName")
                            return
                        }
                        updateBlastStage(stage)
                        Timber.d("HomeViewModel: Updated blast stage to: $stage")
                    }
                    "com.wobbz.fartloop.BLAST_METRICS_UPDATE" -> {
                        // Extract metrics from intent and update UI
                        val httpStartupMs = intent.getLongExtra("httpStartupMs", 0L)
                        val discoveryTimeMs = intent.getLongExtra("discoveryTimeMs", 0L)
                        val devicesFound = intent.getIntExtra("devicesFound", 0)
                        val successfulBlasts = intent.getIntExtra("successfulBlasts", 0)
                        val failedBlasts = intent.getIntExtra("failedBlasts", 0)
                        val connectionsAttempted = intent.getIntExtra("connectionsAttempted", 0)
                        val averageLatencyMs = intent.getLongExtra("averageLatencyMs", 0L)

                        // Extract discovery method statistics
                        val ssdpDevicesFound = intent.getIntExtra("ssdpDevicesFound", 0)
                        val mdnsDevicesFound = intent.getIntExtra("mdnsDevicesFound", 0)
                        val portScanDevicesFound = intent.getIntExtra("portScanDevicesFound", 0)
                        val ssdpTimeMs = intent.getLongExtra("ssdpTimeMs", 0L)
                        val mdnsTimeMs = intent.getLongExtra("mdnsTimeMs", 0L)
                        val portScanTimeMs = intent.getLongExtra("portScanTimeMs", 0L)

                        val discoveryMethodStats = DiscoveryMethodStats(
                            ssdpDevicesFound = ssdpDevicesFound,
                            mdnsDevicesFound = mdnsDevicesFound,
                            portScanDevicesFound = portScanDevicesFound,
                            ssdpTimeMs = ssdpTimeMs,
                            mdnsTimeMs = mdnsTimeMs,
                            portScanTimeMs = portScanTimeMs
                        )

                        val updatedMetrics = BlastMetrics(
                            httpStartupMs = httpStartupMs,
                            discoveryTimeMs = discoveryTimeMs,
                            totalDevicesFound = devicesFound,
                            successfulBlasts = successfulBlasts,
                            failedBlasts = failedBlasts,
                            connectionsAttempted = connectionsAttempted,
                            averageLatencyMs = averageLatencyMs,
                            isRunning = intent.getBooleanExtra("isRunning", false),
                            discoveryMethodStats = discoveryMethodStats
                        )

                        updateMetrics(updatedMetrics)
                        Timber.d("HomeViewModel: Updated metrics - devices: $devicesFound, discovery: ${discoveryTimeMs}ms")
                        Timber.d("HomeViewModel: Discovery method breakdown - SSDP: $ssdpDevicesFound, mDNS: $mdnsDevicesFound, PortScan: $portScanDevicesFound")
                    }
                    "com.wobbz.fartloop.BLAST_DEVICE_UPDATE" -> {
                        // Extract device info from intent
                        val deviceId = intent.getStringExtra("deviceId") ?: return
                        val deviceName = intent.getStringExtra("deviceName") ?: "Unknown Device"
                        val deviceType = try {
                            DeviceType.valueOf(intent.getStringExtra("deviceType") ?: "UPNP")
                        } catch (e: IllegalArgumentException) {
                            DeviceType.UPNP
                        }
                        val ipAddress = intent.getStringExtra("ipAddress") ?: ""
                        val port = intent.getIntExtra("port", 0)
                        val controlUrl = intent.getStringExtra("controlUrl") ?: "/AVTransport/control"
                        val status = try {
                            DeviceStatus.valueOf(intent.getStringExtra("status") ?: "DISCOVERED")
                        } catch (e: IllegalArgumentException) {
                            Timber.w("HomeViewModel: Unknown device status: ${intent.getStringExtra("status")}")
                            DeviceStatus.DISCOVERED
                        }

                        // Extract metadata from broadcast
                        val metadataSize = intent.getIntExtra("metadataSize", 0)
                        val metadata = mutableMapOf<String, String>()
                        if (metadataSize > 0) {
                            // Extract all metadata_* keys
                            intent.extras?.keySet()?.forEach { key ->
                                if (key.startsWith("metadata_")) {
                                    val metadataKey = key.removePrefix("metadata_")
                                    val metadataValue = intent.getStringExtra(key)
                                    if (metadataValue != null) {
                                        metadata[metadataKey] = metadataValue
                                    }
                                }
                            }
                        }

                        val device = DiscoveredDevice(
                            id = deviceId,
                            name = deviceName,
                            type = deviceType,
                            ipAddress = ipAddress,
                            port = port,
                            controlUrl = controlUrl,
                            status = status,
                            metadata = metadata
                        )
                        updateDevice(device)
                        Timber.d("HomeViewModel: Updated device: $device")
                    }
                    "com.wobbz.fartloop.BLAST_COMPLETE" -> {
                        updateBlastStage(BlastStage.COMPLETED)
                        Timber.i("HomeViewModel: Blast operation completed")
                    }
                    "com.wobbz.fartloop.BLAST_ERROR" -> {
                        val error = intent.getStringExtra("error") ?: "Unknown error"
                        _uiState.value = _uiState.value.copy(
                            blastStage = BlastStage.IDLE,
                            errorMessage = error
                        )
                        Timber.e("HomeViewModel: Blast error received: $error")

                        // Auto-clear error after 5 seconds
                        viewModelScope.launch {
                            kotlinx.coroutines.delay(5000)
                            clearError()
                        }
                    }
                    "com.wobbz.fartloop.DISCOVERY_COMPLETE" -> {
                        val devicesFound = intent.getIntExtra("devices_found", 0)
                        // DISCOVERY-ONLY FIX: Don't override stage - BlastService will send IDLE stage
                        // This allows users to start blasting to the discovered devices
                        Timber.i("HomeViewModel: Discovery completed - $devicesFound devices found")
                    }
                    "com.wobbz.fartloop.DISCOVERY_ERROR" -> {
                        val error = intent.getStringExtra("error") ?: "Discovery failed"
                        _uiState.value = _uiState.value.copy(
                            blastStage = BlastStage.IDLE,
                            errorMessage = error
                        )
                        Timber.e("HomeViewModel: Discovery error received: $error")

                        // Auto-clear error after 5 seconds
                        viewModelScope.launch {
                            kotlinx.coroutines.delay(5000)
                            clearError()
                        }
                    }
                    "com.wobbz.fartloop.SINGLE_DEVICE_BLAST_COMPLETE" -> {
                        val deviceName = intent.getStringExtra("device_name") ?: "Unknown Device"
                        updateBlastStage(BlastStage.COMPLETED)
                        Timber.i("HomeViewModel: Single device blast to $deviceName completed")
                    }
                    else -> {
                        Timber.d("HomeViewModel: Unhandled broadcast action: $action")
                    }
                }
            }
        }
    }

    init {
        // Register broadcast receiver for BlastService updates
        val filter = IntentFilter().apply {
            addAction("com.wobbz.fartloop.BLAST_STAGE_UPDATE")
            addAction("com.wobbz.fartloop.BLAST_METRICS_UPDATE")
            addAction("com.wobbz.fartloop.BLAST_DEVICE_UPDATE")
            addAction("com.wobbz.fartloop.BLAST_COMPLETE")
            addAction("com.wobbz.fartloop.BLAST_ERROR")
            addAction("com.wobbz.fartloop.DISCOVERY_COMPLETE")
            addAction("com.wobbz.fartloop.DISCOVERY_ERROR")
        }

        // LOCAL BROADCAST FIX: Use LocalBroadcastManager for same-app communication
        // This is more reliable than regular broadcasts and won't be blocked by Android restrictions
        LocalBroadcastManager.getInstance(context).registerReceiver(blastReceiver, filter)
        Timber.d("HomeViewModel: Local broadcast receiver registered for BlastService updates")
    }

    /**
     * Start a blast operation with the current media source.
     *
     * BLAST COORDINATION FINDING: ViewModel coordinates with BlastService
     * through Intent-based communication rather than direct service binding.
     * This maintains loose coupling and survives configuration changes.
     */
    fun startBlast() {
        viewModelScope.launch {
            try {
                Timber.d("HomeViewModel: Starting blast operation")

                // Update UI state to show blast starting
                _uiState.value = _uiState.value.copy(
                    blastStage = BlastStage.HTTP_STARTING
                )

                // Start BlastService with default parameters
                BlastService.startBlast(
                    context = context,
                    discoveryTimeoutMs = 4000L, // 4 second discovery timeout
                    concurrency = 3 // Max 3 concurrent device blasts
                )

                Timber.i("HomeViewModel: BlastService started successfully")

            } catch (e: Exception) {
                Timber.e(e, "HomeViewModel: Error starting blast")
                _uiState.value = _uiState.value.copy(
                    blastStage = BlastStage.IDLE,
                    errorMessage = e.message
                )

                // Auto-clear error after 5 seconds
                viewModelScope.launch {
                    kotlinx.coroutines.delay(5000)
                    clearError()
                }
            }
        }
    }

    /**
     * Stop any ongoing blast operation.
     */
    fun stopBlast() {
        viewModelScope.launch {
            try {
                Timber.d("HomeViewModel: Stopping blast operation")

                // Stop BlastService
                BlastService.stopBlast(context)

                _uiState.value = _uiState.value.copy(
                    blastStage = BlastStage.IDLE
                )

                Timber.i("HomeViewModel: BlastService stopped successfully")

            } catch (e: Exception) {
                Timber.e(e, "HomeViewModel: Error stopping blast")
            }
        }
    }

    /**
     * Discover devices without blasting audio.
     */
    fun discoverDevices() {
        viewModelScope.launch {
            try {
                Timber.d("HomeViewModel: Starting device discovery")
                Timber.d("HomeViewModel: Context available: ${context != null}")
                Timber.d("HomeViewModel: Context class: ${context?.javaClass?.simpleName}")

                // Clear existing devices and show discovering state
                _uiState.value = _uiState.value.copy(
                    devices = emptyList(),
                    blastStage = BlastStage.DISCOVERING,
                    errorMessage = null
                )

                // Validate context before service call
                if (context == null) {
                    throw IllegalStateException("Context is null - cannot start BlastService")
                }

                Timber.i("HomeViewModel: Calling BlastService.discoverDevices() with context: ${context}")

                // Start discovery-only operation
                BlastService.discoverDevices(
                    context = context,
                    discoveryTimeoutMs = 4000L // 4 second discovery timeout
                )

                Timber.i("HomeViewModel: Device discovery started - BlastService.discoverDevices() call completed")

                // Wait a moment to see if service starts
                kotlinx.coroutines.delay(1000)
                Timber.d("HomeViewModel: 1 second after service start request")

            } catch (e: Exception) {
                Timber.e(e, "HomeViewModel: Error starting device discovery")
                _uiState.value = _uiState.value.copy(
                    blastStage = BlastStage.IDLE,
                    errorMessage = "Failed to start discovery: ${e.message}"
                )

                // Auto-clear error after 5 seconds
                viewModelScope.launch {
                    kotlinx.coroutines.delay(5000)
                    clearError()
                }
            }
        }
    }

    /**
     * Blast audio to a single device.
     * Device must already be discovered and available in the device list.
     */
    fun blastToSingleDevice(device: DiscoveredDevice) {
        viewModelScope.launch {
            try {
                Timber.d("HomeViewModel: Starting single device blast to ${device.name}")

                // Update UI state to show blast starting
                _uiState.value = _uiState.value.copy(
                    blastStage = BlastStage.HTTP_STARTING
                )

                // Start single device blast
                BlastService.blastToSingleDevice(
                    context = context,
                    deviceIp = device.ipAddress,
                    devicePort = device.port,
                    deviceName = device.name,
                    deviceControlUrl = device.controlUrl // Use discovered control URL
                )

                Timber.i("HomeViewModel: Single device blast to ${device.name} started successfully")

            } catch (e: Exception) {
                Timber.e(e, "HomeViewModel: Error starting single device blast to ${device.name}")
                _uiState.value = _uiState.value.copy(
                    blastStage = BlastStage.IDLE,
                    errorMessage = "Failed to blast to ${device.name}: ${e.message}"
                )

                // Auto-clear error after 5 seconds
                viewModelScope.launch {
                    kotlinx.coroutines.delay(5000)
                    clearError()
                }
            }
        }
    }

    /**
     * Update blast metrics from service broadcasts.
     *
     * METRICS INTEGRATION FINDING: ViewModel receives metrics updates
     * via broadcast receivers or service callbacks to maintain real-time UI.
     */
    fun updateMetrics(metrics: BlastMetrics) {
        _uiState.value = _uiState.value.copy(metrics = metrics)
    }

    /**
     * Update blast stage from service updates.
     */
    fun updateBlastStage(stage: BlastStage) {
        _uiState.value = _uiState.value.copy(blastStage = stage)
    }

    /**
     * Clear any error messages.
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    /**
     * Toggle metrics overlay visibility.
     */
    fun toggleMetricsOverlay() {
        _uiState.value = _uiState.value.copy(
            isMetricsExpanded = !_uiState.value.isMetricsExpanded
        )
    }

    /**
     * Handle device selection to toggle dropdown expansion.
     */
    fun onDeviceSelected(device: DiscoveredDevice) {
        Timber.d("HomeViewModel: Device selected: ${device.name} (${device.id})")
        // Toggle dropdown expansion instead of immediately blasting
        toggleDeviceDropdown(device.id)
    }

    /**
     * Toggle device dropdown expansion.
     */
    fun toggleDeviceDropdown(deviceId: String) {
        val currentExpandedId = _uiState.value.expandedDeviceId
        _uiState.value = _uiState.value.copy(
            expandedDeviceId = if (currentExpandedId == deviceId) null else deviceId
        )
        Timber.d("HomeViewModel: Device dropdown toggled for $deviceId")
    }

    /**
     * Close all device dropdowns.
     */
    fun closeDeviceDropdowns() {
        _uiState.value = _uiState.value.copy(expandedDeviceId = null)
    }

    /**
     * Toggle metrics expansion for navigation compatibility.
     */
    fun toggleMetricsExpansion() {
        toggleMetricsOverlay()
    }

    /**
     * Update or add a device to the device list.
     *
     * DEVICE COUNT SYNC FIX: Update metrics when devices are updated to keep counts synchronized
     */
    private fun updateDevice(device: DiscoveredDevice) {
        val currentDevices = _uiState.value.devices.toMutableList()
        val existingIndex = currentDevices.indexOfFirst { it.id == device.id }

        if (existingIndex >= 0) {
            currentDevices[existingIndex] = device
        } else {
            currentDevices.add(device)
        }

        // Synchronize metrics with actual device count
        val currentMetrics = _uiState.value.metrics
        val updatedMetrics = currentMetrics.copy(
            totalDevicesFound = currentDevices.size,
            // Also update attempted connections based on device statuses
            connectionsAttempted = currentDevices.count {
                it.status in listOf(DeviceStatus.CONNECTING, DeviceStatus.BLASTING, DeviceStatus.SUCCESS, DeviceStatus.FAILED)
            },
            successfulBlasts = currentDevices.count { it.status == DeviceStatus.SUCCESS },
            failedBlasts = currentDevices.count { it.status == DeviceStatus.FAILED }
        )

        _uiState.value = _uiState.value.copy(
            devices = currentDevices,
            metrics = updatedMetrics
        )

        Timber.d("HomeViewModel: Device updated. Total devices: ${currentDevices.size}, Device: ${device.name} (${device.status})")
    }

    override fun onCleared() {
        super.onCleared()
        // Unregister broadcast receiver to prevent memory leaks
        try {
            LocalBroadcastManager.getInstance(context).unregisterReceiver(blastReceiver)
            Timber.d("HomeViewModel: Local broadcast receiver unregistered")
        } catch (e: Exception) {
            Timber.w(e, "HomeViewModel: Error unregistering broadcast receiver")
        }
    }
}
