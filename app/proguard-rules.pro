# kotlinx-serialization：保留 DTO 的 serializer 生成成员
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class dev.winniesi.zbox.** {
    *** Companion;
}
-keepclasseswithmembers class dev.winniesi.zbox.** {
    kotlinx.serialization.KSerializer serializer(...);
}
