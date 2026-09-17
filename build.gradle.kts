// Root build file intentionally keeps task wiring minimal.
// Android/Gradle plugin tasks (including `build` and `lint`) are provided by included modules.

plugins {
    // Both Android plugins must be declared here, even though neither is applied
    // to the root project. They are separate plugin ids backed by the same
    // `com.android.tools.build:gradle` artifact, so declaring only one leaves the
    // other to be resolved lazily from a subproject — at which point Gradle finds
    // AGP already on the buildscript classpath under a different resolution and
    // fails with "the plugin is already on the classpath with an unknown version".
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

subprojects {
    configurations.configureEach {
        resolutionStrategy.force(
            "org.jetbrains.kotlin:kotlin-stdlib:1.9.23",
            "org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.9.23",
            "org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.23"
        )
    }
}
