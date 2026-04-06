import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use(::load)
    }
}

fun signingValue(name: String): String? {
    return providers.gradleProperty(name).orNull
        ?: System.getenv(name)
        ?: localProperties.getProperty(name)
}

val defaultReleaseKeystorePath = "C:/Users/Vasilis/AndroidStudioProjects/dosboxAA.jks"
val releaseStoreFilePath = signingValue("RETRODRIVE_RELEASE_STORE_FILE")
    ?: defaultReleaseKeystorePath.takeIf { file(it).exists() }
val releaseKeyAlias = signingValue("RETRODRIVE_RELEASE_KEY_ALIAS") ?: "key0"
val releaseStorePassword = signingValue("RETRODRIVE_RELEASE_STORE_PASSWORD")
val releaseKeyPassword = signingValue("RETRODRIVE_RELEASE_KEY_PASSWORD")
val hasReleaseSigning =
    !releaseStoreFilePath.isNullOrBlank() &&
        !releaseStorePassword.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "com.codeodyssey.retrodriveaa"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFilePath))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.codeodyssey.retrodriveaa"
        minSdk = 29
        targetSdk = 35
        // Incremented for the next release build
        versionCode = 37
        versionName = "1.0.34"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "CONTROLLER_WS_BASE_URL", "\"wss://retrodrive.antoniadis.workers.dev/ws\"")
        buildConfigField("String", "CONTROLLER_WEB_BASE_URL", "\"https://retrodrive.code-odyssey.com/controller.html\"")

        // NDK configuration for native DOSBox engine
        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }

        // 16 KB Page Size Alignment for Native Binaries
        externalNativeBuild {
            ndkBuild {
                // Passes the 16KB max-page-size flag to the linker
                arguments("-Wl,-z,max-page-size=16384", "APP_STL=c++_static")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            excludes += setOf(
                "**/objs/**",
                "**/*.o",
                "**/*.o.tmp",
                "**/*.d",
                "**/*.a"
            )
        }
    }

    // Use ndk-build instead of CMake to preserve original build logic
    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // Android Automotive OS support
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.car.app:app-projected:1.4.0")
    implementation(files("lib/auto/aauto.aar"))

    // AppCompat for legacy DOSBox activity
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.media:media:1.7.0")

    // Trial -> full unlock payments (same as MM-IPTV)
    implementation("com.stripe:stripe-android:21.11.1")
    implementation("com.squareup.okhttp3:okhttp:4.10.0")

    // WiFi Controller: NanoHTTPD WebSocket server
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")

    // WiFi Controller: QR Code generation
    implementation("com.google.zxing:core:3.5.2")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

tasks.configureEach {
    if (name.startsWith("buildNdkBuild")) {
        doNotTrackState("ndk-build emits transient temporary object files that may disappear before output snapshotting")
    }
    if (name.contains("NativeLibs")) {
        doNotTrackState("native merge tasks can observe transient NDK temporary files on Windows")
    }
}