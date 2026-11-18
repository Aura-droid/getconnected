# GetConnected R8/ProGuard rules.

# --- Debuggable stack traces -------------------------------------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
# Generic signatures needed by Gson/Retrofit TypeTokens.
-keepattributes Signature
-keepattributes *Annotation*

# --- App models (Firestore toObject() + Gson map by field name) ---------------
# R8 must not rename or strip these fields or Firestore/Gson silently
# deserialise into default values.
-keep class com.example.getconnected.UserProfile { *; }
-keep class com.example.getconnected.JobPost { *; }
-keep class com.example.getconnected.Message { *; }
-keep class com.example.getconnected.Chat { *; }
-keep class com.example.getconnected.ChatSummary { *; }
-keep class com.example.getconnected.JobicyResponse { *; }
-keep class com.example.getconnected.RemoteJob { *; }

# --- Room / WorkManager --------------------------------------------------------
# Room loads its generated `*_Impl` database classes ONLY by reflection
# (Class.forName(name + "_Impl").getDeclaredConstructor().newInstance()).
# The library's own rule keeps the class name but not its members, so R8 would
# strip the no-arg constructor and androidx.startup crashes WorkManager at
# app launch with "Failed to create an instance of androidx.work.impl.WorkDatabase".
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class androidx.room.paging.**
-dontwarn androidx.room.paging.**

# --- Cloudinary (ships no consumer rules) -------------------------------------
-keep class com.cloudinary.android.** { *; }
-keep interface com.cloudinary.android.callback.** { *; }
-dontwarn com.cloudinary.android.**

# --- ShortcutBadger (resolves launcher classes by reflection) -----------------
-keep class me.leolin.shortcutbadger.** { *; }
-dontwarn me.leolin.shortcutbadger.**

# --- Jsoup (HTML parsing of link previews) ------------------------------------
-dontwarn org.jsoup.**
-keep class org.jsoup.** { *; }

# --- Retrofit / Gson ----------------------------------------------------------
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
