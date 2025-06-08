package com.wobbz.fartloop.feature.home.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.wobbz.fartloop.design.theme.*
import com.wobbz.fartloop.core.blast.*
import timber.log.Timber

/**
 * FAB to Bottom Sheet Motion Transformation Component
 *
 * Implementation Notes:
 * - Follows Material Motion spec for container transform
 * - FAB expands both horizontally and vertically into bottom sheet
 * - Icon morphs with alpha/scale transitions during expansion
 * - Background color transitions from FAB to sheet surface
 * - Z-index coordination ensures proper layering during animation
 *
 * Motion Spec Research Findings:
 * 1. Container Transform Duration: 300ms with FastOutSlowIn easing
 * 2. FAB Scale: 1.0 → 0.0 (icon) then 1.0 → sheet scale (container)
 * 3. Alpha Crossfade: FAB content fades out as sheet content fades in
 * 4. Shape Morph: Circle (FAB) → RoundedRectangle (bottom sheet)
 * 5. Color Transition: Primary → Surface with elevation
 *
 * @param blastStage Current blast pipeline stage
 * @param isExpanded Whether the FAB should be expanded to bottom sheet
 * @param isMinimized Whether the FAB should be minimized
 * @param onFabClick Callback when FAB is clicked to start blast
 * @param onDismiss Callback when bottom sheet should be dismissed
 * @param fabSize Size of the FAB when collapsed
 * @param modifier Optional modifier for the motion container
 */
@Composable
fun BlastFabMotion(
    blastStage: BlastStage,
    metrics: BlastMetrics,
    devices: List<DiscoveredDevice>,
    isExpanded: Boolean,
    isMinimized: Boolean = false,
    onFabClick: () -> Unit,
    onStopClick: () -> Unit = {},
    onDiscoverClick: () -> Unit = {},
    onDismiss: () -> Unit,
    fabSize: Dp = 80.dp,
    modifier: Modifier = Modifier
) {
    // Container transform animations with Material Motion specs
    val containerScale by animateFloatAsState(
        targetValue = if (isExpanded) 1f else 0.2f,
        animationSpec = tween(
            durationMillis = 300,
            easing = FastOutSlowInEasing
        ),
        label = "ContainerScale"
    )

    val containerAlpha by animateFloatAsState(
        targetValue = if (isExpanded) 1f else 0f,
        animationSpec = tween(
            durationMillis = 250,
            delayMillis = if (isExpanded) 50 else 0,
            easing = LinearEasing
        ),
        label = "ContainerAlpha"
    )

    val fabScale by animateFloatAsState(
        targetValue = if (isExpanded) 0f else 1f,
        animationSpec = tween(
            durationMillis = 200,
            easing = FastOutSlowInEasing
        ),
        label = "FabScale"
    )

    val fabAlpha by animateFloatAsState(
        targetValue = if (isExpanded) 0f else 1f,
        animationSpec = tween(
            durationMillis = 150,
            easing = LinearEasing
        ),
        label = "FabAlpha"
    )

    // Log motion state changes for debugging
    LaunchedEffect(isExpanded, containerScale, fabScale) {
        Timber.d("BlastFabMotion state: expanded=$isExpanded, containerScale=$containerScale, fabScale=$fabScale")
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .zIndex(if (isExpanded) 10f else 1f),
        contentAlignment = Alignment.BottomEnd
    ) {
        // FAB Container (visible when not expanded)
        AnimatedVisibility(
            visible = !isExpanded,
            enter = scaleIn(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            ) + fadeIn(),
            exit = scaleOut(
                animationSpec = tween(durationMillis = 200)
            ) + fadeOut(),
            modifier = Modifier.padding(16.dp)
        ) {
            MotionAwareFab(
                blastStage = blastStage,
                isMinimized = isMinimized,
                onClick = {
                    onFabClick()
                    Timber.d("MotionAwareFab clicked, triggering motion expansion")
                },
                scale = fabScale,
                alpha = fabAlpha,
                modifier = Modifier.size(fabSize)
            )
        }

        // Bottom Sheet Container (visible when expanded)
        AnimatedVisibility(
            visible = isExpanded,
            enter = slideInVertically(
                initialOffsetY = { fullHeight -> fullHeight },
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            ) + fadeIn(
                animationSpec = tween(
                    durationMillis = 300,
                    delayMillis = 100
                )
            ),
            exit = slideOutVertically(
                targetOffsetY = { fullHeight -> fullHeight },
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            ) + fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 32.dp)
                    .graphicsLayer {
                        scaleX = containerScale
                        scaleY = containerScale
                        alpha = containerAlpha
                    }
            ) {
                BlastProgressBottomSheet(
                    blastStage = blastStage,
                    metrics = metrics,
                    devices = devices,
                    onStopClick = onStopClick,
                    onDiscoverClick = onDiscoverClick,
                    onDismiss = {
                        onDismiss()
                        Timber.d("BlastProgressBottomSheet dismissed, triggering motion collapse")
                    }
                )
            }
        }
    }
}

/**
 * Motion-aware FAB that coordinates with the expansion animation
 *
 * Design Finding: FAB state needs to reflect blast progression for smooth handoff
 * The icon and color change based on blast stage to provide visual continuity
 *
 * MINIMIZED STATE FIX: Show different icon when minimized to indicate hidden bottom sheet
 */
@Composable
private fun MotionAwareFab(
    blastStage: BlastStage,
    isMinimized: Boolean = false,
    onClick: () -> Unit,
    scale: Float,
    alpha: Float,
    modifier: Modifier = Modifier
) {
    // Icon changes based on blast stage for visual continuity
    val fabIcon = when {
        isMinimized && blastStage != BlastStage.IDLE -> Icons.Default.ExpandLess // Show expand icon when minimized
        blastStage == BlastStage.IDLE -> Icons.Default.PlayArrow
        blastStage == BlastStage.HTTP_STARTING -> Icons.Default.CloudUpload
        blastStage == BlastStage.DISCOVERING -> Icons.Default.Search
        blastStage == BlastStage.BLASTING -> Icons.Default.Speaker
        blastStage == BlastStage.COMPLETING -> Icons.Default.Check
        blastStage == BlastStage.COMPLETED -> Icons.Default.CheckCircle
        else -> Icons.Default.PlayArrow
    }

    // Color changes to match stage progression
    val fabColor = when (blastStage) {
        BlastStage.IDLE -> MaterialTheme.colorScheme.primary
        BlastStage.HTTP_STARTING -> MaterialTheme.colorScheme.tertiary
        BlastStage.DISCOVERING -> MaterialTheme.colorScheme.secondary
        BlastStage.BLASTING -> MaterialTheme.colorScheme.primary
        BlastStage.COMPLETING -> MetricColors.Warning
        BlastStage.COMPLETED -> MetricColors.Success
    }

    FloatingActionButton(
        onClick = onClick,
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            },
        containerColor = fabColor,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = CircleShape
    ) {
        Icon(
            imageVector = fabIcon,
            contentDescription = when {
                isMinimized -> "Show progress"
                blastStage == BlastStage.IDLE -> "Start audio blast"
                blastStage == BlastStage.HTTP_STARTING -> "Starting server"
                blastStage == BlastStage.DISCOVERING -> "Finding devices"
                blastStage == BlastStage.BLASTING -> "Blasting audio"
                blastStage == BlastStage.COMPLETING -> "Completing blast"
                blastStage == BlastStage.COMPLETED -> "Blast complete"
                else -> "Audio blast"
            },
            modifier = Modifier.size(32.dp)
        )
    }
}

/**
 * Coordinated Motion Controller for FAB ↔ Bottom Sheet transitions
 *
 * Architecture Finding: Centralized state management prevents animation conflicts
 * This component manages the complex interaction between FAB visibility,
 * bottom sheet expansion, and motion timing coordination
 *
 * MINIMIZE FIX: Separated minimize (temporary hide) from dismiss (permanent close) logic
 * - Minimize works during active stages (DISCOVERING, BLASTING, etc.)
 * - Dismiss only works when operation is complete or can be safely cancelled
 */
@Composable
fun BlastMotionController(
    blastStage: BlastStage,
    metrics: BlastMetrics,
    devices: List<DiscoveredDevice>,
    onStartBlast: () -> Unit,
    onStopBlast: () -> Unit = {},
    onDiscoverDevices: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Motion state derived from blast stage
    val isExpanded = blastStage != BlastStage.IDLE
    val canDismiss = blastStage in listOf(BlastStage.COMPLETED, BlastStage.COMPLETING)
    val canMinimize = blastStage in listOf(BlastStage.HTTP_STARTING, BlastStage.DISCOVERING, BlastStage.BLASTING)

    var isDismissing by remember { mutableStateOf(false) }
    var isMinimized by remember { mutableStateOf(false) }

    // Auto-dismiss after completion with delay
    LaunchedEffect(blastStage) {
        if (blastStage == BlastStage.COMPLETED && !isDismissing) {
            kotlinx.coroutines.delay(3000) // Show success for 3 seconds
            isDismissing = true
        }
    }

    // Reset minimize state when operation completes or starts new operation
    LaunchedEffect(blastStage) {
        if (blastStage == BlastStage.IDLE || blastStage == BlastStage.COMPLETED) {
            isMinimized = false
        }
    }

    BlastFabMotion(
        blastStage = blastStage,
        metrics = metrics,
        devices = devices,
        isExpanded = isExpanded && !isDismissing && !isMinimized,
        isMinimized = isMinimized,
        onFabClick = {
            if (blastStage == BlastStage.IDLE) {
                onStartBlast()
                Timber.d("BlastMotionController triggering blast start")
            } else if (isMinimized) {
                // Un-minimize by showing the bottom sheet again
                isMinimized = false
                Timber.d("BlastMotionController un-minimizing bottom sheet")
            }
        },
        onStopClick = {
            if (blastStage != BlastStage.IDLE && blastStage != BlastStage.COMPLETED) {
                onStopBlast()
                Timber.d("BlastMotionController triggering blast stop")
            }
        },
        onDiscoverClick = {
            if (blastStage == BlastStage.IDLE) {
                onDiscoverDevices()
                Timber.d("BlastMotionController triggering device discovery")
            }
        },
        onDismiss = {
            if (canDismiss) {
                // Permanent dismissal for completed operations
                isDismissing = true
                Timber.d("BlastMotionController handling permanent dismiss")
            } else if (canMinimize) {
                // Temporary minimize for active operations
                isMinimized = true
                Timber.d("BlastMotionController handling minimize (temporary hide)")
            }
        },
        modifier = modifier
    )

    // Reset dismissing state when blast returns to idle
    LaunchedEffect(blastStage) {
        if (blastStage == BlastStage.IDLE) {
            isDismissing = false
        }
    }
}

/**
 * Preview showing the motion transformation in different states
 */
@Preview(name = "FAB Motion - Collapsed", showBackground = true)
@Composable
private fun BlastFabMotionCollapsedPreview() {
    FartLooperThemePreview {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.BottomEnd
        ) {
            BlastFabMotion(
                blastStage = BlastStage.IDLE,
                metrics = BlastMetrics(),
                devices = emptyList(),
                isExpanded = false,
                onFabClick = { },
                onDismiss = { }
            )
        }
    }
}

@Preview(name = "FAB Motion - Expanded", showBackground = true)
@Composable
private fun BlastFabMotionExpandedPreview() {
    FartLooperThemePreview {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            BlastFabMotion(
                blastStage = BlastStage.DISCOVERING,
                metrics = BlastMetrics(
                    httpStartupMs = 35L,
                    discoveryTimeMs = 1200L,
                    totalDevicesFound = 2,
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
                    )
                ),
                isExpanded = true,
                onFabClick = { },
                onDismiss = { }
            )
        }
    }
}
