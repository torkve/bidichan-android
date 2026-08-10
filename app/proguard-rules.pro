# The gomobile binding is reached through JNI and reflection, so nothing under
# the generated Go packages may be renamed or stripped.
-keep class go.** { *; }
-keep class torkve.bidichan.go.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# The keystore-backed preferences pull in Tink, which references annotations
# that exist only at compile time (error-prone, JSR-305). They are absent from
# the runtime classpath by design, so R8's missing-class error is noise.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
