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
# BouncyCastle
-keep class org.bouncycastle.crypto.** { *; }
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
