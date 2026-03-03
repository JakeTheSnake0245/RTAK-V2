# Chaquopy — don't obfuscate Python bridge classes
-keep class com.rtak.bridge.RTAKCallback { *; }
-keep class com.rtak.bridge.ReticulumBridge { *; }

# Keep Chaquopy runtime
-keep class com.chaquo.python.** { *; }
