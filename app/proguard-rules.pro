# Terminal Master Hub ProGuard Rules

# Keep Chaquopy Python classes
-keep class com.chaquo.python.** { *; }

# Keep Apache Commons Compress
-keep class org.apache.commons.compress.** { *; }

# Keep Gson
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# Keep Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Keep USB-related classes
-keep class android.hardware.usb.** { *; }

# Keep our own core classes
-keep class com.terminalmasterhub.core.** { *; }
-keep class com.terminalmasterhub.data.** { *; }

# Keep WebView
-keepclassmembers class * extends android.webkit.WebView {
   public *;
}
