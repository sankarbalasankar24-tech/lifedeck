# Keep kotlinx.serialization generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.lifedeck.app.**$$serializer { *; }
-keepclassmembers class com.lifedeck.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.lifedeck.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
