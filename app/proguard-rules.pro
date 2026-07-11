# kotlinx.serialization — keep @Serializable metadata and generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class com.miruronative.data.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.miruronative.data.model.**$$serializer { *; }
-keep class com.miruronative.data.model.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
