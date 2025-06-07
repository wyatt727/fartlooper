package com.wobbz.fartloop.feature.home.model

import com.wobbz.fartloop.core.blast.*

/**
 * Main UI state for HomeScreen
 * Consolidates all state needed for the home feature
 */
data class HomeUiState(
    val devices: List<DiscoveredDevice> = emptyList(),
    val metrics: BlastMetrics = BlastMetrics(),
    val blastStage: BlastStage = BlastStage.IDLE,
    val isMetricsExpanded: Boolean = false,  // MetricsOverlay expansion state
    val errorMessage: String? = null,        // Error display
    val isLoading: Boolean = false           // General loading state
) {
    /**
     * Convenience properties for UI logic
     */
    val isBlastActive: Boolean get() = blastStage != BlastStage.IDLE && blastStage != BlastStage.COMPLETED
    val hasDevices: Boolean get() = devices.isNotEmpty()
    val activeDeviceCount: Int get() = devices.count { it.status in listOf(DeviceStatus.CONNECTING, DeviceStatus.BLASTING, DeviceStatus.SUCCESS) }
}
