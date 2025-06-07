package com.wobbz.fartloop.feature.library.model

import android.net.Uri
import androidx.compose.runtime.Stable
import java.io.File

/**
 * UI state for the Library screen managing media source selection
 *
 * Architecture Finding: Separate sealed classes for media sources enable type-safe handling
 * This prevents mixing local file paths with remote URLs and provides clear state boundaries
 * between different media source types that require different handling mechanisms
 */
@Stable
data class LibraryUiState(
    val currentMediaSource: MediaSource? = null,
    val availableClips: List<ClipItem> = emptyList(),
    val isLoading: Boolean = false,
    val isUrlDialogVisible: Boolean = false,
    val urlInputText: String = "",
    val isUrlValid: Boolean = false,
    val errorMessage: String? = null,
    val isAnalyzingClip: Boolean = false,
    val waveformData: WaveformData? = null,
    val urlValidationResult: UrlValidationResult? = null  // URL validation state for dialog
) {
    /**
     * Computed properties for UI logic
     *
     * Performance Finding: These computed properties prevent redundant calculations
     * in Compose recomposition cycles by deriving state instead of storing it
     */
    val hasSelection: Boolean get() = currentMediaSource != null
    val hasLocalClips: Boolean get() = availableClips.any { it.source is MediaSource.Local }
    val hasError: Boolean get() = errorMessage != null
    val canAnalyzeWaveform: Boolean get() = currentMediaSource is MediaSource.Local && !isAnalyzingClip
}

/**
 * Sealed class representing different types of media sources
 *
 * Design Finding: Sealed classes provide exhaustive when statements and type safety
 * This prevents runtime errors when handling different media source types throughout the app
 */
sealed interface MediaSource {
    /**
     * Local file media source with caching support
     *
     * Implementation Note: File objects are used instead of Uris for local sources
     * because we need direct file access for waveform analysis and caching operations
     */
    data class Local(
        val file: File,
        val originalUri: Uri? = null, // Keep original SAF URI for metadata
        val displayName: String,
        val sizeBytes: Long,
        val mimeType: String? = null
    ) : MediaSource {
        val isAudioFile: Boolean
            get() = mimeType?.startsWith("audio/") == true ||
                    file.extension.lowercase() in SUPPORTED_AUDIO_EXTENSIONS
    }

    /**
     * Remote stream URL source with validation
     *
     * URL Validation Finding: HEAD requests are used to validate streams without downloading
     * This prevents wasted bandwidth while ensuring the URL is accessible and returns audio content
     */
    data class Remote(
        val url: String,
        val displayName: String = url.substringAfterLast('/'),
        val contentType: String? = null,
        val contentLength: Long? = null,
        val isValidated: Boolean = false
    ) : MediaSource {
        val isStreamUrl: Boolean
            get() = url.startsWith("http://") || url.startsWith("https://")
    }

    companion object {
        /**
         * Supported audio file extensions for local files
         *
         * Format Selection Finding: Focus on common formats to prevent edge case issues
         * MP3, MP4, M4A cover most user content; FLAC/WAV for high quality; OGG for open source
         */
        val SUPPORTED_AUDIO_EXTENSIONS = setOf(
            "mp3", "mp4", "m4a", "aac",
            "flac", "wav", "ogg", "opus"
        )
    }
}

/**
 * Individual clip item for library display
 *
 * UI Finding: Separate ClipItem from MediaSource allows UI-specific metadata
 * like thumbnails, durations, and last-played timestamps without polluting core data models
 */
@Stable
data class ClipItem(
    val id: String,
    val source: MediaSource,
    val duration: Long? = null, // Duration in milliseconds
    val addedAt: Long = System.currentTimeMillis(),
    val lastPlayedAt: Long? = null,
    val thumbnailPath: String? = null, // Path to generated waveform thumbnail
    val isCurrentSelection: Boolean = false
) {
    val displayName: String
        get() = when (source) {
            is MediaSource.Local -> source.displayName
            is MediaSource.Remote -> source.displayName
        }

    val sourceType: String
        get() = when (source) {
            is MediaSource.Local -> "Local File"
            is MediaSource.Remote -> "Stream URL"
        }

    /**
     * Human-readable file size for local files
     *
     * UX Finding: File size display helps users understand storage impact
     * Especially important for large FLAC files that might impact performance
     */
    val displaySize: String?
        get() = when (source) {
            is MediaSource.Local -> formatFileSize(source.sizeBytes)
            is MediaSource.Remote -> source.contentLength?.let { formatFileSize(it) }
        }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }
}

/**
 * Waveform visualization data for audio clips
 *
 * Audio Analysis Finding: Downsampled waveform data enables smooth UI performance
 * Full audio analysis would be too expensive for real-time UI, so we pre-process
 * into drawable amplitude arrays suitable for Compose Canvas operations
 */
@Stable
data class WaveformData(
    val amplitudes: List<Float>, // Normalized 0.0-1.0 amplitude values
    val sampleRate: Int,
    val durationMs: Long,
    val peakAmplitude: Float,
    val averageAmplitude: Float
) {
    /**
     * Computed properties for waveform rendering
     *
     * Rendering Finding: Pre-computed properties improve Canvas draw performance
     * These calculations happen once during data creation rather than every frame
     */
    val normalizedAmplitudes: List<Float>
        get() = if (peakAmplitude > 0) {
            amplitudes.map { it / peakAmplitude }
        } else {
            amplitudes
        }

    val samplesPerSecond: Float
        get() = amplitudes.size / (durationMs / 1000f)

    companion object {
        /**
         * Create empty waveform data for loading states
         */
        fun empty(): WaveformData = WaveformData(
            amplitudes = emptyList(),
            sampleRate = 0,
            durationMs = 0L,
            peakAmplitude = 0f,
            averageAmplitude = 0f
        )

        /**
         * Create mock waveform data for previews
         *
         * Preview Finding: Realistic mock data improves design validation
         * Using sine wave patterns creates recognizable waveform shapes for UI testing
         *
         * MATH API FIXING: kotlin.math.random() doesn't exist, use kotlin.random.Random
         * kotlin.math.abs() requires specific type, provide Float explicitly
         */
        fun mock(): WaveformData {
            val amplitudes = (0 until 100).map { i ->
                kotlin.math.sin(i * 0.1).toFloat() * 0.8f + kotlin.random.Random.nextFloat() * 0.2f
            }.map { kotlin.math.abs(it) }

            return WaveformData(
                amplitudes = amplitudes,
                sampleRate = 44100,
                durationMs = 30000L,
                peakAmplitude = amplitudes.maxOrNull() ?: 1f,
                averageAmplitude = amplitudes.average().toFloat()
            )
        }
    }
}

/**
 * URL validation result for stream sources
 *
 * Network Finding: Structured validation results enable better error handling
 * Different failure types (network, format, access) require different user messaging
 */
sealed interface UrlValidationResult {
    data object Loading : UrlValidationResult
    data class Valid(
        val contentType: String?,
        val contentLength: Long?,
        val responseCode: Int
    ) : UrlValidationResult

    data class Invalid(
        val reason: String,
        val httpCode: Int? = null
    ) : UrlValidationResult
}

/**
 * File picker result for handling SAF responses
 *
 * SAF Integration Finding: Wrapper class simplifies error handling across components
 * Storage Access Framework has complex failure modes that need unified handling
 */
sealed interface FilePickerResult {
    data class Success(val uri: Uri) : FilePickerResult
    data class Cancelled(val reason: String = "User cancelled") : FilePickerResult
    data class Error(val exception: Throwable) : FilePickerResult
}
