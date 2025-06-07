package com.wobbz.fartloop.core.media

import java.io.File

/**
 * Represents a media source that can be served by the HTTP server.
 * - Local: File stored in app cache directory
 * - Remote: URL to be proxied through the server
 */
sealed interface MediaSource {
    /**
     * Local media file stored in cache directory.
     * File should already exist and be accessible.
     */
    data class Local(val file: File) : MediaSource {
        init {
            require(file.exists()) { "Local media file must exist: ${file.absolutePath}" }
            require(file.canRead()) { "Local media file must be readable: ${file.absolutePath}" }
        }
    }
    
    /**
     * Remote media URL to be proxied.
     * URL should be accessible and return audio content.
     */
    data class Remote(val url: String) : MediaSource {
        init {
            require(url.isNotBlank()) { "Remote URL cannot be blank" }
            require(url.startsWith("http://") || url.startsWith("https://")) { 
                "Remote URL must be HTTP or HTTPS: $url" 
            }
        }
    }
} 