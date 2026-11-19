# Loom Groovebox ProGuard Rules
# Keep JNI native methods and their containing classes

# Keep the NativeLib class and all its members
-keep class com.groovebox.NativeLib { *; }

# Keep all native method declarations
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Kotlin coroutine support
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Keep Compose-related classes
-keep class androidx.compose.** { *; }

# Keep R8 from stripping data classes used across JNI
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# Project files and factory presets use Java serialization. JNI also reads
# StepState by class and field name when loading sequence chains.
-keep class com.groovebox.** implements java.io.Serializable { *; }
-keep class com.groovebox.persistence.** { *; }

# Prevent obfuscation of classes accessed via reflection
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses

# Keep kotlinx.serialization generated serializers for portable presets
-keep class com.groovebox.presets.** { *; }
-keepclassmembers class com.groovebox.presets.** { *; }
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
