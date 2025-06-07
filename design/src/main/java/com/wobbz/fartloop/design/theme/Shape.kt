package com.wobbz.fartloop.design.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val FartLooperShapes = Shapes(
    // Small shapes - for chips, buttons, small components
    small = RoundedCornerShape(8.dp),

    // Medium shapes - for cards and main components (16dp as specified in PDR)
    medium = RoundedCornerShape(16.dp),

    // Large shapes - for dialogs, bottom sheets
    large = RoundedCornerShape(24.dp)
)

// Custom shapes for specific app components
object FartLooperCustomShapes {

    // Device chips - rounded pill shape for better UX
    val deviceChip = RoundedCornerShape(16.dp)

    // Metrics cards - consistent with medium shape
    val metricsCard = RoundedCornerShape(16.dp)

    // Large BLAST FAB - circular by default, transforms for motion
    val blastFab = RoundedCornerShape(28.dp)  // Large FAB standard size

    // Bottom sheet for blast progress - rounded top corners only
    val bottomSheet = RoundedCornerShape(
        topStart = 24.dp,
        topEnd = 24.dp,
        bottomStart = 0.dp,
        bottomEnd = 0.dp
    )

    // Rule builder cards
    val ruleCard = RoundedCornerShape(12.dp)

    // Input fields and text fields
    val inputField = RoundedCornerShape(8.dp)

    // Dialog shapes
    val dialog = RoundedCornerShape(20.dp)

    // Clip thumbnail in library
    val clipThumbnail = RoundedCornerShape(12.dp)

    // Settings section cards
    val settingsCard = RoundedCornerShape(16.dp)
}

// Animation-related shapes for FAB motion spec transformation
object FartLooperMotionShapes {

    // FAB states during transformation to bottom sheet
    val fabNormal = RoundedCornerShape(28.dp)      // Normal circular FAB
    val fabExpanding = RoundedCornerShape(20.dp)   // Mid-transformation
    val fabBottomSheet = RoundedCornerShape(       // Final bottom sheet state
        topStart = 24.dp,
        topEnd = 24.dp,
        bottomStart = 0.dp,
        bottomEnd = 0.dp
    )

    // Progress indicator containers
    val progressContainer = RoundedCornerShape(8.dp)

    // Status chips in the progress sheet
    val statusChip = RoundedCornerShape(12.dp)
}
