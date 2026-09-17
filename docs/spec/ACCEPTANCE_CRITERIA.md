# opentvcast – Acceptance Criteria

Version: 3.0
Status: Active
Date: 2026-09-16
Scope: v1 (AirPlay 2 + DLNA)

A milestone is **DONE** only when every criterion passes on every target device, unless
the criterion is explicitly device-specific.

---

## How to read the status column

| Mark | Meaning |
|---|---|
| ✅ | Verified, with the verification recorded |
| 🚧 | Partially verified — see the note |
| ⬜ | Not started |
| ⚠️ | **Not met.** Stated plainly rather than omitted |

The distinction that matters most in this document is between "the code is written and
unit-tested" and "it has been seen working on a TV". Most of v1 is the former. §8 is
where the latter lives, and §8 is currently ⚠️.

---

## Milestone 0 – Specification complete ✅

**Goal:** the specification describes what ships.

| # | Criterion | How to verify | Pass condition | Status |
|---|---|---|---|---|
| AC-0.1 | `docs/spec/REQUIREMENTS.md` exists and covers v1 scope | Read it | AirPlay 2 + DLNA only; no Miracast or Cast requirements | ✅ |
| AC-0.2 | `docs/spec/TECHNICAL_SPEC.md` matches the shipped module layout | Compare with `settings.gradle.kts` | 5+1 modules described; component table correct | ✅ |
| AC-0.3 | `docs/spec/ACCEPTANCE_CRITERIA.md` covers every milestone | This file | Milestones 0–10 present | ✅ |
| AC-0.4 | `docs/spec/PROJECT_PLAN.md` phases match the status table | Read it | No ✅ on an unfinished phase | ✅ |
| AC-0.5 | Every spec document is committed | `git log --oneline -- docs/spec` | All four visible | ✅ |

## Milestone 1 – App starts ✅

**Goal:** the app launches on both flavors without crashing.

| # | Criterion | How to verify | Pass condition | Status |
|---|---|---|---|---|
| AC-1.1 | Google TV debug APK builds | `./gradlew :app:assembleGoogletvDebug` | Exit 0, APK produced | ✅ |
| AC-1.2 | Fire TV debug APK builds | `./gradlew :app:assembleFiretvDebug` | Exit 0, APK produced | ✅ |
| AC-1.3 | Native libraries are present for all supported ABIs | Inspect the APK | `libplayfair.so` and `libalac.so` for arm64-v8a, armeabi-v7a, x86, x86_64 | ✅ |
| AC-1.4 | Release APK builds under R8 | `./gradlew :app:assembleGoogletvRelease` | Exit 0 | ✅ |
| AC-1.5 | Shrinking actually removes code | Compare APK entry counts | Release has materially fewer entries than debug | ✅ (486 vs 677) |
| AC-1.6 | Lint is clean with warnings as errors | `./gradlew :app:lintGoogletvDebug` | Exit 0 | ✅ |
| AC-1.7 | App launches and shows the home screen | Install on a device, launch | Home screen appears, no crash | ⚠️ **Not verified on a device** |
| AC-1.8 | Foreground service notification appears | Launch, observe notification | Persistent notification with Stop / Restart | ⚠️ **Not verified on a device** |

## Milestone 2 – Network compatibility ✅

**Goal:** the failures that make a receiver invisible or unreachable are handled in one
place, and each has a test.

`REQUIREMENTS.md` FR-07 … FR-23. Everything below is a unit test in `:core`, which
needs no device and no Android SDK.

| # | Criterion | How to verify | Pass condition | Status |
|---|---|---|---|---|
| AC-2.1 | The multicast lock is reference-counted by owner | `:core:test --tests RefCountedMulticastLockTest` | Releasing one owner while another holds keeps the lock held | ✅ |
| AC-2.2 | Acquiring twice under one tag counts once | Same | One release fully releases | ✅ |
| AC-2.3 | A missing Wi-Fi service does not prevent startup | `:app:testGoogletvDebugUnitTest --tests AndroidMulticastLockTest` | Construction and acquire/release are no-ops, no exception | ✅ |
| AC-2.4 | An unavailable preferred port is substituted, not fatal | `--tests ServerSocketPortAllocatorTest` | A dynamic port is returned | ✅ |
| AC-2.5 | A port a real listener holds is not granted | Same | Granted port differs from the requested one | ✅ |
| AC-2.6 | Only the reserving owner may release a port | Same | A non-owner release is ignored | ✅ |
| AC-2.7 | Unusable interfaces are excluded from selection | `--tests DefaultInterfaceSelectorTest` | Loopback, down, virtual, point-to-point all excluded | ✅ |
| AC-2.8 | The selection preference order holds | Same | Ethernet > Wi-Fi; site-local > global; IPv4 > IPv6 | ✅ |
| AC-2.9 | A network change is reported once it settles | `--tests CoalescingNetworkMonitorTest` | One callback per settled change | ✅ |
| AC-2.10 | A flap that recovers is not reported | Same | Zero callbacks | ✅ |
| AC-2.11 | The identity at start is not reported | Same | Zero callbacks | ✅ |
| AC-2.12 | A throwing re-advertise does not stop monitoring | Same | The next change is still reported | ✅ |
| AC-2.13 | The lock is held while advertising on a device | Install, `adb shell dumpsys wifi \| grep opentvcast` | Lock listed as held | ⚠️ **Not verified on a device** |
| AC-2.14 | Discovery still works on a device that filters multicast | Install on such a device, open the AirPlay picker | TV appears | ⚠️ **Not verified on a device** |

## Milestone 3 – Discovery ✅ (unit) / ⚠️ (device)

**Goal:** the receiver appears in the sender's picker within 3 seconds of start.

| # | Criterion | How to verify | Pass condition | Status |
|---|---|---|---|---|
| AC-3.1 | mDNS services are registered after start | `MdnsServiceTest` | `_airplay._tcp` and `_raop._tcp` both registered | 🚧 |
| AC-3.2 | The advertised name follows the setting | Read the TXT record / `MdnsService` logs | Configured name, or the device name when blank | ✅ |
| AC-3.3 | The TV appears in the macOS AirPlay menu within 3 s | Stopwatch from app start | Appears ≤ 3 s | ⚠️ **Not verified on a device** |
| AC-3.4 | The TV disappears within 10 s of the service stopping | Stop the service, watch the picker | Removed ≤ 10 s | ⚠️ **Not verified on a device** |
| AC-3.5 | Re-advertising happens after a network change | Toggle Wi-Fi with the app running | TV reappears without an app restart | ⚠️ **Not implemented** (no `NetworkMonitor` adapter yet) |

## Milestone 4 – Connection & handshake ✅ (unit) / ⚠️ (device)

**Goal:** a sender completes RTSP negotiation and reaches `RECORD`.

| # | Criterion | How to verify | Pass condition | Status |
|---|---|---|---|---|
| AC-4.1 | RTSP request parsing is byte-exact | `RtspRequestReaderTest` | A request consumes exactly its own bytes; following bytes remain | ✅ |
| AC-4.2 | `OPTIONS` advertises the required methods | `RtspHandlerTest` | All expected methods present | ✅ |
| AC-4.3 | `GET /info` returns a well-formed binary plist | `InfoResponder` via handler tests | Parses, contains the required keys | ✅ |
| AC-4.4 | Pair-setup / pair-verify complete | `PairingSessionTest` | Expected message sizes and outcomes | ✅ |
| AC-4.5 | FairPlay `fp-setup` returns a correctly sized response | `FairPlayTest` | 142-byte responses for v2 and v3; 32-byte for the legacy path | ✅ |
| AC-4.6 | Legacy SRP PIN pairing works and locks out | `LegacyPairSetupPinTest` | PIN accepted; lockout after the configured attempts | ✅ |
| AC-4.7 | A sender completes the full handshake | `adb logcat` during a mirror | OPTIONS → SETUP → ANNOUNCE → RECORD, all `200 OK` | ⚠️ **Not verified on a device** |

## Milestone 5 – Video ✅ (unit) / ⚠️ (device)

| # | Criterion | How to verify | Pass condition | Status |
|---|---|---|---|---|
| AC-5.1 | Interleaved RTP reassembly handles single NAL and FU-A | `RtpFuaReassemblyTest` | Correct reassembly for both | ✅ |
| AC-5.2 | The CTR keystream is continuous across payloads | `MirrorCryptoTest` | Split payloads equal one contiguous decrypt | ✅ |
| AC-5.3 | AVCC → Annex-B conversion is correct | Same | Start codes inserted; malformed lengths rejected | ✅ |
| AC-5.4 | SPS resolution parsing is correct | `SpsParserTest` | 17 cases including high profile | ✅ |
| AC-5.5 | Session ID formatting matches the sender's derivation | `MirrorCryptoTest` | The id participates; negative ids use the unsigned form | ✅ |
| AC-5.6 | Video renders full-screen, aspect-fit | Watch it | No stretching, black bars as needed | ⚠️ **Not verified on a device** |
| AC-5.7 | Portrait streams render portrait | Mirror from a phone | Portrait, not stretched to 16:9 | ⚠️ **Not verified on a device** |

## Milestone 6 – Audio ✅ (unit) / ⚠️ (device)

| # | Criterion | How to verify | Pass condition | Status |
|---|---|---|---|---|
| AC-6.1 | SDP audio parsing covers codec / encryption / rate / channels | `SdpParserTest` | All supported audio types parsed | ✅ |
| AC-6.2 | ALAC magic cookie construction is correct | `AlacDecoderTest` | 24-byte cookie for the documented parameters | ✅ |
| AC-6.3 | Audio decryption paths are correct | `AudioPlayerEncryptionTest` | Per-packet IV handling verified | ✅ |
| AC-6.4 | RTP duplicates are suppressed and counted | `AudioStreamServerTest` | Duplicate count matches input | ✅ |
| AC-6.5 | A wrong key produces silence, not noise | Read `AudioPlayer`'s health guard; unit-tested | Output muted on decode failure | ✅ |
| AC-6.6 | Audio plays in sync with video, drift ≤ 40 ms | Watch a mirror for 5 minutes | No perceptible drift | ⚠️ **Not verified on a device** |
| AC-6.7 | Audio-only streams play without opening the streaming screen | Mirror audio from the Mac | Audio plays, home screen stays up | ⚠️ **Not verified on a device** |

## Milestone 7 – DLNA ⬜

**Goal:** Windows and Android senders can push media to the TV.

Not started. The `:dlna` module contains no sources.

| # | Criterion | How to verify | Pass condition | Status |
|---|---|---|---|---|
| AC-7.1 | The device responds to SSDP `M-SEARCH` | Send `M-SEARCH` from a control point | `200 OK` with a `LOCATION` on the granted port | ⬜ |
| AC-7.2 | The `LOCATION` port matches the allocated port | Compare SSDP to the description fetch | Identical; never the requested port | ⬜ |
| AC-7.3 | The device description and SCPDs are served | Fetch them | Valid XML, all three services | ⬜ |
| AC-7.4 | `AVTransport` actions are implemented | SOAP calls | `Play`, `Pause`, `Stop`, `Seek`, `SetAVTransportURI` respond correctly | ⬜ |
| AC-7.5 | GENA subscription works | Subscribe, change state | `LastChange` notifications delivered | ⬜ |
| AC-7.6 | Windows "Cast to Device" lists the TV | Open the Windows menu | TV appears | ⬜ |
| AC-7.7 | A pushed video plays, pauses, seeks, stops | Drive it from the sender | All four work | ⬜ |

## Milestone 8 – Stability & device validation ⚠️ **NOT MET**

**Goal:** 30 minutes of continuous mirroring without loss, on real hardware.

This milestone is the project's largest gap and is called out rather than buried.
Everything above that is marked ✅ is a JVM unit test.

| # | Criterion | How to verify | Pass condition | Status |
|---|---|---|---|---|
| AC-8.1 | 30 minutes of continuous mirroring without a drop | Watch it | Never drops | ⚠️ |
| AC-8.2 | RAM stays ≤ 150 MB throughout | `adb shell dumpsys meminfo tv.opentvcast` every 5 min | Peak ≤ 150 MB | ⚠️ |
| AC-8.3 | CPU stays ≤ 30 % average | `adb shell top -p $(pidof tv.opentvcast)` | 5-minute rolling average ≤ 30 % | ⚠️ |
| AC-8.4 | Latency ≤ 100 ms on 5 GHz | Measure | ≤ 100 ms | ⚠️ |
| AC-8.5 | Frame rate ≥ 25 fps sustained | Debug overlay | ≥ 25 fps | ⚠️ |
| AC-8.6 | The session recovers after a Wi-Fi toggle | Disable and re-enable Wi-Fi on the sender | Reconnect possible within 5 s | ⚠️ |
| AC-8.7 | A second sender is prompted, not silently refused | Connect a second sender | Preemption prompt appears | ⚠️ |
| AC-8.8 | The release build resolves its JNI symbols | Install a release APK, start mirroring | No `UnsatisfiedLinkError` | ⚠️ |
| AC-8.9 | Swiping the app away stops the service | Swipe, then check the notification | Notification gone, receiver stopped | ⚠️ |

**Exit criterion for this milestone:** at least AC-8.1, AC-8.8 and AC-8.9 recorded on
both a Google TV and a Fire TV device.

## Milestone 9 – i18n ⬜

| # | Criterion | How to verify | Pass condition | Status |
|---|---|---|---|---|
| AC-9.1 | No user-visible string is hardcoded | Lint `HardcodedText`, plus review | None | ✅ |
| AC-9.2 | Every string has a default-locale value | Build | No missing default values | ✅ |
| AC-9.3 | German and French are complete | Compare key sets | No missing keys | ⬜ |
| AC-9.4 | RTL locales render correctly | Switch to an RTL locale | Layout mirrors | ⬜ |
| AC-9.5 | `MissingTranslation` is an error | Lint config | Enabled | ⬜ |

## Milestone 10 – Release ⬜

| # | Criterion | How to verify | Pass condition | Status |
|---|---|---|---|---|
| AC-10.1 | The full module test suite passes | See `docs/TESTING.md` | 0 failures | ✅ (482 tests) |
| AC-10.2 | CI runs the per-module tests, not only `:test-runner` | Read `.github/workflows/ci.yml` | Both present | ✅ |
| AC-10.3 | CI installs the NDK and CMake | Same | Present in the SDK packages list | ✅ |
| AC-10.4 | Signed release APKs for both flavors | `scripts/release.sh vX.Y.Z` | Two signed APKs | ⬜ |
| AC-10.5 | GPLv3 obligations met | `NOTICE` review | Attribution present, source offer valid | 🚧 (paths verified; no published release yet) |
| AC-10.6 | First public release published | Check the releases page | Tag and APKs published | ⬜ |

---

## Performance benchmark summary

Targets are from `REQUIREMENTS.md` §2.1. **None have been measured**, because no
device has run the app.

| Metric | Target | Measured | Status |
|---|---|---|---|
| Video latency (5 GHz) | ≤ 100 ms | — | ⚠️ |
| Frame rate | ≥ 25 fps | — | ⚠️ |
| Peak RAM (one stream) | ≤ 150 MB | — | ⚠️ |
| Average CPU | ≤ 30 % | — | ⚠️ |
| A/V drift | ≤ 40 ms | — | ⚠️ |
| Discovery-to-visible | ≤ 3 s | — | ⚠️ |

The tooling for all of these exists: `tools/collect-device-logs.sh` captures package
state, memory, CPU and process-filtered logcat, and the debug overlay shows FPS, queue
depth and loss live. What is missing is a device.

---

## Definition of done for v1

1. Milestones 0–6 met, with AC-8.1, AC-8.8 and AC-8.9 recorded on both target devices.
2. Milestone 7 (**DLNA**) met, or explicitly deferred with the scope table updated.
3. Milestone 9 complete for the languages claimed.
4. Milestone 10 complete, with a signed release published and the GPLv3 source offer valid.

Items 2 and 4 are unresolved: DLNA is unstarted, and no release has been published.
