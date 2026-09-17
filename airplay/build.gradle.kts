// :airplay — AirPlay 2 receiver implementation.
//
// Depends on :core for the receiver/session contracts and knows nothing about
// DLNA or about the app module. Everything Android-facing that this module
// needs arrives through tv.opentvcast.core.net.ReceiverEnvironment.
//
// The native FairPlay (libplayfair.so) and ALAC (libalac.so) libraries live
// here rather than in :app, because they belong to this protocol.

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "tv.opentvcast.airplay"
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 25

        ndk {
            abiFilters += setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    // RPiPlay-derived FairPlay -> libplayfair.so, Apple ALAC -> libalac.so.
    // Both are GPLv3-compatible; see the repository NOTICE for provenance.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        buildConfig = false
    }

    // Must match :app. Kotlin defaults its jvmTarget to the JDK in use (17 here)
    // while javac defaults to 1.8, and AGP rejects the mismatch outright, so
    // setting only one of the two is not an option.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Upstream disabled these to keep lint quiet. MissingTranslation is
    // reinstated as an error at the :app level, where the string resources live.
    lint {
        abortOnError = true
    }

    // Several protocol tests drive `internal` members (TimingHandler.handleProbe,
    // RaopRsa.publicKeyForTest) or construct android.net.nsd types directly, so
    // they have to live in THIS module's test source set: `internal` does not
    // cross a module boundary. They used to sit in :app/src/test, where they
    // could never compile under AGP — :test-runner only appeared to accept them
    // because it merges every module into one compilation unit.
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    api(projects.core)

    // Logger (every file) and NetworkUtils (mDNS TXT, /info). These used to live in
    // :app, which made this module depend on the application module.
    implementation(projects.platform)

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // Ed25519 / X25519 for AirPlay pairing.
    implementation(libs.bouncycastle)

    // Binary property lists (GET /info, SETUP, playback-info).
    implementation(libs.ddplist)

    implementation(libs.timber)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}
