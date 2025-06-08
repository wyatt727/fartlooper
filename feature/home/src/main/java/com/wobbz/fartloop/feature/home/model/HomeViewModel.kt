package com.wobbz.fartloop.feature.home.model

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wobbz.fartloop.core.blast.BlastService
import com.wobbz.fartloop.core.blast.*
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
            when (intent?.action) {
                "com.wobbz.fartloop.BLAST_STAGE_UPDATE" -> {
                    val stage = intent.getStringExtra("stage")?.let { stageName ->
                        BlastStage.values().find { it.name == stageName }
                    } ?: BlastStage.IDLE
                    updateBlastStage(stage)
                    Timber.d("HomeViewModel: Received stage update: $stage")
                }
                "com.wobbz.fartloop.BLAST_METRICS_UPDATE" -> {
                    // Extract metrics from intent and update UI
                    val httpStartupMs = intent.getLongExtra("httpStartupMs", 0L)
                    val discoveryTimeMs = intent.getLongExtra("discoveryTimeMs", 0L)
                    val devicesFound = intent.getIntExtra("devicesFound", 0)
                    val successfulBlasts = intent.getIntExtra("successfulBlasts", 0)
                    val failedBlasts = intent.getIntExtra("failedBlasts", 0)

                    val metrics = BlastMetrics(
                        httpStartupMs = httpStartupMs,
                        discoveryTimeMs = discoveryTimeMs,
                        totalDevicesFound = devicesFound,
                        successfulBlasts = successfulBlasts,
                        failedBlasts = failedBlasts,
                        isRunning = intent.getBooleanExtra("isRunning", false)
                    )
                    updateMetrics(metrics)
                    Timber.d("HomeViewModel: Received metrics update: $metrics")
                }
                "com.wobbz.fartloop.BLAST_DEVICE_UPDATE" -> {
                    val deviceId = intent.getStringExtra("deviceId") ?: return
                    val deviceName = intent.getStringExtra("deviceName") ?: return
                    val deviceType = intent.getStringExtra("deviceType")?.let { typeName ->
                        DeviceType.values().find { it.name == typeName }
                    } ?: DeviceType.UNKNOWN
                    val ipAddress = intent.getStringExtra("ipAddress") ?: return
                    val port = intent.getIntExtra("port", 0)
                    val status = intent.getStringExtra("status")?.let { statusName ->
                        DeviceStatus.values().find { it.name == statusName }
                    } ?: DeviceStatus.DISCOVERED

                    val device = DiscoveredDevice(
                        id = deviceId,
                        name = deviceName,
                        type = deviceType,
                        ipAddress = ipAddress,
                        port = port,
                        status = status
                    )
                    updateDevice(device)
                    Timber.d("HomeViewModel: Received device update: $device")
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
                    updateBlastStage(BlastStage.COMPLETED)
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

        // ANDROID 14+ FIX: Specify RECEIVER_NOT_EXPORTED for internal app broadcasts
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(blastReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(blastReceiver, filter)
        }
        Timber.d("HomeViewModel: Broadcast receiver registered for BlastService updates")
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

                // Clear existing devices and show discovering state
                _uiState.value = _uiState.value.copy(
                    devices = emptyList(),
                    blastStage = BlastStage.DISCOVERING,
                    errorMessage = null
                )

                // Start discovery-only operation
                BlastService.discoverDevices(
                    context = context,
                    discoveryTimeoutMs = 4000L // 4 second discovery timeout
                )

                Timber.i("HomeViewModel: Device discovery started")

            } catch (e: Exception) {
                Timber.e(e, "HomeViewModel: Error starting device discovery")
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
     * Handle device selection for navigation compatibility.
     */
    fun onDeviceSelected(device: DiscoveredDevice) {
        Timber.d("HomeViewModel: Device selected: ${device.name} (${device.id})")
        // TODO: Handle device selection logic
    }

    /**
     * Toggle metrics expansion for navigation compatibility.
     */
    fun toggleMetricsExpansion() {
        toggleMetricsOverlay()
    }

    /**
     * Update or add a device to the device list.
     */
    private fun updateDevice(device: DiscoveredDevice) {
        val currentDevices = _uiState.value.devices.toMutableList()
        val existingIndex = currentDevices.indexOfFirst { it.id == device.id }

        if (existingIndex >= 0) {
            currentDevices[existingIndex] = device
        } else {
            currentDevices.add(device)
        }

        _uiState.value = _uiState.value.copy(devices = currentDevices)
    }

    override fun onCleared() {
        super.onCleared()
        try {
            context.unregisterReceiver(blastReceiver)
            Timber.d("HomeViewModel: Broadcast receiver unregistered")
        } catch (e: Exception) {
            Timber.w(e, "HomeViewModel: Error unregistering broadcast receiver")
        }
    }
}
