package com.wobbz.fartloop.design.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Primary colors - Fart-Looper brand colors as specified in PDR
val FartRed = Color(0xFFF44336)
val FartOrange = Color(0xFFFFAB91)

// Light color scheme
val LightColorScheme = lightColorScheme(
    primary = FartRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD6),
    onPrimaryContainer = Color(0xFF410002),
    
    secondary = FartOrange,
    onSecondary = Color(0xFF442C2E),
    secondaryContainer = Color(0xFFFFD8DB),
    onSecondaryContainer = Color(0xFF2B1518),
    
    tertiary = Color(0xFF776655),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFEDDB),
    onTertiaryContainer = Color(0xFF2B1B0C),
    
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onError = Color.White,
    onErrorContainer = Color(0xFF410002),
    
    background = Color(0xFFFFFBFF),
    onBackground = Color(0xFF201A1A),
    surface = Color(0xFFFFFBFF),
    onSurface = Color(0xFF201A1A),
    surfaceVariant = Color(0xFFF5DDDD),
    onSurfaceVariant = Color(0xFF534344),
    
    outline = Color(0xFF857374),
    inverseOnSurface = Color(0xFFFBEEEE),
    inverseSurface = Color(0xFF362F2F),
    inversePrimary = Color(0xFFFFB4AB),
)

// Dark color scheme
val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFFFB4AB),
    onPrimary = Color(0xFF690005),
    primaryContainer = Color(0xFF93000A),
    onPrimaryContainer = Color(0xFFFFDAD6),
    
    secondary = Color(0xFFE7BDC1),
    onSecondary = Color(0xFF442C2E),
    secondaryContainer = Color(0xFF5D4044),
    onSecondaryContainer = Color(0xFFFFD8DB),
    
    tertiary = Color(0xFFD2C2A0),
    onTertiary = Color(0xFF423220),
    tertiaryContainer = Color(0xFF5A4935),
    onTertiaryContainer = Color(0xFFFFEDDB),
    
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onError = Color(0xFF690005),
    onErrorContainer = Color(0xFFFFDAD6),
    
    background = Color(0xFF201A1A),
    onBackground = Color(0xFFF0C0C7),
    surface = Color(0xFF201A1A),
    onSurface = Color(0xFFF0C0C7),
    surfaceVariant = Color(0xFF534344),
    onSurfaceVariant = Color(0xFFD8C2C4),
    
    outline = Color(0xFFA08C8D),
    inverseOnSurface = Color(0xFF201A1A),
    inverseSurface = Color(0xFFF0C0C7),
    inversePrimary = FartRed,
)

// Status and metric colors for the HUD and device chips
object MetricColors {
    val Success = Color(0xFF4CAF50)  // Green for successful devices
    val Error = Color(0xFFF44336)    // Red for failed devices  
    val Warning = Color(0xFFFF9800)  // Orange for pending/timeout
    val Info = Color(0xFF2196F3)     // Blue for info states
    val Neutral = Color(0xFF9E9E9E)  // Gray for unknown/idle states
}

// Device chip colors to indicate connection status
object DeviceChipColors {
    val Connected = MetricColors.Success
    val Connecting = MetricColors.Warning  
    val Failed = MetricColors.Error
    val Discovered = MetricColors.Info
    val Idle = MetricColors.Neutral
} 