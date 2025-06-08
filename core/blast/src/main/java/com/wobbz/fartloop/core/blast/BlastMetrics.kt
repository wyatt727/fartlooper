package com.wobbz.fartloop.core.blast

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks real-time metrics during blast operations.
 *
 * METRICS COLLECTION FINDING: Provides live feedback for UI progress indicators
 * and post-blast analytics. StateFlow enables reactive UI updates while maintaining
 * thread-safe access to metrics data across service and UI components.
 */
@Singleton
class BlastMetricsCollector @Inject constructor() {

    private val _currentMetrics = MutableStateFlow(MetricsSnapshot())
    val currentMetrics: StateFlow<MetricsSnapshot> = _currentMetrics.asStateFlow()

    // Performance timing tracking
    private var httpStartTime: Long = 0
    private var discoveryStartTime: Long = 0
    private var blastStartTime: Long = 0

    /**
     * Reset all metrics for a new blast operation.
     */
    fun resetForNewBlast() {
        _currentMetrics.value = MetricsSnapshot()
        httpStartTime = 0
        discoveryStartTime = 0
        blastStartTime = 0
    }

    /**
     * Mark the start of HTTP server startup.
     */
    fun startHttpTiming() {
        httpStartTime = System.currentTimeMillis()
    }

    /**
     * Mark HTTP server as ready and record startup time.
     */
    fun completeHttpStartup() {
        if (httpStartTime > 0) {
            val duration = System.currentTimeMillis() - httpStartTime
            _currentMetrics.value = _currentMetrics.value.copy(
                httpStartupMs = duration.toInt(),
            )
        }
    }

    /**
     * Mark the start of device discovery.
     */
    fun startDiscoveryTiming() {
        discoveryStartTime = System.currentTimeMillis()
    }

    /**
     * Mark discovery as complete and record duration.
     */
    fun completeDiscovery(devicesFound: Int) {
        if (discoveryStartTime > 0) {
            val duration = System.currentTimeMillis() - discoveryStartTime
            _currentMetrics.value = _currentMetrics.value.copy(
                discoveryDurationMs = duration.toInt(),
                devicesDiscovered = devicesFound,
            )
        }
    }

    /**
     * Mark the start of the actual blast phase.
     */
    fun startBlastTiming() {
        blastStartTime = System.currentTimeMillis()
    }

    /**
     * Record a successful device blast.
     */
    fun recordDeviceSuccess(deviceName: String, durationMs: Int) {
        val current = _currentMetrics.value
        _currentMetrics.value = current.copy(
            successfulDevices = current.successfulDevices + 1,
            deviceResults = current.deviceResults + (deviceName to DeviceResult.Success(durationMs)),
        )
    }

    /**
     * Record a failed device blast.
     */
    fun recordDeviceFailure(deviceName: String, error: String) {
        val current = _currentMetrics.value
        _currentMetrics.value = current.copy(
            failedDevices = current.failedDevices + 1,
            deviceResults = current.deviceResults + (deviceName to DeviceResult.Failure(error)),
        )
    }

    /**
     * Mark the entire blast operation as complete.
     */
    fun completeBlast() {
        if (blastStartTime > 0) {
            val duration = System.currentTimeMillis() - blastStartTime
            _currentMetrics.value = _currentMetrics.value.copy(
                totalBlastDurationMs = duration.toInt(),
                isComplete = true,
            )
        }
    }

    /**
     * Get current success rate as a percentage.
     */
    fun getSuccessRate(): Float {
        val current = _currentMetrics.value
        val total = current.successfulDevices + current.failedDevices
        return if (total > 0) {
            (current.successfulDevices.toFloat() / total.toFloat()) * 100f
        } else {
            0f
        }
    }

    /**
     * Get average SOAP response time for successful devices.
     */
    fun getAverageSoapTime(): Int {
        val current = _currentMetrics.value
        val successTimes = current.deviceResults.values
            .filterIsInstance<DeviceResult.Success>()
            .map { it.durationMs }

        return if (successTimes.isNotEmpty()) {
            successTimes.average().toInt()
        } else {
            0
        }
    }

    /**
     * Update current metrics directly for real-time progress tracking.
     *
     * REAL-TIME PROGRESS FIX: Allows direct metric updates during discovery
     * for immediate UI feedback without waiting for stage completion.
     */
    fun updateCurrentMetrics(metrics: MetricsSnapshot) {
        _currentMetrics.value = metrics
    }
}

/**
 * Immutable snapshot of blast metrics at a point in time.
 */
data class MetricsSnapshot(
    val httpStartupMs: Int = 0,
    val discoveryDurationMs: Int = 0,
    val totalBlastDurationMs: Int = 0,
    val devicesDiscovered: Int = 0,
    val successfulDevices: Int = 0,
    val failedDevices: Int = 0,
    val deviceResults: Map<String, DeviceResult> = emptyMap(),
    val isComplete: Boolean = false,
) {
    val totalDevicesTargeted: Int get() = successfulDevices + failedDevices
    val successRate: Float get() = if (totalDevicesTargeted > 0) {
        (successfulDevices.toFloat() / totalDevicesTargeted.toFloat()) * 100f
    } else {
        0f
    }
}

/**
 * Result of attempting to blast a specific device.
 */
sealed class DeviceResult {
    data class Success(val durationMs: Int) : DeviceResult()
    data class Failure(val error: String) : DeviceResult()
}


