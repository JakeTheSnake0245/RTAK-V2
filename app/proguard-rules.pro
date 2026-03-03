# Chaquopy — don't obfuscate Python bridge classes
-keep class com.caai.rtak.RTAKCallback { *; }
-keep class com.caai.rtak.ReticulumBridge { *; }

# Keep Chaquopy runtime
-keep class com.chaquo.python.** { *; }
