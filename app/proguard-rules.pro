# BareLabel release ProGuard rules.
# JSON is parsed with org.json (no reflection), so no model keep rules are needed.
# Firebase, Play services, ML Kit, and CameraX ship their own consumer rules.

# Keep line numbers for crash reports (Firebase Crashlytics if added later).
-keepattributes SourceFile,LineNumberTable

# Keep the analytics event taxonomy readable in deobfuscated stack traces.
-keep class com.example.barelabel.AnalyticsTracker { *; }
