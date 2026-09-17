// :core — the contract layer of opentvcast.
//
// This module is intentionally a plain Kotlin/JVM module. It MUST NOT apply the
// Android Gradle plugin or depend on any Android artifact. That constraint is
// the mechanism, not a style preference: as soon as :core can see `android.*`,
// ProtocolReceiver starts accumulating Context, BroadcastReceiver and Surface
// parameters, and we are back to the coupling problem upstream's
// AirPlayReceiver(context: Context, ...) had.
//
// A second benefit: everything here is testable with plain JUnit, with no
// Robolectric and no emulator, which is why `./gradlew :core:test` is a
// meaningful fast gate.

plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    // Pins both the compiler toolchain and the bytecode target, so the module
    // does not depend on whichever JDK happens to be running Gradle.
    jvmToolchain(17)
}

dependencies {
    // `api`, not `implementation`: StateFlow is part of this module's public
    // surface (ProtocolReceiver.state, CastSession.phase), so consumers must see
    // the coroutines types without declaring the dependency themselves.
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
