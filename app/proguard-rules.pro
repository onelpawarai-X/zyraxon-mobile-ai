# =============================================================================
#  ZYRAXON — release hardening rules
#
#  R8 runs in full mode on release: every class, field and method is renamed to
#  a short meaningless name, unused code is removed, and the source-file
#  attribute is rewritten so a stack trace reveals no original file name.
#
#  Every rule below exists for one reason only: something looks a class up *by
#  name* at run time, and renaming it would break the app instead of hiding it.
#  Read top to bottom to see each such place — Room, Moshi, the ViewModel
#  factory, the JavaScript bridge, the system-started Android components, and
#  the Shizuku and JGit service loaders.
# =============================================================================

# The line table is dropped so a crash cannot hand out the shape of the code,
# and every source-file name is replaced by the same constant so a trace cannot
# be matched back to a real file. LineNumberTable is deliberately NOT kept:
# keeping it would leave the original line numbers in the dex.
-renamesourcefileattribute SourceFile
-keepattributes SourceFile

# -----------------------------------------------------------------------------
#  Android components the system starts by name.
#
#  The manifest names these classes, and Android instantiates them from that
#  string, so they must keep their names. The rules are deliberately narrow:
#  only the constructor and the lifecycle entry points, not the whole class.
# -----------------------------------------------------------------------------
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.Application
-keep public class * extends android.app.backup.BackupAgentHelper
-keep public class * extends android.preference.Preference

# The custom views the XML layouts inflate, found by their names in the layout.
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public void set*(...);
}

# A class the system serialises into a Bundle keeps a no-arg constructor.
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Enum values are looked up by name in places (valueOf), so keep them.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# -----------------------------------------------------------------------------
#  Room — the generated database reads its entities, DAOs and converters by
#  the names it was compiled against.
# -----------------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep class androidx.room.** { *; }
-dontwarn androidx.room.paging.**

# -----------------------------------------------------------------------------
#  Moshi — the code-generated adapters are matched to their model by name, and
#  any reflective adapter reads the field names of the model.
# -----------------------------------------------------------------------------
-keep class **JsonAdapter { *; }
-keepclasseswithmembers class * {
    @com.squareup.moshi.* <methods>;
}
-keep @com.squareup.moshi.JsonQualifier @interface *
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
}
-keepnames @com.squareup.moshi.JsonClass class *
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions*
-dontwarn retrofit2.KotlinExtensions$*

# -----------------------------------------------------------------------------
#  ViewModel — AndroidViewModelFactory instantiates a ViewModel from its
#  Class object, so the constructor must survive. The class *name* is not
#  looked up anywhere, so it is renamed like everything else.
# -----------------------------------------------------------------------------
-keepclassmembers class * extends androidx.lifecycle.ViewModel { <init>(...); }
-keepclassmembers class ai.zyraxon.assistant.viewmodel.ZyraxonViewModel {
    public static ** shared(...);
}

# -----------------------------------------------------------------------------
#  JavaScript bridges — WebView calls @JavascriptInterface methods by name.
# -----------------------------------------------------------------------------
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# -----------------------------------------------------------------------------
#  Shizuku — the AIDL interfaces and the provider are resolved by name over
#  binder, so the generated Stub classes and the interface names must stay.
# -----------------------------------------------------------------------------
-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-keep class androidx.annotation.Keep
-dontwarn rikka.shizuku.**

# -----------------------------------------------------------------------------
#  JGit — the transport implementations are loaded through the service loader,
#  so their names appear in META-INF/services and cannot be renamed.
# -----------------------------------------------------------------------------
-keep class org.eclipse.jgit.** { *; }
-dontwarn org.eclipse.jgit.**
-dontwarn org.slf4j.**

# -----------------------------------------------------------------------------
#  MediaPipe and LiteRT — the on-device model runtime loads its native bridges
#  and task classes by name.
# -----------------------------------------------------------------------------
-keep class com.google.mediapipe.** { *; }
-keep class com.google.ai.edge.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.ai.edge.**

# -----------------------------------------------------------------------------
#  Kotlin and coroutines — the metadata and the intrinsics the runtime needs.
# -----------------------------------------------------------------------------
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature, Exceptions
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata { public <methods>; }
-dontwarn kotlin.**
-dontwarn kotlinx.coroutines.**

# -----------------------------------------------------------------------------
#  Compose — the runtime finds @Composable functions and the Composer.
# -----------------------------------------------------------------------------
# No blanket `-keep class androidx.compose.runtime.**` here on purpose. The
# compose artifacts ship their own consumer rules, and a blanket keep defeats
# R8: it pins the whole runtime, so the trace-marker code paths and the
# "Composer.kt:406"-style source strings inside them survive in the release dex
# even though the compose compiler no longer generates calls into them. Letting
# R8 shrink what is unreachable is what removes them.
-dontwarn androidx.compose.**

# OkHttp, which the provider and cloud clients sit on.
-dontwarn okhttp3.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
