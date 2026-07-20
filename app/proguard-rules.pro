# kotlinx.serialization keeps its generated serializers via @Serializable companions.
-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.tune.player.**$$serializer { *; }
-keep class dev.tune.player.data.** { *; }
