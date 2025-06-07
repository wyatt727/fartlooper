# PROGUARD RULES FINDING: Media module requires consumer rules for library distribution
# These rules are applied to consuming modules to ensure proper code obfuscation
# without breaking media processing and playback functionality

# MEDIA PROCESSING FINDING: Keep media codec and format classes public
# Android MediaPlayer and ExoPlayer rely on reflection for codec discovery
-keep class com.wobbz.fartloop.core.media.** { *; }

# AUDIO FORMAT FINDING: Preserve audio format handlers and converters
# Media processing uses format-specific classes that must not be obfuscated
-keep class * implements com.wobbz.fartloop.core.media.AudioFormat { *; }

# MEDIA METADATA FINDING: Keep metadata parser classes for file analysis
# MP3, MP4, and other format parsers use reflection for metadata extraction
-keep class * extends com.wobbz.fartloop.core.media.MetadataParser { *; }

# ASSET MANAGEMENT FINDING: Preserve asset access for bundled media files
# AssetManager integration requires preserved class names for resource loading
-dontwarn com.wobbz.fartloop.core.media.**
