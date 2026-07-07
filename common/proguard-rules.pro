#-dontobfuscate
-dontoptimize
-dontpreverify

## Partially based on Android Gradle Plugin's common ProGuard rules.

# For enumeration classes, see https://www.guardsquare.com/manual/configuration/examples#enumerations
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Preserve annotated Javascript interface methods.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

## Symera extension runtime rules
-keep class org.symera.mediasource.** { public <init>(); }
-keep class org.symera.source.** { *; }

# Rhino references optional JVM desktop APIs that are not available on Android.
-dontwarn java.beans.**
-dontwarn javax.lang.model.**

# kotlinx-serialization runtime keeps for @Serializable types and generated serializers.
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature, RuntimeVisibleAnnotations, AnnotationDefault

-keepclassmembers @kotlinx.serialization.Serializable class ** {
    static ** Companion;
}

-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}

-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

-keepclassmembers public class **$$serializer {
    private ** descriptor;
}

-if @kotlinx.serialization.Serializable class **
-keep,allowshrinking,allowoptimization,allowobfuscation,allowaccessmodification class <1>
