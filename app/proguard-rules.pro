# ProGuard / R8 rules for opentvcast release builds.
#
# These rules exist for exactly two reasons: code that native libraries reach by
# name, and third-party libraries that resolve their own classes reflectively.
# opentvcast's own code performs no reflective lookup, so it is shrunk normally.
# An earlier version of this file kept every class under the app package, which
# made `isMinifyEnabled = true` a no-op.

# ── JNI bridges ───────────────────────────────────────────────────────────────
# libplayfair.so and libalac.so export symbols of the form
#     Java_tv_opentvcast_airplay_handshake_<Class>_<method>
# The symbol encodes BOTH the class name and the method name, so renaming either
# one breaks the link. That failure surfaces as an UnsatisfiedLinkError on a
# device at runtime, never as a build error — so these are pinned explicitly
# rather than left to the AGP default rule for native methods.
-keep class tv.opentvcast.airplay.handshake.AlacDecoder { *; }
-keep class tv.opentvcast.airplay.handshake.FairPlay { *; }

# ── Bouncy Castle ─────────────────────────────────────────────────────────────
# JCA providers are instantiated by name through the SPI mechanism.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# ── Timber ────────────────────────────────────────────────────────────────────
# The debug tree is constructed through the static `Timber.plant` call site.
-keep class timber.log.** { *; }

# ── Kotlin coroutines ─────────────────────────────────────────────────────────
# Reached reflectively by the compiler-generated state machines and by the
# coroutines service loader.
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# ── Android components ────────────────────────────────────────────────────────
# AGP generates keep rules from the manifest, but Activity/Service subclasses are
# also looked up by the framework in ways those rules do not always cover.
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver

# ── Diagnostics ───────────────────────────────────────────────────────────────
# Retain exception metadata so release crash reports stay readable, without
# leaking local file paths.
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
