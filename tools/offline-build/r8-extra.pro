# Extra R8 rules used only by the hermetic build: the Gradle build has these
# compile-only annotation artifacts on its classpath, the offline build does not.
-dontwarn org.jetbrains.annotations.**
-dontwarn org.intellij.lang.annotations.**
-dontwarn kotlinx.serialization.**
-dontwarn org.jspecify.annotations.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn org.checkerframework.**
