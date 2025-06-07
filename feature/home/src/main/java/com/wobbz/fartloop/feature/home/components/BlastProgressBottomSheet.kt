package com.wobbz.fartloop.feature.home.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.wobbz.fartloop.design.theme.*
import com.wobbz.fartloop.core.blast.*
import timber.log.Timber

/**
 * Bottom sheet showing blast pipeline progress with animated stages
 *
 * Implementation Notes:
 * - Transforms from FAB using Material Motion specs
 * - Shows 3 main pipeline stages: HTTP → Discovery → Blasting
 * - Each stage has progress indicators and real-time status
 * - Uses custom shapes from design system for consistent rounded corners
 * - Implements smooth enter/exit animations with spring physics
 *
 * Pipeline stages per PDR Section 7.2:
 * 1. HTTP_STARTING: NanoHTTPD server setup (target: <40ms)
 * 2. DISCOVERING: SSDP + mDNS + port scan (target: ~2100ms)
 * 3. BLASTING: SOAP command execution (parallel, max 3 concurrent)
 *
 * @param blastStage Current stage of the blast pipeline
 * @param metrics Real-time metrics for progress tracking
 * @param devices List of discovered devices with status updates
 * @param onDismiss Callback when bottom sheet should be dismissed
 * @param modifier Optional modifier for the bottom sheet
 */
@Composable
fun BlastProgressBottomSheet(
    blastStage: BlastStage,
    metrics: BlastMetrics,
    devices: List<DiscoveredDevice>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Animation for the bottom sheet appearance
    val sheetProgress by animateFloatAsState(
        targetValue = if (blastStage != BlastStage.IDLE) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "SheetProgress"
    )

    // Show the bottom sheet only when blast is active
    AnimatedVisibility(
        visible = blastStage != BlastStage.IDLE,
        enter = slideInVertically(
            initialOffsetY = { it },
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            )
        ) + fadeIn(),
        exit = slideOutVertically(
            targetOffsetY = { it },
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            )
        ) + fadeOut()
    ) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .clip(FartLooperCustomShapes.bottomSheet),
            shape = FartLooperCustomShapes.bottomSheet,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Header with stage title and dismiss option
                BottomSheetHeader(
                    blastStage = blastStage,
                    onDismiss = onDismiss
                )

                // Pipeline stages with progress indicators
                BlastPipelineStages(
                    currentStage = blastStage,
                    metrics = metrics
                )

                // Device list with real-time status
                if (devices.isNotEmpty()) {
                    DeviceStatusList(
                        devices = devices,
                        currentStage = blastStage
                    )
                }

                // Action buttons
                BottomSheetActions(
                    blastStage = blastStage,
                    onDismiss = onDismiss
                )
            }
        }
    }

    // Log stage changes for debugging motion behavior
    LaunchedEffect(blastStage) {
        Timber.d("BlastProgressBottomSheet stage changed: $blastStage (sheetProgress: $sheetProgress)")
    }
}

/**
 * Header section with current stage title and dismiss button
 *
 * Design Finding: Using large typography for stage visibility during motion
 * The stage title needs to be immediately readable as the FAB transforms
 */
@Composable
private fun BottomSheetHeader(
    blastStage: BlastStage,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = when (blastStage) {
                    BlastStage.HTTP_STARTING -> "Starting HTTP Server"
                    BlastStage.DISCOVERING -> "Discovering Devices"
                    BlastStage.BLASTING -> "Blasting Audio"
                    BlastStage.COMPLETING -> "Finishing Up"
                    BlastStage.COMPLETED -> "Blast Complete"
                    BlastStage.IDLE -> "Ready"
                },
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = getStageDescription(blastStage),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Dismiss button (only show when blast is completed or can be cancelled)
        if (blastStage in listOf(BlastStage.COMPLETED, BlastStage.COMPLETING)) {
            IconButton(
                onClick = {
                    onDismiss()
                    Timber.d("BottomSheet dismissed by user")
                }
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss blast progress",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Pipeline stages with animated progress indicators
 *
 * Implementation Finding: Sequential stage animation with overlap
 * Each stage shows progress while the next stage prepares, creating smooth flow
 * Progress bars use target times from PDR: HTTP<40ms, Discovery~2100ms
 */
@Composable
private fun BlastPipelineStages(
    currentStage: BlastStage,
    metrics: BlastMetrics
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = "Pipeline Progress",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold
        )

        // HTTP Server Stage
        PipelineStageItem(
            icon = Icons.Default.CloudUpload,
            title = "HTTP Server",
            description = "Starting media server",
            isActive = currentStage == BlastStage.HTTP_STARTING,
            isComplete = currentStage.ordinal > BlastStage.HTTP_STARTING.ordinal,
            progress = if (currentStage == BlastStage.HTTP_STARTING) 0.7f else if (currentStage.ordinal > BlastStage.HTTP_STARTING.ordinal) 1f else 0f,
            timeMs = metrics.httpStartupMs,
            targetMs = 40L
        )

        // Discovery Stage
        PipelineStageItem(
            icon = Icons.Default.Search,
            title = "Device Discovery",
            description = "SSDP, mDNS & port scanning",
            isActive = currentStage == BlastStage.DISCOVERING,
            isComplete = currentStage.ordinal > BlastStage.DISCOVERING.ordinal,
            progress = if (currentStage == BlastStage.DISCOVERING) 0.6f else if (currentStage.ordinal > BlastStage.DISCOVERING.ordinal) 1f else 0f,
            timeMs = metrics.discoveryTimeMs,
            targetMs = 2100L
        )

        // Blasting Stage
        PipelineStageItem(
            icon = Icons.Default.PlayCircle,
            title = "Audio Blasting",
            description = "SOAP commands to devices",
            isActive = currentStage == BlastStage.BLASTING,
            isComplete = currentStage.ordinal > BlastStage.BLASTING.ordinal,
            progress = if (currentStage == BlastStage.BLASTING) {
                if (metrics.connectionsAttempted > 0) {
                    (metrics.successfulBlasts + metrics.failedBlasts).toFloat() / metrics.connectionsAttempted.toFloat()
                } else 0.3f
            } else if (currentStage.ordinal > BlastStage.BLASTING.ordinal) 1f else 0f,
            timeMs = metrics.averageLatencyMs,
            targetMs = 500L
        )
    }
}

/**
 * Individual pipeline stage with animated progress indicator
 *
 * Motion Finding: Progress animation uses easing for natural feel
 * The progress bar fills smoothly and the icon changes color as stages complete
 */
@Composable
private fun PipelineStageItem(
    icon: ImageVector,
    title: String,
    description: String,
    isActive: Boolean,
    isComplete: Boolean,
    progress: Float,
    timeMs: Long,
    targetMs: Long
) {
    // Animated progress value for smooth transitions
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(
            durationMillis = 800,
            easing = FastOutSlowInEasing
        ),
        label = "StageProgress"
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Stage icon with status color
        val iconColor = when {
            isComplete -> MetricColors.Success
            isActive -> MetricColors.Info
            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        }

        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = iconColor,
            modifier = Modifier.size(24.dp)
        )

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                )

                // Time indicator
                if (timeMs > 0 || isActive) {
                    Text(
                        text = "${timeMs}ms",
                        style = MaterialTheme.typography.labelMedium,
                        color = when {
                            timeMs <= targetMs -> MetricColors.Success
                            timeMs <= targetMs * 1.5 -> MetricColors.Warning
                            else -> MetricColors.Error
                        }
                    )
                }
            }

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Progress bar
            LinearProgressIndicator(
                progress = animatedProgress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = iconColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}

/**
 * Device status list showing real-time connection states
 *
 * UX Finding: Limit to 5 visible devices to prevent bottom sheet overflow
 * Shows most important devices first (success, then connecting, then failed)
 */
@Composable
private fun DeviceStatusList(
    devices: List<DiscoveredDevice>,
    currentStage: BlastStage
) {
    val visibleDevices = devices
        .sortedWith(compareBy<DiscoveredDevice> { device ->
            when (device.status) {
                DeviceStatus.SUCCESS, DeviceStatus.BLASTING -> 0
                DeviceStatus.CONNECTING -> 1
                DeviceStatus.DISCOVERED -> 2
                DeviceStatus.FAILED -> 3
                else -> 4
            }
        }.thenBy { it.name })
        .take(5) // Limit to prevent UI overflow

    if (visibleDevices.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Device Status (${devices.size} total)",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )

            visibleDevices.forEach { device ->
                DeviceStatusRow(device = device)
            }

            if (devices.size > 5) {
                Text(
                    text = "... and ${devices.size - 5} more devices",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 32.dp)
                )
            }
        }
    }
}

/**
 * Individual device status row with animated status indicator
 */
@Composable
private fun DeviceStatusRow(device: DiscoveredDevice) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status indicator dot with animation
        val statusColor = when (device.status) {
            DeviceStatus.SUCCESS, DeviceStatus.BLASTING -> MetricColors.Success
            DeviceStatus.CONNECTING -> MetricColors.Warning
            DeviceStatus.FAILED -> MetricColors.Error
            else -> MetricColors.Neutral
        }

        Box(
            modifier = Modifier
                .size(8.dp)
                .background(statusColor, RoundedCornerShape(4.dp))
        )

        Text(
            text = device.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )

        Text(
            text = device.status.name.lowercase().replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.labelMedium,
            color = statusColor
        )
    }
}

/**
 * Bottom action buttons for user control
 */
@Composable
private fun BottomSheetActions(
    blastStage: BlastStage,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        when (blastStage) {
            BlastStage.COMPLETED -> {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("Done")
                }
            }
            BlastStage.COMPLETING -> {
                OutlinedButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
            else -> {
                // During active stages, show minimal controls
                TextButton(onClick = onDismiss) {
                    Text("Minimize")
                }
            }
        }
    }
}

/**
 * Get descriptive text for each blast stage
 */
private fun getStageDescription(stage: BlastStage): String {
    return when (stage) {
        BlastStage.HTTP_STARTING -> "Setting up media server on local network"
        BlastStage.DISCOVERING -> "Finding UPnP, Chromecast, and other audio devices"
        BlastStage.BLASTING -> "Sending audio to discovered devices"
        BlastStage.COMPLETING -> "Cleaning up connections and collecting metrics"
        BlastStage.COMPLETED -> "All devices have been sent the audio clip"
        BlastStage.IDLE -> "Ready to blast audio to network devices"
    }
}

/**
 * Preview for BlastProgressBottomSheet in different stages
 */
@Preview(name = "Bottom Sheet - Discovering")
@Composable
private fun BlastProgressBottomSheetPreview() {
    FartLooperThemePreview {
        BlastProgressBottomSheet(
            blastStage = BlastStage.DISCOVERING,
            metrics = BlastMetrics(
                httpStartupMs = 35L,
                discoveryTimeMs = 1500L,
                totalDevicesFound = 3,
                connectionsAttempted = 3,
                successfulBlasts = 0,
                failedBlasts = 0,
                averageLatencyMs = 0L,
                isRunning = true
            ),
            devices = listOf(
                DiscoveredDevice(
                    id = "1",
                    name = "Living Room Sonos",
                    type = DeviceType.SONOS,
                    ipAddress = "192.168.1.100",
                    port = 1400,
                    status = DeviceStatus.DISCOVERED
                ),
                DiscoveredDevice(
                    id = "2",
                    name = "Kitchen Chromecast",
                    type = DeviceType.CHROMECAST,
                    ipAddress = "192.168.1.101",
                    port = 8008,
                    status = DeviceStatus.CONNECTING
                )
            ),
            onDismiss = { }
        )
    }
}
