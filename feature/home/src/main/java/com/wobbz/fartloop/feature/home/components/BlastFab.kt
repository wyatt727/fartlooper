package com.wobbz.fartloop.feature.home.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.wobbz.fartloop.design.AccessibilityUtils
import com.wobbz.fartloop.design.accessibleClickable
import com.wobbz.fartloop.design.theme.*
import com.wobbz.fartloop.feature.home.model.BlastStage
import timber.log.Timber

/**
 * Large BLAST FAB with animated states
 *
 * Features:
 * - Large size with custom shape from design system
 * - Color and icon changes based on blast stage
 * - Scale animation for active states
 * - Foundation for motion spec transformation (Task B-3)
 * - Custom text styling for button text
 *
 * @param blastStage Current blast stage to determine appearance
 * @param onBlastClick Callback when FAB is clicked to start blast
 * @param modifier Optional modifier for the FAB
 */
@Composable
fun BlastFab(
    blastStage: BlastStage,
    onBlastClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Determine FAB properties based on blast stage
    val (icon, text, containerColor, contentColor) = when (blastStage) {
        BlastStage.IDLE -> FabState(
            icon = Icons.Default.PlayArrow,
            text = "BLAST!",
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
        BlastStage.HTTP_STARTING -> FabState(
            icon = Icons.Default.CloudUpload,
            text = "Starting...",
            containerColor = MetricColors.Warning,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
        BlastStage.DISCOVERING -> FabState(
            icon = Icons.Default.Search,
            text = "Discovering...",
            containerColor = MetricColors.Info,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
        BlastStage.BLASTING -> FabState(
            icon = Icons.Default.PlayCircle,
            text = "Blasting...",
            containerColor = MetricColors.Success,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
        BlastStage.COMPLETING -> FabState(
            icon = Icons.Default.Check,
            text = "Finishing...",
            containerColor = MetricColors.Success,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
        BlastStage.COMPLETED -> FabState(
            icon = Icons.Default.CheckCircle,
            text = "Complete",
            containerColor = MetricColors.Success,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    }

    // Animate FAB scale for "pulse" effect during active stages
    val fabScale by animateFloatAsState(
        targetValue = when (blastStage) {
            BlastStage.IDLE -> 1.0f
            BlastStage.HTTP_STARTING, BlastStage.DISCOVERING, BlastStage.BLASTING -> 1.1f
            BlastStage.COMPLETING, BlastStage.COMPLETED -> 1.0f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "FabScale"
    )

    // Animate container color transitions
    val animatedContainerColor by animateColorAsState(
        targetValue = containerColor,
        animationSpec = tween(durationMillis = 300),
        label = "ContainerColor"
    )

    // Animate content color transitions
    val animatedContentColor by animateColorAsState(
        targetValue = contentColor,
        animationSpec = tween(durationMillis = 300),
        label = "ContentColor"
    )

        // ACCESSIBILITY ENHANCEMENT: Enhanced BlastFab with haptic feedback and screen reader support
    // Combines visual state with audio descriptions and touch feedback for comprehensive accessibility
    val isEnabled = blastStage == BlastStage.IDLE || blastStage == BlastStage.COMPLETED
    val accessibilityLabel = when (blastStage) {
        BlastStage.IDLE -> "Start audio blast to all devices"
        BlastStage.HTTP_STARTING -> "Starting HTTP server"
        BlastStage.DISCOVERING -> "Discovering network devices"
        BlastStage.BLASTING -> "Blasting audio to devices"
        BlastStage.COMPLETING -> "Completing blast sequence"
        BlastStage.COMPLETED -> "Blast completed, tap to start new blast"
    }

    ExtendedFloatingActionButton(
        onClick = {
            if (isEnabled) {
                onBlastClick()
                Timber.d("BlastFab clicked - starting blast sequence (stage: $blastStage)")
            } else {
                Timber.d("BlastFab clicked - blast already in progress (${blastStage})")
            }
        },
        modifier = modifier
            .scale(fabScale)
            .accessibleClickable(
                onClick = { /* Handled by FAB onClick */ },
                onClickLabel = accessibilityLabel,
                hapticPattern = when (blastStage) {
                    BlastStage.IDLE, BlastStage.COMPLETED -> AccessibilityUtils.HapticPattern.SUCCESS
                    else -> AccessibilityUtils.HapticPattern.WARNING
                },
                customRoleDescription = AccessibilityUtils.CustomRole.BLAST_BUTTON.description,
                enabled = isEnabled,
                hapticEnabled = false // FAB will handle its own haptic feedback
            ),
        shape = FartLooperCustomShapes.blastFab,
        containerColor = animatedContainerColor,
        contentColor = animatedContentColor,
        elevation = FloatingActionButtonDefaults.elevation(
            defaultElevation = 8.dp,
            pressedElevation = 12.dp
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Animated icon with rotation for active states
            val iconRotation by animateFloatAsState(
                targetValue = if (blastStage in listOf(BlastStage.HTTP_STARTING, BlastStage.DISCOVERING)) 360f else 0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 2000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "IconRotation"
            )

            Icon(
                imageVector = icon,
                contentDescription = "Blast action: $text",
                modifier = Modifier
                    .size(28.dp)
                    .then(
                        if (blastStage in listOf(BlastStage.HTTP_STARTING, BlastStage.DISCOVERING)) {
                            Modifier
                        } else {
                            Modifier
                        }
                    )
            )

            // Button text with custom styling
            Text(
                text = text,
                style = FartLooperTextStyles.blastButton,
                color = animatedContentColor
            )
        }
    }

    // Log stage changes for debugging
    LaunchedEffect(blastStage) {
        Timber.d("BlastFab stage changed to: $blastStage")
    }
}

/**
 * Data class to hold FAB state properties
 */
private data class FabState(
    val icon: ImageVector,
    val text: String,
    val containerColor: androidx.compose.ui.graphics.Color,
    val contentColor: androidx.compose.ui.graphics.Color
)

/**
 * Preview for BlastFab in different stages
 */
@Preview(name = "Blast FAB States")
@Composable
private fun BlastFabPreview() {
    FartLooperThemePreview {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            BlastStage.values().forEach { stage ->
                BlastFab(
                    blastStage = stage,
                    onBlastClick = { }
                )
            }
        }
    }
}
