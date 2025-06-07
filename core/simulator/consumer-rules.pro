# PROGUARD RULES FINDING: Simulator module requires consumer rules for library distribution
# These rules are applied to consuming modules to ensure proper code obfuscation
# without breaking simulator functionality and device emulation

# SIMULATOR FINDING: Keep simulator classes public for debugging and testing
# Simulator components need to be accessible for manual testing and verification
-keep class com.wobbz.fartloop.core.simulator.** { *; }

# DEVICE SIMULATION FINDING: Preserve device model classes used in testing
# Simulator uses reflection to create mock devices for testing scenarios
-keep class * implements com.wobbz.fartloop.core.simulator.SimulatedDevice { *; }

# TESTING FRAMEWORK FINDING: Keep test infrastructure for simulator integration
# Unit and integration tests rely on simulator components for reproducible testing
-dontwarn com.wobbz.fartloop.core.simulator.**
