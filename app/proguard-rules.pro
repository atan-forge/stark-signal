-keep class com.atan.starkaudio.transcription.nativebridge.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
# JNI uses stable method names. Do not let R8 rename the bridge or callback.
-keep class com.atan.starkaudio.transcription.WhisperNativeBridge { *; }
-keep class com.atan.starkaudio.transcription.NativeProgressCallback { *; }
