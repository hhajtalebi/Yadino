# Yadino R8 rules.
# Room, WorkManager, Compose and AndroidX ship their own consumer rules.

# Keep line numbers for readable (non-sensitive) crash logs.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Widgets, receivers, services and activities are referenced from the manifest
# (aapt2 generates keep rules for them).

# Missing optional classes referenced by libraries (not used at runtime).
-dontwarn kotlinx.serialization.**
-dontwarn org.jspecify.annotations.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
