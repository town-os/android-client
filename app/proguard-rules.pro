# kotlinx.serialization generates serializers reflectively by name; R8 must not
# rename or strip them or every API response fails to decode in a release build.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.townos.client.api.** {
    *** Companion;
}
-keepclasseswithmembers class com.townos.client.api.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# dnsjava reflects over record classes to build them from the wire. Without this
# a release build silently loses TLSA support.
-keep class org.xbill.DNS.** { *; }
-dontwarn org.xbill.DNS.**

# The WireGuard backend's JNI entry points are called from native code.
-keep class com.wireguard.android.backend.** { *; }
-keep class com.wireguard.crypto.** { *; }
