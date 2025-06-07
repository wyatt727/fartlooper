package com.wobbz.fartloop.core.media

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Unit tests for StorageUtil.
 * Tests file copying, URL validation, cache management, and Flow emissions.
 */
@RunWith(AndroidJUnit4::class)
class StorageUtilTest {

    private lateinit var context: Context
    private lateinit var storageUtil: StorageUtil
    private lateinit var testCacheDir: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        storageUtil = StorageUtil(context)
        testCacheDir = context.cacheDir.resolve("audio")
    }

    @After
    fun cleanup() {
        // Clean up test files
        testCacheDir.listFiles()?.forEach { it.delete() }
    }

    @Test
    fun `MediaSource Local validates file existence`() {
        // Create a test file
        val testFile = testCacheDir.resolve("test.mp3").apply {
            parentFile?.mkdirs()
            writeText("test audio data")
        }

        // Should succeed with existing file
        val localSource = MediaSource.Local(testFile)
        assertEquals(testFile, localSource.file)

        // Should fail with non-existent file
        val nonExistentFile = testCacheDir.resolve("nonexistent.mp3")
        assertThrows(IllegalArgumentException::class.java) {
            MediaSource.Local(nonExistentFile)
        }
    }

    @Test
    fun `MediaSource Remote validates URL format`() {
        // Valid URLs should work
        val httpUrl = MediaSource.Remote("http://example.com/audio.mp3")
        assertEquals("http://example.com/audio.mp3", httpUrl.url)

        val httpsUrl = MediaSource.Remote("https://example.com/audio.mp3")
        assertEquals("https://example.com/audio.mp3", httpsUrl.url)

        // Invalid URLs should fail
        assertThrows(IllegalArgumentException::class.java) {
            MediaSource.Remote("")
        }

        assertThrows(IllegalArgumentException::class.java) {
            MediaSource.Remote("ftp://example.com/audio.mp3")
        }

        assertThrows(IllegalArgumentException::class.java) {
            MediaSource.Remote("not-a-url")
        }
    }

    @Test
    fun `validateRemoteUrl handles valid URLs`() = runTest {
        // Note: This test would ideally use a mock HTTP server
        // For now, testing with well-known URLs that should be accessible

        val validUrl = "https://httpbin.org/status/200"
        val result = storageUtil.validateRemoteUrl(validUrl)

        assertTrue("Valid URL should succeed", result.isSuccess)
        val mediaSource = result.getOrNull()
        assertNotNull(mediaSource)
        assertEquals(validUrl, mediaSource?.url)

        // Check that Flow was updated
        val currentSource = storageUtil.currentMediaSource.first()
        assertTrue(currentSource is MediaSource.Remote)
        assertEquals(validUrl, (currentSource as MediaSource.Remote).url)
    }

    @Test
    fun `validateRemoteUrl handles invalid URLs`() = runTest {
        val invalidUrl = "https://httpbin.org/status/404"
        val result = storageUtil.validateRemoteUrl(invalidUrl)

        assertTrue("Invalid URL should fail", result.isFailure)
        assertNotNull(result.exceptionOrNull())
    }

    @Test
    fun `getCurrentMediaSource returns current value synchronously`() {
        // Initially should be null
        assertNull(storageUtil.getCurrentMediaSource())

        // After setting a source, should return it
        // Note: We can't easily test this without mocking internal state
        // This test structure is here for when we add more functionality
    }

    @Test
    fun `clearMediaSource resets flow`() = runTest {
        // Set a media source first
        val testUrl = "https://example.com/test.mp3"
        try {
            storageUtil.validateRemoteUrl(testUrl)
        } catch (e: Exception) {
            // Ignore network errors for this test
        }

        // Clear the media
        storageUtil.clearMediaSource()

        // Should be null now
        val currentSource = storageUtil.currentMediaSource.first()
        assertNull(currentSource)
    }

    @Test
    fun `getCacheStats returns readable format`() {
        val stats = storageUtil.getCacheStats()

        assertNotNull(stats)
        assertTrue("Stats should contain file count", stats.contains("Files"))
        assertTrue("Stats should contain size info", stats.contains("KB"))
    }

    @Test
    fun `default asset is loaded on initialization`() = runTest {
        // The default asset should be loaded automatically during StorageUtil construction
        val currentSource = storageUtil.getCurrentMediaSource()

        // Should have a local media source by default (the fart.mp3 asset)
        assertTrue("Default asset should be loaded", currentSource is MediaSource.Local)

        val localSource = currentSource as MediaSource.Local
        assertTrue("Default file should exist", localSource.file.exists())
        assertTrue("Default file should have content", localSource.file.length() > 0)
    }

    @Test
    fun `loadDefaultAsset manually reloads default`() = runTest {
        // Load default asset manually
        val result = storageUtil.loadDefaultAsset()

        assertTrue("Loading default should succeed", result.isSuccess)
        val mediaSource = result.getOrNull()
        assertNotNull("Media source should not be null", mediaSource)
        assertTrue("Should be local source", mediaSource is MediaSource.Local)

        // Verify the file exists and has content
        assertTrue("Default file should exist", mediaSource!!.file.exists())
        assertTrue("Default file should have content", mediaSource.file.length() > 0)
    }
}
