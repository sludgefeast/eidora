# TFLite
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.gpu.** { *; }
-keep class org.tensorflow.lite.gpu.GpuDelegate { *; }
-keep class org.tensorflow.lite.gpu.GpuDelegate$Options { *; }
-keep class org.tensorflow.lite.gpu.GpuDelegateFactory { *; }
-keep class org.tensorflow.lite.gpu.GpuDelegateFactory$Options { *; }
-keep class org.tensorflow.lite.gpu.GpuDelegateFactory$Options$GpuBackend { *; }
-dontwarn org.tensorflow.lite.gpu.**
-keepclassmembers class * {
    @org.tensorflow.lite.annotations.UsedByReflection *;
}

# Ashampoo XMP Core
-keep class com.ashampoo.** { *; }

# ML Kit
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *

# Application classes (referenced from AndroidManifest.xml)
-keep class de.sebastian.eidora.EidoraApplication { *; }
-keep class de.sebastian.eidora.MainActivity { *; }

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class de.sebastian.eidora.**$$serializer { *; }
-keepclassmembers class de.sebastian.eidora.** {
    *** Companion;
}
-keepclasseswithmembers class de.sebastian.eidora.** {
    kotlinx.serialization.KSerializer serializer(...);
}
