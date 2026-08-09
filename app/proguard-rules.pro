# The gomobile binding is reached through JNI and reflection, so nothing under
# the generated Go packages may be renamed or stripped.
-keep class go.** { *; }
-keep class torkve.bidichan.go.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
