package com.wobbz.fartloop.feature.library.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wobbz.fartloop.design.theme.*
import com.wobbz.fartloop.feature.library.model.*
import timber.log.Timber
import kotlin.math.roundToInt

/**
 * Clip thumbnail component with waveform visualization
 *
 * Design Philosophy: Visual audio representation improves user understanding
 * Users can quickly identify different clips by their waveform patterns
 * rather than relying solely on filenames or metadata
 *
 * Performance Finding: Canvas drawing is expensive, so waveform data is pre-processed
 * Amplitude arrays are normalized and downsampled to match component width
 * This prevents real-time audio analysis during UI rendering
 */
@Composable
fun ClipThumbnail(
    clipItem: ClipItem,
    isSelected: Boolean = false,
    waveformData: WaveformData? = null,
    onClick: (ClipItem) -> Unit = { },
    onRemove: ((ClipItem) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // Animation for selection state changes
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        },
        animationSpec = tween(200),
        label = "BorderColor"
    )

    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        animationSpec = tween(200),
        label = "BackgroundColor"
    )

    Card(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable {
                onClick(clipItem)
                Timber.d("ClipThumbnail clicked: ${clipItem.displayName}")
            }
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            ),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isSelected) 8.dp else 4.dp
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header with title and remove button
            ClipThumbnailHeader(
                clipItem = clipItem,
                isSelected = isSelected,
                onRemove = onRemove
            )

            // Waveform visualization area
            WaveformVisualization(
                waveformData = waveformData,
                isSelected = isSelected,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
            )

            // Metadata footer
            ClipThumbnailFooter(
                clipItem = clipItem,
                waveformData = waveformData
            )
        }
    }
}

/**
 * Header section with clip title and optional remove button
 *
 * UX Finding: Remove functionality should be easily accessible but not accidental
 * Icon button provides clear target while not dominating the visual hierarchy
 */
@Composable
private fun ClipThumbnailHeader(
    clipItem: ClipItem,
    isSelected: Boolean,
    onRemove: ((ClipItem) -> Unit)?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // Main clip name
            Text(
                text = clipItem.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Source type indicator
            Text(
                text = clipItem.sourceType,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp
            )
        }

        // Remove button (if enabled)
        if (onRemove != null) {
            IconButton(
                onClick = {
                    onRemove(clipItem)
                    Timber.d("ClipThumbnail remove clicked: ${clipItem.displayName}")
                },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove clip",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/**
 * Waveform visualization component using Canvas
 *
 * Rendering Finding: Custom Canvas drawing provides better performance than UI components
 * Drawing amplitude bars directly is more efficient than creating hundreds of Box composables
 * Path-based drawing scales better with complex waveforms than individual shape components
 */
@Composable
private fun WaveformVisualization(
    waveformData: WaveformData?,
    isSelected: Boolean,
    modifier: Modifier = Modifier
) {
    // Animated colors for waveform rendering
    val waveformColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        },
        animationSpec = tween(300),
        label = "WaveformColor"
    )

    val backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)

    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
    ) {
        if (waveformData != null && waveformData.amplitudes.isNotEmpty()) {
            drawWaveform(
                waveformData = waveformData,
                color = waveformColor,
                size = size
            )
        } else {
            // Draw placeholder waveform for loading/empty state
            drawPlaceholderWaveform(
                color = waveformColor.copy(alpha = 0.3f),
                size = size
            )
        }
    }
}

/**
 * Draw actual waveform data using Canvas primitives
 *
 * Drawing Algorithm Finding: Bar-based approach works better than continuous paths
 * Individual bars allow for better visual separation and amplitude representation
 * Logarithmic amplitude scaling makes quiet sections more visible in the UI
 */
private fun DrawScope.drawWaveform(
    waveformData: WaveformData,
    color: Color,
    size: androidx.compose.ui.geometry.Size
) {
    val amplitudes = waveformData.normalizedAmplitudes
    if (amplitudes.isEmpty()) return

    val barWidth = size.width / amplitudes.size
    val centerY = size.height / 2f
    val maxBarHeight = size.height * 0.8f // Leave some padding

    amplitudes.forEachIndexed { index, amplitude ->
        // Logarithmic scaling for better visual representation
        val scaledAmplitude = if (amplitude > 0) {
            kotlin.math.log10(amplitude * 9 + 1) // Maps 0-1 to 0-1 logarithmically
        } else {
            0f
        }

        val barHeight = scaledAmplitude * maxBarHeight
        val x = index * barWidth

        // Draw amplitude bar from center
        drawRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(
                x = x + barWidth * 0.1f, // Small gap between bars
                y = centerY - barHeight / 2f
            ),
            size = androidx.compose.ui.geometry.Size(
                width = barWidth * 0.8f,
                height = barHeight.coerceAtLeast(1.dp.toPx()) // Minimum visible height
            )
        )
    }
}

/**
 * Draw placeholder waveform for loading/empty states
 *
 * Placeholder Design Finding: Subtle animation indicates loading without being distracting
 * Using a simple sine wave pattern provides recognizable waveform appearance
 */
private fun DrawScope.drawPlaceholderWaveform(
    color: Color,
    size: androidx.compose.ui.geometry.Size
) {
    val barCount = 32
    val barWidth = size.width / barCount
    val centerY = size.height / 2f
    val maxBarHeight = size.height * 0.4f

    repeat(barCount) { index ->
        val amplitude = kotlin.math.sin(index * 0.3) * 0.5f + 0.5f // Sine wave pattern
        val barHeight = amplitude * maxBarHeight
        val x = index * barWidth

        drawRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(
                x = x + barWidth * 0.2f,
                y = centerY - barHeight / 2f
            ),
            size = androidx.compose.ui.geometry.Size(
                width = barWidth * 0.6f,
                height = barHeight.coerceAtLeast(2.dp.toPx())
            )
        )
    }
}

/**
 * Footer section with clip metadata
 *
 * Information Architecture Finding: Essential metadata should be immediately visible
 * Duration and file size are most relevant for user decision-making
 */
@Composable
private fun ClipThumbnailFooter(
    clipItem: ClipItem,
    waveformData: WaveformData?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Duration from waveform data or clip metadata
        val duration = waveformData?.durationMs ?: clipItem.duration
        if (duration != null) {
            Text(
                text = formatDuration(duration),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // File size (for local files)
        clipItem.displaySize?.let { size ->
            Text(
                text = size,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Source type icon
        Icon(
            imageVector = when (clipItem.source) {
                is MediaSource.Local -> Icons.Default.Folder
                is MediaSource.Remote -> Icons.Default.Link
            },
            contentDescription = clipItem.sourceType,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
    }
}

/**
 * Format duration from milliseconds to human-readable format
 *
 * Time Display Finding: MM:SS format works best for audio clips under 1 hour
 * Most user clips are short, so minutes and seconds provide sufficient precision
 */
private fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/**
 * Preview for ClipThumbnail in different states
 */
@Preview(name = "Clip Thumbnail - Local File Selected")
@Composable
private fun ClipThumbnailSelectedPreview() {
    FartLooperThemePreview {
        ClipThumbnail(
            clipItem = ClipItem(
                id = "1",
                source = MediaSource.Local(
                    file = java.io.File("/mock/fart.mp3"),
                    displayName = "Epic Fart Sound.mp3",
                    sizeBytes = 2048576, // 2MB
                    mimeType = "audio/mpeg"
                ),
                duration = 45000L // 45 seconds
            ),
            isSelected = true,
            waveformData = WaveformData.mock(),
            onRemove = { }
        )
    }
}
