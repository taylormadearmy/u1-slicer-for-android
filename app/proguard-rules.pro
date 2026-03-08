# Add project specific ProGuard rules here.

# Keep JNI-called classes and methods
-keep class com.u1.slicer.NativeLibrary { *; }
-keep class com.u1.slicer.data.ModelInfo { *; }
-keep class com.u1.slicer.data.SliceConfig { *; }
-keep class com.u1.slicer.data.SliceResult { *; }

# Keep Room entities and DAOs
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Suppress warnings for unused/unknown classes from dependencies
-dontwarn java.lang.invoke.**
-dontwarn javax.annotation.**
