package com.wobbz.fartloop.feature.home.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
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
class HomeViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

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

                // TODO: Start BlastService via Intent
                // BlastService.startBlast(context, discoveryTimeoutMs, concurrency)

            } catch (e: Exception) {
                Timber.e(e, "HomeViewModel: Error starting blast")
                _uiState.value = _uiState.value.copy(
                    blastStage = BlastStage.IDLE,
                    errorMessage = e.message
                )
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

                // TODO: Stop BlastService via Intent
                // BlastService.stopBlast(context)

                _uiState.value = _uiState.value.copy(
                    blastStage = BlastStage.IDLE
                )

            } catch (e: Exception) {
                Timber.e(e, "HomeViewModel: Error stopping blast")
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
}
