# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.qimingnan.couple.** {
    *** Companion;
}
-keepclasseswithmembers class com.qimingnan.couple.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Retrofit
-keepattributes Signature, Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
# Paho MQTT
-keep class org.eclipse.paho.client.mqttv3.** { *; }
-dontwarn org.eclipse.paho.**
# BouncyCastle: only the lightweight Ed25519 API is used, by direct (non-reflective) calls, so R8 can trace it.
# (The old blanket -keep of org.bouncycastle.crypto.** pinned several MB of unused ciphers into the dex.)
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
# kotlinx.serialization: keep generated serializers of @Serializable classes (library ships rules too; this is belt & braces)
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-keepclassmembers @kotlinx.serialization.Serializable class com.qimingnan.couple.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
# Retrofit service interfaces are only reached through Proxy; keep their annotated methods
-keepclassmembers,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
# Hilt / Dagger and ML Kit ship consumer rules; silence optional references only
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.mlkit.**
# Shizuku: binder/AIDL stubs are looked up by descriptor and ShizukuProvider is manifest-declared
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.server.** { *; }
-dontwarn rikka.**
# Paho loads its NLS message bundle and logger by name (kept by the package keep above)
# Room / Hilt / WorkManager / OkHttp / Compose / kotlinx.serialization ship their own consumer rules.
# Keep Hilt-injected Workers' constructors (instantiated via HiltWorkerFactory's assisted factories)
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
# Keep line numbers for readable crash stack traces (mapping.txt is uploaded as a CI artifact)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
