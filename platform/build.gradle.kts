// :platform — Android-facing infrastructure shared by :app and the protocol modules.
//
// WHY THIS MODULE EXISTS
//
// :airplay uses Logger in almost every file and NetworkUtils in three of them
// (mDNS TXT records, the /info handshake response), and :app uses both too. Those
// files were left behind in :app when the protocol code moved out, which made
// :airplay depend on the application module — the same inversion the split exists
// to prevent, and the same shape as the earlier ProtocolState defect.
//
// They cannot simply go in :core. :core is deliberately a plain kotlin-jvm module
// with no Android on its classpath, and two of the three need `android.*`. Timber
// is a third obstacle: from 5.0 it is published as an AAR only, so a JVM module
// could not consume it even if we wanted it to.
//
// So this is the third bucket: not a contract (that is :core), not a protocol
// (that is :airplay / :dlna), not app policy (that is :app). The test for what
// belongs here is: code that would be byte-identical in every module that needed
// it, and that has no opinion about casting.
//
// The Kotlin package stays `tv.opentvcast.util`. Package and module are
// independent, so nothing that imports these classes had to change.

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "tv.opentvcast.platform"
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
    // The network contracts (NetworkMonitor, NetworkSnapshot, InterfaceSelector)
    // are part of :core's public surface; this module adapts them to Android.
    api(project(":core"))

    // Logger is a thin wrapper over Timber. `implementation` rather than `api`
    // because the AAR packaging is an internal detail of how logging gets to
    // logcat — callers depend on Logger, not on Timber. Modules that plant a Tree
    // (:app plants DebugTree) declare Timber themselves.
    implementation(libs.timber)

    // The Base64 and network-identity unit tests came across with the code.
    // NetworkUtilsTest mocks Context/Settings/ContentResolver, so it needs MockK
    // and Android classes on the unit-test classpath, same as :app.
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
}
