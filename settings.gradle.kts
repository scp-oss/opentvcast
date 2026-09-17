// `projects.core` / `projects.airplay` style accessors, used by :airplay, :dlna
// and :app to declare module dependencies. Without this the buildscripts do not
// compile at all — `projects` resolves to Gradle's unrelated ProjectReportTask.
// The settings file was rewritten during the P0 module split and this line was
// lost with it, which broke every module that depends on another.
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "opentvcast"

// :core is the contract layer. It is deliberately a plain kotlin-jvm module and
// must never apply an Android plugin — that is what keeps ProtocolReceiver and
// friends free of Context/Broadcast/Surface imports. See docs/ARCHITECTURE.md.
include(":core")

// Android-facing infrastructure shared by :app and the protocol modules (Logger,
// NetworkUtils, Base64Util). It exists because those helpers need `android.*` and
// Timber, so :core cannot hold them, while leaving them in :app would make the
// protocol modules depend on the application module. See platform/build.gradle.kts.
include(":platform")

// Protocol implementations. Each one depends on :core and knows nothing about
// the others, so a new protocol is additive: new module + one register() call.
include(":airplay")
include(":dlna")

include(":app")

// Pure-JVM aggregation module that compiles :airplay + :dlna + :core sources
// together so `internal` members are testable without Robolectric.
include(":test-runner")
