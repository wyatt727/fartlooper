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
import com.wobbz.fartloop.feature.home.model.*
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
    onFabClick: () -> Unit,
    onDismiss: () -> Unit,
    fabSize: Dp = 80.dp,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current

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

    // Background color transition from FAB to sheet
    val containerColor by animateColorAsState(
        targetValue = if (isExpanded) {
            MaterialTheme.colorScheme.surface
        } else {
            MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(
            durationMillis = 300,
            easing = FastOutSlowInEasing
        ),
        label = "ContainerColor"
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
 */
@Composable
private fun MotionAwareFab(
    blastStage: BlastStage,
    onClick: () -> Unit,
    scale: Float,
    alpha: Float,
    modifier: Modifier = Modifier
) {
    // Icon changes based on blast stage for visual continuity
    val fabIcon = when (blastStage) {
        BlastStage.IDLE -> Icons.Default.PlayArrow
        BlastStage.HTTP_STARTING -> Icons.Default.CloudUpload
        BlastStage.DISCOVERING -> Icons.Default.Search
        BlastStage.BLASTING -> Icons.Default.Speaker
        BlastStage.COMPLETING -> Icons.Default.Check
        BlastStage.COMPLETED -> Icons.Default.CheckCircle
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
            contentDescription = when (blastStage) {
                BlastStage.IDLE -> "Start audio blast"
                BlastStage.HTTP_STARTING -> "Starting server"
                BlastStage.DISCOVERING -> "Finding devices"
                BlastStage.BLASTING -> "Blasting audio"
                BlastStage.COMPLETING -> "Completing blast"
                BlastStage.COMPLETED -> "Blast complete"
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
 */
@Composable
fun BlastMotionController(
    blastStage: BlastStage,
    metrics: BlastMetrics,
    devices: List<DiscoveredDevice>,
    onStartBlast: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Motion state derived from blast stage
    val isExpanded = blastStage != BlastStage.IDLE
    val canDismiss = blastStage in listOf(BlastStage.COMPLETED, BlastStage.COMPLETING)

    var isDismissing by remember { mutableStateOf(false) }

    // Auto-dismiss after completion with delay
    LaunchedEffect(blastStage) {
        if (blastStage == BlastStage.COMPLETED && !isDismissing) {
            kotlinx.coroutines.delay(3000) // Show success for 3 seconds
            isDismissing = true
        }
    }

    BlastFabMotion(
        blastStage = blastStage,
        metrics = metrics,
        devices = devices,
        isExpanded = isExpanded && !isDismissing,
        onFabClick = {
            if (blastStage == BlastStage.IDLE) {
                onStartBlast()
                Timber.d("BlastMotionController triggering blast start")
            }
        },
        onDismiss = {
            if (canDismiss) {
                isDismissing = true
                Timber.d("BlastMotionController handling dismiss")
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
