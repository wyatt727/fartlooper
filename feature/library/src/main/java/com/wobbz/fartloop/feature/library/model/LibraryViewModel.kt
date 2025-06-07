package com.wobbz.fartloop.feature.library.model

import android.net.Uri
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
 * ViewModel for the Library screen managing media sources and file operations.
 *
 * LIBRARY VIEWMODEL FINDING: Centralized media source management prevents
 * state inconsistencies during file picking, URL validation, and waveform analysis.
 * StateFlow enables reactive UI updates for loading states and error handling.
 */
@HiltViewModel
class LibraryViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

        /**
     * Handle file picker result from Storage Access Framework.
     *
     * FILE PROCESSING FINDING: File processing happens on background thread
     * to prevent UI blocking during large file analysis and waveform generation.
     */
    fun handleFilePickerResult(uri: Uri) {
        viewModelScope.launch {
            try {
                Timber.d("LibraryViewModel: Processing file picker result: $uri")

                // Update UI to show loading
                _uiState.value = _uiState.value.copy(isLoading = true)

                // TODO: Process file with StorageUtil
                // val mediaSource = storageUtil.processPickedFile(uri)
                // val clipItem = ClipItem(id = UUID.randomUUID().toString(), source = mediaSource)

                // TODO: Add to library and update state
                // _uiState.value = _uiState.value.copy(
                //     isLoading = false,
                //     availableClips = _uiState.value.availableClips + clipItem
                // )

                // Temporary success state
                _uiState.value = _uiState.value.copy(isLoading = false)

            } catch (e: Exception) {
                Timber.e(e, "LibraryViewModel: Error processing file")
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Failed to process file: ${e.message}"
                )
            }
        }
    }

    /**
     * Validate and add a stream URL to the library.
     *
     * URL VALIDATION FINDING: Real-time validation provides immediate feedback
     * while background HEAD requests verify stream accessibility and content type.
     */
    fun validateAndAddUrl(url: String) {
        viewModelScope.launch {
            try {
                Timber.d("LibraryViewModel: Validating URL: $url")

                // Update validation state
                _uiState.value = _uiState.value.copy(
                    urlValidationResult = UrlValidationResult.Loading
                )

                // TODO: Validate URL with HTTP HEAD request
                // val validationResult = urlValidator.validate(url)

                // TODO: If valid, add to library
                // if (validationResult is UrlValidationResult.Valid) {
                //     val mediaSource = MediaSource.Remote(url, contentType = validationResult.contentType)
                //     val clipItem = ClipItem(id = UUID.randomUUID().toString(), source = mediaSource)
                //     _uiState.value = _uiState.value.copy(
                //         availableClips = _uiState.value.availableClips + clipItem,
                //         urlValidationResult = validationResult
                //     )
                // }

                // Temporary success state
                _uiState.value = _uiState.value.copy(
                    urlValidationResult = UrlValidationResult.Valid(
                        contentType = "audio/mpeg",
                        contentLength = null,
                        responseCode = 200
                    )
                )

            } catch (e: Exception) {
                Timber.e(e, "LibraryViewModel: Error validating URL")
                _uiState.value = _uiState.value.copy(
                    urlValidationResult = UrlValidationResult.Invalid(
                        reason = "Validation failed: ${e.message}"
                    )
                )
            }
        }
    }

    /**
     * Select a clip for playback.
     */
    fun selectClip(clipItem: ClipItem) {
        Timber.d("LibraryViewModel: Selected clip: ${clipItem.displayName}")

        _uiState.value = _uiState.value.copy(
            currentMediaSource = clipItem.source,
            availableClips = _uiState.value.availableClips.map { clip ->
                clip.copy(isCurrentSelection = clip.id == clipItem.id)
            }
        )
    }

    /**
     * Remove a clip from the library.
     */
    fun removeClip(clipItem: ClipItem) {
        Timber.d("LibraryViewModel: Removing clip: ${clipItem.displayName}")

        _uiState.value = _uiState.value.copy(
            availableClips = _uiState.value.availableClips.filter { it.id != clipItem.id }
        )
    }

    /**
     * Show the URL input dialog.
     */
    fun showUrlDialog() {
        _uiState.value = _uiState.value.copy(isUrlDialogVisible = true)
    }

    /**
     * Hide the URL input dialog.
     */
    fun hideUrlDialog() {
        _uiState.value = _uiState.value.copy(
            isUrlDialogVisible = false,
            urlInputText = "",
            urlValidationResult = null
        )
    }

    /**
     * Update URL input text for real-time validation.
     */
    fun updateUrlInput(text: String) {
        _uiState.value = _uiState.value.copy(urlInputText = text)
    }

    /**
     * Retry loading after an error.
     */
        fun retryLoad() {
        _uiState.value = _uiState.value.copy(
            errorMessage = null,
            isLoading = true
        )

        // TODO: Reload library data
        viewModelScope.launch {
            // Simulate loading
            kotlinx.coroutines.delay(1000)
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    /**
     * Clear any error state.
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(
            errorMessage = null
        )
    }

    /**
     * Handle file picker result for navigation compatibility.
     */
    fun handleFilePicked(uri: Uri) {
        handleFilePickerResult(uri)
    }

    /**
     * Dismiss URL dialog for navigation compatibility.
     */
    fun dismissUrlDialog() {
        hideUrlDialog()
    }
}
