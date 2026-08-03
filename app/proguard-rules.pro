# ---------------------------------------------------------------- osmdroid
# osmdroid resolves tile sources and overlays reflectively.
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# ------------------------------------------------------------ BouncyCastle
# The JCE provider is looked up by algorithm name at runtime, so the SPI
# classes must survive shrinking or CSR generation fails only in release.
-keep class org.bouncycastle.** { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-dontwarn org.bouncycastle.**
# BC ships optional hooks against classes absent on Android.
-dontwarn javax.naming.**
-dontwarn java.awt.**

# ----------------------------------------------------------------- NGA MGRS
-keep class mil.nga.** { *; }
-dontwarn mil.nga.**

# --------------------------------------------------------------- XML pull
# TakProtocol parses CoT XML via the platform XmlPullParser.
-keep class org.xmlpull.v1.** { *; }
-dontwarn org.xmlpull.v1.**

# ------------------------------------------------- our wire-format models
# Field names are not reflected over, but keeping the CoT model makes crash
# reports from the field readable.
-keepnames class com.atakwatch.minimap.model.** { *; }

# ------------------------------------------------------------------ misc
-dontwarn org.slf4j.**
# Keep source/line info so release stack traces stay actionable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
