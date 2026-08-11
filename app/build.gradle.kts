plugins {
    // AGP 9 builds Kotlin in, so the standalone kotlin-android plugin is gone;
    // the compiler plugins for Compose and serialization are still applied.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "torkve.bidichan"
    compileSdk = 37

    defaultConfig {
        applicationId = "torkve.bidichan"
        minSdk = 26
        // Compile against the newest APIs the dependencies require, but stay a
        // release behind on targetSdk: that opts into new runtime behaviour,
        // and none of it has been exercised on a device yet.
        targetSdk = 36
        versionCode = (System.getenv("BUILD_NUMBER") ?: "1").toInt()
        versionName = "2.0.1"
    }

    // CI writes a keystore and exports these; without them a release build is
    // signed with the debug key so the APK is still installable from a branch
    // build. Never ship an unsigned or debug-signed artifact as a release.
    val keystorePath = System.getenv("KEYSTORE_PATH")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystorePath != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")
    sourceSets["test"].java.srcDirs("src/test/kotlin")
}


dependencies {
    // The gomobile-built binding of bidichan's `mobile` package. It is produced
    // by the build (see README) and is deliberately not committed.
    implementation(files("libs/bidichan.aar"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.serialization.json)

    // Renders a profile as a scannable code. Pure Java, encoder only — see
    // ui/QrCode.kt for why this is not shared with the iOS client the way the
    // link format is.
    implementation(libs.zxing.core)

    // Cidr.kt is arithmetic over java.net types with nothing from the platform
    // in it, so it runs on the JVM — the one part of tunnel bring-up that can
    // be checked without a device.
    testImplementation(libs.junit)
}
