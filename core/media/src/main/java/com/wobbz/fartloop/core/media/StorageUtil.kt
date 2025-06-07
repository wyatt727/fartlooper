package com.wobbz.fartloop.core.media

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages media storage and access for the Fart-Looper app.
 * Responsibilities:
 * - Copy picked files to cache directory (current.mp3)
 * - Load default audio from assets (fart.mp3)
 * - Validate remote URLs via HEAD requests
 * - Provide Flow<MediaSource> for current media
 * - Auto-trim cache to prevent disk space issues
 */
@Singleton
class StorageUtil @Inject constructor(
    private val context: Context
) {
    private val cacheDir = context.cacheDir.resolve("audio").apply {
        mkdirs()
        Timber.d("Initialized audio cache directory: $absolutePath")
    }

    private val currentFile = cacheDir.resolve("current.mp3")
    private val defaultAssetFile = "fart.mp3" // Asset file name

    // Flow for current media source - UI can observe this
    private val _currentMediaSource = MutableStateFlow<MediaSource?>(null)
    val currentMediaSource: Flow<MediaSource?> = _currentMediaSource.asStateFlow()

    init {
        // Load default asset on initialization
        ensureDefaultAssetLoaded()
    }

    /**
     * Ensure the default fart.mp3 from assets is copied to cache directory.
     * This runs on initialization to provide a default clip out of the box.
     *
     * Asset Loading Finding: Copying assets to cache directory on first run
     * ensures we always have a stable file path for HTTP server serving
     * and eliminates need for special asset handling in HTTP server code.
     */
    private fun ensureDefaultAssetLoaded() {
        try {
            // Only copy if current file doesn't exist or if we want to refresh default
            if (!currentFile.exists()) {
                Timber.d("Loading default asset: $defaultAssetFile")

                context.assets.open(defaultAssetFile).use { inputStream ->
                    FileOutputStream(currentFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }

                Timber.i("Default asset loaded: ${currentFile.absolutePath} (${currentFile.length()} bytes)")

                // Set as current media source
                _currentMediaSource.value = MediaSource.Local(currentFile)
            } else {
                // Current file exists, assume it's the active selection
                _currentMediaSource.value = MediaSource.Local(currentFile)
                Timber.d("Using existing current file: ${currentFile.absolutePath}")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load default asset: $defaultAssetFile")
            // Continue without default - user can still pick files
        }
    }

    /**
     * Reset to default fart.mp3 from assets.
     * Useful for "reset to default" functionality in UI.
     */
    suspend fun loadDefaultAsset(): Result<MediaSource.Local> = withContext(Dispatchers.IO) {
        try {
            Timber.d("Manually loading default asset: $defaultAssetFile")

            context.assets.open(defaultAssetFile).use { inputStream ->
                FileOutputStream(currentFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            Timber.i("Default asset reloaded: ${currentFile.absolutePath} (${currentFile.length()} bytes)")

            val mediaSource = MediaSource.Local(currentFile)
            _currentMediaSource.value = mediaSource

            Result.success(mediaSource)
        } catch (e: Exception) {
            Timber.e(e, "Failed to reload default asset: $defaultAssetFile")
            Result.failure(e)
        }
    }

    /**
     * Copy a picked file (via SAF) to the cache directory as current.mp3.
     * This ensures we have a stable file path for the HTTP server.
     *
     * @param uri Content URI from file picker
     * @return MediaSource.Local pointing to copied file
     */
    suspend fun copyLocalFile(uri: Uri): Result<MediaSource.Local> = withContext(Dispatchers.IO) {
        try {
            Timber.d("Copying local file from URI: $uri")

            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(currentFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: return@withContext Result.failure(IllegalStateException("Could not open input stream for URI: $uri"))

            Timber.i("Successfully copied file to: ${currentFile.absolutePath} (${currentFile.length()} bytes)")

            val mediaSource = MediaSource.Local(currentFile)
            _currentMediaSource.value = mediaSource

            // Auto-trim cache if needed (keep only current file)
            trimCache()

            Result.success(mediaSource)
        } catch (e: Exception) {
            Timber.e(e, "Failed to copy local file from URI: $uri")
            Result.failure(e)
        }
    }

    /**
     * Validate a remote URL by performing a HEAD request.
     * Checks if the URL is accessible and returns audio content.
     *
     * @param url Remote audio URL
     * @return MediaSource.Remote if valid
     */
    suspend fun validateRemoteUrl(url: String): Result<MediaSource.Remote> = withContext(Dispatchers.IO) {
        try {
            Timber.d("Validating remote URL: $url")

            // Basic URL validation first
            val mediaSource = MediaSource.Remote(url)

            // Perform HEAD request to validate accessibility
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            val responseCode = connection.responseCode
            val contentType = connection.contentType

            Timber.d("HEAD response: $responseCode, Content-Type: $contentType")

            if (responseCode !in 200..299) {
                return@withContext Result.failure(
                    IllegalStateException("Remote URL returned HTTP $responseCode: $url")
                )
            }

            // Log content type but don't strictly validate - some servers don't set proper MIME types
            if (contentType != null && !contentType.startsWith("audio/") && !contentType.startsWith("application/octet-stream")) {
                Timber.w("Remote URL may not be audio content (Content-Type: $contentType): $url")
            }

            _currentMediaSource.value = mediaSource
            Timber.i("Successfully validated remote URL: $url")

            Result.success(mediaSource)
        } catch (e: Exception) {
            Timber.e(e, "Failed to validate remote URL: $url")
            Result.failure(e)
        }
    }

    /**
     * Get the current media source synchronously.
     * Used by HTTP server to determine what to serve.
     */
    fun getCurrentMediaSource(): MediaSource? = _currentMediaSource.value

    /**
     * Clear current media source and clean up cache.
     */
    suspend fun clearMediaSource() = withContext(Dispatchers.IO) {
        try {
            _currentMediaSource.value = null

            // Clean up cache files
            if (currentFile.exists()) {
                currentFile.delete()
                Timber.d("Cleared current media file")
            }

            trimCache()
        } catch (e: Exception) {
            Timber.e(e, "Error clearing media source")
        }
    }

    /**
     * Get cache statistics for debugging.
     */
    fun getCacheStats(): String {
        val files = cacheDir.listFiles() ?: emptyArray()
        val totalSize = files.sumOf { it.length() }
        return "Files: ${files.size}, Total: ${totalSize / 1024}KB"
    }

    /**
     * Get last modified time for cache monitoring.
     */
    fun getLastModified(): String {
        return if (currentFile.exists()) {
            java.time.Instant.ofEpochMilli(currentFile.lastModified()).toString()
        } else {
            "No current file"
        }
    }

    /**
     * Clean up old cache files, keeping only the current file.
     *
     * Cache Management Finding: Simple strategy works best for this use case
     * Keep only current.mp3, delete everything else to prevent cache bloat
     * More sophisticated LRU not needed since we only have one active file at a time
     */
    private fun trimCache() {
        try {
            val files = cacheDir.listFiles() ?: return

            files.filter { it.name != "current.mp3" }
                .forEach { file ->
                    if (file.delete()) {
                        Timber.d("Deleted old cache file: ${file.name}")
                    }
                }
        } catch (e: Exception) {
            Timber.e(e, "Error trimming cache")
        }
    }
}
