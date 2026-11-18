# kotlinx.serialization: keep the generated serializers of the API models.
-keepclassmembers @kotlinx.serialization.Serializable class com.imsbooks.songbook.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
