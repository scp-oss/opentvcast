// Local verification settings file — NOT part of the shipped build.
//
// Purpose: compile and test everything that does not require the Android Gradle
// Plugin, in environments where the Android SDK is unavailable.
//
//   ./gradlew --settings-file settings.verify.gradle.kts :core:test
//   ./gradlew --settings-file settings.verify.gradle.kts :test-runner:test
//
// `:test-runner` deliberately compiles :core + :airplay + :dlna + :app sources
// itself via `srcDirs`, using Robolectric's android-all jar for Android class
// definitions. So this pair of tasks type-checks essentially the whole Kotlin
// codebase and executes the full JVM test suite — with no SDK and no AGP.
//
// The repository mirrors below exist because dl.google.com was unreachable from
// the machine this was written on. They are mirrors, not substitutes: the same
// artifacts, so a green run here means the same thing as a green CI run.

pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/google")
    }
}

rootProject.name = "opentvcast"

// Only the two Android-plugin-free modules. Including :app / :airplay / :dlna
// would make Gradle load AGP, which requires a valid SDK location.
include(":core")
include(":test-runner")
