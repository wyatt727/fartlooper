# PROGUARD RULES FINDING: Design module requires consumer rules for library distribution
# These rules are applied to consuming modules to ensure proper code obfuscation
# without breaking Material Design components and Compose functionality

# COMPOSE FINDING: Keep all @Composable functions to prevent runtime crashes
# Compose runtime relies on reflection for function discovery
-keep @androidx.compose.runtime.Composable class * { *; }

# MATERIAL3 FINDING: Preserve Material Design component state classes
# Material3 components use internal state classes that must not be obfuscated
-keep class androidx.compose.material3.** { *; }

# DESIGN SYSTEM FINDING: Keep our theme and color classes public
# Design module provides theme resources consumed by feature modules
-keep class com.wobbz.fartloop.design.theme.** { *; }

# KOTLIN SERIALIZATION FINDING: If using serialization for design tokens
# Keep serializable classes used for design system configuration
-keepattributes *Annotation*

# COMPOSE COMPILER FINDING: Keep Compose compiler annotations
# Required for proper Compose function compilation and optimization
-keep class androidx.compose.runtime.** { *; }
