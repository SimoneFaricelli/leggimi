# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
# PdfBox-Android: dipendenze opzionali non presenti
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
# OkHttp / Jsoup
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn org.jspecify.**
# PdfBox-Android carica font, filtri e risorse per nome: lo lasciamo intatto.
-keep class com.tom_roush.** { *; }
