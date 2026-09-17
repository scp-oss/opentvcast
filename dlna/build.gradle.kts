// :dlna — DLNA / UPnP AV MediaRenderer.
//
// Written from scratch: upstream PhairPlay lists "DLNA / UPnP" as explicitly
// out of scope, so there is nothing to port. The wire behaviour is specified in
// docs/spec/TECHNICAL_SPEC.md (DLNA chapter), derived from a working
// third-party implementation.
//
// Structure mirrors that spec:
//   ssdp/   discovery and advertisement over UDP 1900
//   http/   device description + SCPD over a dynamic TCP port
//   soap/   control point actions (AVTransport, RenderingControl, ConnectionManager)
//   gena/   event subscriptions and LastChange notification
//   player/ transport state machine driving a URL media player

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "tv.opentvcast.dlna"
    compileSdk = 35

    defaultConfig {
        minSdk = 25
    }

    buildFeatures {
        buildConfig = false
    }

    // Must match :app — see the note in :airplay. javac and kotlinc default to
    // different JVM targets and AGP fails the build on the mismatch.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = true
    }
}

dependencies {
    api(projects.core)

    // Shared Logger/NetworkUtils. :dlna has no sources yet; the dependency is
    // declared now so the first DLNA file does not have to rediscover why it
    // cannot reach the logger.
    implementation(projects.platform)

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)

    // Pure-JVM tests (SSDP parsing, UPnP documents, SOAP) — no Robolectric here:
    // everything in :dlna that needs testing is deliberately framework-free, and
    // the Android-touching edges are thin adapters like :platform's.
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}
