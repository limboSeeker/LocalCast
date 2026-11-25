import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing key, in this order:
//  1. LOCALCAST_KEYSTORE_PATH, LOCALCAST_KEYSTORE_PASSWORD, LOCALCAST_KEY_ALIAS, LOCALCAST_KEY_PASSWORD
//     environment variables (CI, see docs/RELEASING.md);
//  2. keystore.properties in the project root (storeFile, storePassword, keyAlias, keyPassword;
//     see keystore.properties.example). Git-ignored, like *.jks and *.keystore.
// Neither: release is signed with the debug key so it can still be installed for testing, and
// release builds print a warning. -Plocalcast.requireReleaseSigning=true (tag builds on CI) turns
// that into an error. A half-filled source is always an error. Passwords are never logged.
class ReleaseKey(val storeFile: File, val storePassword: String, val keyAlias: String, val keyPassword: String)

val releaseKey: ReleaseKey? = run {
    fun key(source: String, values: Map<String, String?>): ReleaseKey {
        val missing = values.filterValues { it.isNullOrBlank() }.keys
        if (missing.isNotEmpty()) {
            throw GradleException("LocalCast: release signing in $source is incomplete, missing: ${missing.joinToString()}")
        }
        // Passwords are used verbatim: a leading or trailing space may be part of them.
        val (path, storePassword, alias, keyPassword) = values.values.map { it!! }
        val store = rootProject.file(path.trim())
        if (!store.isFile) throw GradleException("LocalCast: release keystore not found: $store ($source)")
        return ReleaseKey(store, storePassword, alias.trim(), keyPassword)
    }
    val env = listOf(
        "LOCALCAST_KEYSTORE_PATH", "LOCALCAST_KEYSTORE_PASSWORD", "LOCALCAST_KEY_ALIAS", "LOCALCAST_KEY_PASSWORD",
    ).associateWith { providers.environmentVariable(it).orNull?.takeIf(String::isNotBlank) }
    val propsText = providers.fileContents(rootProject.layout.projectDirectory.file("keystore.properties")).asText.orNull
    when {
        env.values.any { it != null } -> key("environment variables", env)
        propsText != null -> {
            val props = Properties().apply { load(propsText.reader()) }
            key("keystore.properties", listOf("storeFile", "storePassword", "keyAlias", "keyPassword").associateWith { props.getProperty(it) })
        }
        else -> null
    }
}

if (releaseKey == null) {
    if (providers.gradleProperty("localcast.requireReleaseSigning").orNull == "true") {
        throw GradleException("LocalCast: release signing key not configured (required by -Plocalcast.requireReleaseSigning)")
    }
    val buildsRelease = gradle.startParameter.taskNames.any {
        val task = it.substringAfterLast(':')
        task.contains("Release") || task in setOf("assemble", "build", "bundle")
    }
    if (buildsRelease) logger.warn("LocalCast: release signing key not configured, APK is NOT signed for distribution")
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
        versionCode = 5
        versionName = "1.2.0"
    }

    androidResources {
        // The app ships English and Ukrainian; drop library translations into other languages.
        localeFilters += listOf("en", "uk")
    }

    signingConfigs {
        if (releaseKey != null) {
            create("release") {
                storeFile = releaseKey.storeFile
                storePassword = releaseKey.storePassword
                keyAlias = releaseKey.keyAlias
                keyPassword = releaseKey.keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // The developer key when configured (see the top of this file), else the debug key.
            signingConfig = signingConfigs.getByName(if (releaseKey != null) "release" else "debug")
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

// For CI: the version is read from here, not duplicated in the workflow.
tasks.register("printVersionName") {
    val versionName = android.defaultConfig.versionName
    doLast { println(versionName) }
}

// ktor-server-core depends on kotlin-reflect (~835 KB of dex) and Typesafe Config (~67 KB).
// Ktor needs them only to load modules by name and to read HOCON application.conf (EngineMain).
// LocalCast starts the server with embeddedServer { } and the default in-memory config,
// so neither is used. R8 -dontwarn rules for the remaining references: proguard-rules.pro.
fun ExternalModuleDependency.withoutReflectAndHocon() {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
    exclude(group = "com.typesafe", module = "config")
}
