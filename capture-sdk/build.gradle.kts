plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

android {
    namespace = "com.drishtiai.capture"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        targetSdk = 34
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Required for JitPack (and any Maven-based consumer) to produce a
    // publishable .aar + POM from this library module.
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

// JitPack builds this repo standalone (see sdk-android/README.md for the
// mirror-repo distribution setup) by running `./gradlew publishToMavenLocal`
// and picks up whatever lands in the local Maven cache - groupId/artifactId
// here are placeholders; JitPack overrides them with com.github.<owner> and
// the repo name at resolution time, so consumers never reference these
// directly.
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.drishtiai"
                artifactId = "capture-sdk"
                version = project.findProperty("VERSION_NAME")?.toString() ?: "1.2.0"
            }
        }
    }
}

dependencies {
    // CameraX - Apache 2.0 licensed. Only used by the optional camera/
    // integration point; core scoring has no dependency on it at all.
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")

    // Needed for the suspend-fun update-check API (ConfigUpdateManager,
    // DrishtiCaptureSDK.checkForUpdates()).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // org.json ships with the Android platform - no extra JSON library
    // needed, keeping the SDK's dependency footprint minimal.

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
