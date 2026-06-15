# Terminal Master Hub ProGuard Rules

# Keep Apache Commons Compress and XZ
-keep class org.apache.commons.compress.** { *; }
-keep class org.tukaani.xz.** { *; }
-dontwarn org.tukaani.xz.**

# Keep Gson
-keep class com.google.gson.** { *; }
-keepattributes Signature, *Annotation*

# Keep Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** { *; }

# Keep core classes
-keep class com.terminalmasterhub.core.** { *; }
-keep class com.terminalmasterhub.data.** { *; }
-keep class com.terminalmasterhub.ui.** { *; }

# Keep WebView JS interface
-keepclassmembers class * extends android.webkit.WebView {
   public *;
}

# Keep USB classes
-keep class android.hardware.usb.** { *; }

# General Android
-keep class * extends androidx.fragment.app.Fragment { *; }
-keep class * implements android.os.Parcelable { *; }
-keepclassmembers class * implements android.os.Parcelable {
    static ** CREATOR;
}
