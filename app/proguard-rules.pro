# TFLite
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.gpu.** { *; }
-keepclassmembers class * {
    @org.tensorflow.lite.annotations.UsedByReflection *;
}

# Adobe XMP Core
-keep class com.adobe.xmp.** { *; }

# ML Kit
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class de.sebastian.faces.**$$serializer { *; }
-keepclassmembers class de.sebastian.faces.** {
    *** Companion;
}
-keepclasseswithmembers class de.sebastian.faces.** {
    kotlinx.serialization.KSerializer serializer(...);
}
