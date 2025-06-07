package com.wobbz.fartloop.feature.home.model

import android.content.Context
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
