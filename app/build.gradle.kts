plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.localcast.app"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "com.localcast.app"
        minSdk = 30
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
    }

    androidResources {
        // The app ships English and Ukrainian; drop library translations into other languages.
        localeFilters += listOf("en", "uk")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Signed with the debug key so the release build can be installed for testing.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    splits {
        abi {
            // One APK per architecture: arm64-v8a and armeabi-v7a for phones and tablets,
            // x86_64 for the emulator. No universal APK: it carries all three libwebrtc builds.
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/INDEX.LIST",
                "/META-INF/DEPENDENCIES",
                "/META-INF/io.netty.versions.properties",
                // Read only by kotlin-reflect, which is excluded below.
                "/kotlin/**.kotlin_builtins",
                // License texts and library version markers: not used at runtime.
                "/META-INF/LICENSE*",
                "/META-INF/**/LICENSE*",
                "/META-INF/*.version",
                // kotlinx-coroutines debug agent (JVM only).
                "/DebugProbesKt.bin",
            )
        }
        jniLibs {
            // Compress .so files: the APK is sideloaded, so its size is what users download and
            // share, and libwebrtc (arm64) shrinks from ~12 MB to ~6 MB. The installer extracts
            // the libraries to disk, so in-APK page alignment is not needed; 16 KB page devices
            // only need the ELF LOAD segments aligned to 16 KB, which all 64-bit .so here are.
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.ktor.server.core) { withoutReflectAndHocon() }
    implementation(libs.ktor.server.cio) { withoutReflectAndHocon() }
    implementation(libs.ktor.server.websockets) { withoutReflectAndHocon() }
    implementation(libs.slf4j.nop)

    implementation(libs.webrtc.android)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Same exclusions in tests, so SignalingTest runs Ktor exactly as the app ships it.
    testImplementation(libs.ktor.server.test.host) { withoutReflectAndHocon() }
    testImplementation(libs.ktor.client.websockets)
}

// ktor-server-core depends on kotlin-reflect (~835 KB of dex) and Typesafe Config (~67 KB).
// Ktor needs them only to load modules by name and to read HOCON application.conf (EngineMain).
// LocalCast starts the server with embeddedServer { } and the default in-memory config,
// so neither is used. R8 -dontwarn rules for the remaining references: proguard-rules.pro.
fun ExternalModuleDependency.withoutReflectAndHocon() {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
    exclude(group = "com.typesafe", module = "config")
}
