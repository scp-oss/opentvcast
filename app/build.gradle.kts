// App module build configuration for opentvcast.
//
// Two product flavors are defined from the start:
//   - "googletv": targets Google TV / Android TV (minSdk 29)
//   - "firetv":   targets Amazon Fire TV (minSdk 25)
//
// Shared code lives in src/main/. Flavor-specific overrides in src/googletv/ and src/firetv/.

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// NOTE: there is deliberately no Cast sender-app-id plumbing here. Upstream
// carried a gradle property for it from this file into BuildConfig, but v1 ships
// AirPlay + DLNA only and nothing ever read the value: it survived as dead code
// plus a doc page describing a build flag that did nothing. Removed rather than
// renamed — if Google Cast is ever added, the plumbing comes back with the
// feature, not before it.

android {
    namespace = "tv.opentvcast"
    compileSdk = 35

    defaultConfig {
        // applicationId is overridden per flavor below
        minSdk = 25           // Lowest common denominator (Fire TV)
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // NOTE: no externalNativeBuild here. The native libraries (libplayfair.so,
    // libalac.so) belong to the AirPlay protocol and are built by :airplay,
    // which is also where their JNI bridges live. Keeping them in :app would
    // make the protocol module unbuildable on its own and would blur the
    // module boundary this refactor exists to establish.

    // Two flavors: one for Google TV, one for Amazon Fire TV.
    // This separation allows flavor-specific code, resources, and dependencies.
    flavorDimensions += "platform"
    productFlavors {
        create("googletv") {
            dimension = "platform"
            applicationId = "tv.opentvcast"
            minSdk = 29        // Google TV requires Android 10+
            versionNameSuffix = "-googletv"
        }
        create("firetv") {
            dimension = "platform"
            applicationId = "tv.opentvcast.firetv"
            minSdk = 25        // Fire TV supports Android 7.1+
            versionNameSuffix = "-firetv"
        }
    }

    // Release signing: credentials are injected via environment variables in CI.
    // Set KEYSTORE_PATH, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD to enable.
    // Local builds without these vars produce unsigned release APKs (fine for dev/test).
    val keystorePath = System.getenv("KEYSTORE_PATH")
    if (keystorePath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // Enable strict coroutine checks in debug builds
        freeCompilerArgs += listOf(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
        )
    }

    // Source sets: shared code in main, flavor-specific resources in flavor directories.
    // (Flavor kotlin.srcDirs are only declared when the directories exist — AGP's defaults
    // already point at src/<name>/kotlin and src/<name>/res.)
    sourceSets {
        getByName("googletv") {
            res.srcDirs("src/googletv/res")
        }
        getByName("firetv") {
            res.srcDirs("src/firetv/res")
        }
        getByName("test") {
            kotlin.srcDirs("src/test/kotlin")
        }
        getByName("androidTest") {
            kotlin.srcDirs("src/androidTest/kotlin")
        }
    }

    // Lint configuration: treat all warnings as errors in CI
    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = true
        // Keep lint focused on opentvcast's own sources. Analysing the whole
        // dependency graph is slow and noisy on a small CI runner, while
        // app-source lint still catches manifest/resource/API regressions.
        // (Upstream disabled this to work around the Google Cast SDK's
        // transitive graph; v1 does not depend on that SDK, but the setting is
        // kept because the reason now applies to the AndroidX graph instead.)
        checkDependencies = false
        disable += setOf(
            // Dependency freshness is tracked intentionally, but should not block
            // protocol/build CI when the pinned toolchain is known-good.
            "AndroidGradlePluginVersion",
            "GradleDependency",
            // Localizations are incomplete during the pre-release hardware-test phase.
            "MissingTranslation",
            // Cleanup/style issues that should not block debug APK CI.
            "ButtonStyle",
            "DataExtractionRules",
            "DiscouragedApi",
            "MonochromeLauncherIcon",
            // Launcher-icon shape is advisory; on Android TV the banner is the primary
            // artwork and the icon is rarely shown (sibling of MonochromeLauncherIcon above).
            "IconLauncherShape",
            "ObsoleteSdkInt",
            "Overdraw",
            "UnusedResources",
            // Advisory: the project deliberately supports a wide API range for old TVs;
            // targetSdk is bumped deliberately, not on every new platform release.
            "OldTargetApi"
        )
    }

    packaging {
        jniLibs {
            keepDebugSymbols += "**/*.so"
        }
        resources {
            // BouncyCastle (and some other crypto libs) include OSGI manifest files
            // that conflict when multiple jars are merged. Exclude them — they are
            // not needed at runtime on Android (OSGI is a Java EE/OSGi framework).
            excludes += "META-INF/versions/9/OSGI-INF/**"
            // NOTE: do NOT exclude META-INF/NOTICE.md or META-INF/LICENSE.md here.
            // opentvcast is distributed under GPLv3, which requires preserving the
            // attribution notices of all bundled third-party components
            // (BouncyCastle, dd-plist, Apple ALAC, playfair). Stripping them would
            // breach Section 5(d) of the licence. upstream PhairPlay excluded them
            // to dodge a jar-merge conflict; if that conflict resurfaces, solve it
            // with a merge rule (`mergeInc`) instead of a blanket exclude.
        }
    }

    buildFeatures {
        // BuildConfig is disabled by default in AGP 8.x — enable it explicitly.
        // OpenTvCastApp gates debug logging on BuildConfig.DEBUG and the settings
        // screen shows BuildConfig.VERSION_NAME.
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // Protocol implementations. :app only wires them up; it must not contain
    // protocol logic of its own.
    implementation(projects.core)
    implementation(projects.airplay)
    implementation(projects.dlna)

    // Logger / NetworkUtils / Base64Util. Shared rather than app-local because
    // :airplay needs them too and must not depend on :app.
    implementation(projects.platform)

    // AndroidX UI (View-based, for maximum TV compatibility)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)

    // Lifecycle — lifecycleScope/StateFlow plumbing between CastService and the UI
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // DataStore — async, type-safe replacement for SharedPreferences
    implementation(libs.androidx.datastore.preferences)

    // Async I/O — all network and media operations use coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Logging — tagged, level-filtered logs with pluggable backend
    implementation(libs.timber)

    // Unit Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    // Robolectric — real Android framework classes (Intent, Base64, …) in JVM unit tests
    testImplementation(libs.robolectric)

    // Instrumented Testing (on device)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
