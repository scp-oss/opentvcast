# opentvcast – Technical Specification

Version: 3.0
Status: Active
Date: 2026-09-16
Scope: v1 (AirPlay 2 + DLNA)

## Scope

### Verification status

**No part of this protocol stack has been exercised against real Apple hardware
in its current architecture.** Every statement below is derived from the
implementation and from unit tests, not from observed streams. Features marked
"Not implemented" genuinely do not exist in the tree; features without such a
mark exist in code but have not been proven against a sender.

This document describes the system as built. It covers **AirPlay 2** (implemented) and
**DLNA / UPnP** (implemented — see §10.5; not verified against a real control point).

**Miracast and Google Cast are not part of v1.** Sections describing them have been
removed rather than annotated: Google Cast is the only feature requiring Google Play
Services, and Miracast requires Wi-Fi Direct, whose behaviour varies too much across TV
hardware. `REQUIREMENTS.md` §0 has the reasoning in full.

The one place a removed protocol still matters is §11: HDCP exists only for Miracast
and Widevine/PlayReady only for Cast, so all three drop out together.

---

## 1. AirPlay 2 Protocol Overview

AirPlay 2 Screen Mirroring works as a layered protocol stack. The description below covers the full flow from the moment a **macOS or iOS/iPadOS** user taps "AirPlay" to media appearing on the TV.

> **Target senders:** macOS 12+ (Monterey and later), iOS/iPadOS 16+ for the
> HomeKit-style pairing path. Older senders may fall back to the legacy SDP path.
> The wire protocol is the same RTSP/RTP stack either way.
>
> **Nothing here has been verified on hardware.** See "Verification status" below.

### Step 1 – Service Discovery (mDNS / Bonjour)

macOS continuously scans the local network for AirPlay receivers using **mDNS** (Multicast DNS, RFC 6762), the same technology as Apple's "Bonjour".

opentvcast registers two mDNS services:

| Service Type | Purpose |
|---|---|
| `_airplay._tcp` | Main AirPlay service — advertises device name, features, model |
| `_raop._tcp` | Remote Audio Output Protocol — required for audio streaming negotiation |

Each service registration includes **TXT records** that tell the sender what the receiver supports:

| TXT Key | Value | Meaning |
|---|---|---|
| `deviceid` | MAC address (e.g., `aa:bb:cc:dd:ee:ff`) | Unique device identifier |
| `features` | Bitmask (e.g., `0x5A7FFFF7,0x1E`) | Which AirPlay features are supported |
| `model` | `AppleTV5,3` | Tells macOS to treat us like an Apple TV |
| `srcvers` | `220.68` | AirPlay server version |
| `vv` | `2` | Protocol version 2 |
| `pi` | UUID string | Persistent device UUID |
| `pk` | 64-byte hex public key | Ed25519 public key (used in authenticated mode) |

**Android API used:** `android.net.nsd.NsdManager` — Android's built-in mDNS implementation.

### Step 2 – RTSP Session Establishment

Once macOS discovers the device, it opens a **TCP connection to port 7000** and speaks **RTSP** (Real Time Streaming Protocol, RFC 2326) with Apple-specific extensions.

The RTSP handshake sequence for screen mirroring:

```
macOS                           opentvcast (Android TV)
  │                                     │
  │── OPTIONS rtsp://... RTSP/1.0 ────► │  "What can you do?"
  │◄─ 200 OK (Public: OPTIONS, SETUP…)─ │  "Here are my capabilities"
  │                                     │
  │── ANNOUNCE rtsp://... RTSP/1.0 ───► │  "I'm about to send you a stream"
  │   Content-Type: application/sdp     │  (SDP describes codec, ports, keys)
  │◄─ 200 OK ──────────────────────────│
  │                                     │
  │── SETUP rtsp://... RTSP/1.0 ──────► │  "Set up the video channel"
  │   Transport: RTP/AVP/TCP;...        │  (negotiates port numbers)
  │◄─ 200 OK (Transport: ...) ─────────│
  │                                     │
  │── SETUP rtsp://... RTSP/1.0 ──────► │  "Set up the audio channel"
  │◄─ 200 OK ──────────────────────────│
  │                                     │
  │── RECORD rtsp://... RTSP/1.0 ─────► │  "Start streaming now"
  │◄─ 200 OK ──────────────────────────│
  │                                     │
  │   [RTP video packets over TCP] ───► │
  │   [RTP audio packets over UDP] ───► │
  │   [Timing packets over UDP] ──────► │
  │                                     │
  │── TEARDOWN rtsp://... RTSP/1.0 ───► │  "Stop, I'm disconnecting"
  │◄─ 200 OK ──────────────────────────│
```

**SDP (Session Description Protocol)** in the ANNOUNCE body describes:
- Video codec: H.264 (`a=rtpmap:96 H264/90000`)
- Video parameters: profile-level-id, SPS/PPS (codec initialization data)
- Audio codec: AAC-ELD or ALAC
- Encryption keys (AES-128-CTR for the mirroring video stream, AES-128-CBC for every audio path)
- Port numbers for RTP/RTCP

### Step 3 – Video Streaming

After RECORD, macOS sends video as **RTP packets over the RTSP TCP connection** (interleaved in the RTSP stream using `$` framing).

Each video packet contains a fragment of an **H.264 NAL unit** (Network Abstraction Layer — the elementary unit of H.264 video). The MediaCodec decoder reassembles NAL units into frames.

**Video path:**
```
RTP bytes (TCP) → RtspHandler strips RTP header →
VideoDecoder extracts NAL units → MediaCodec (hardware) → SurfaceView
```

**H.264 profile:** Up to High Profile Level 5.2. Typical mirroring uses Constrained High Profile (CHP) or Main Profile. The decoder must handle all profiles up to High Profile Level 5.2 (supports 1080p @ 60fps and 4K @ 30fps).

> **Not implemented.** HEVC/H.265 is planned for v2 (see §11) and capability-gated
> there. No HEVC path exists in the tree today: a sender that negotiates it fails
> rather than falling back silently.

### Step 4 – Audio Streaming

Audio is sent as **RTP packets over a separate UDP socket** (port negotiated in SETUP).

Audio format is one of:
- **AAC-LC** — Low Complexity AAC, used for screen mirroring (lower bitrate)
- **AAC-ELD** (Enhanced Low Delay AAC) — used when low-latency audio is required during mirroring
- **ALAC** (Apple Lossless) 16-bit / 44.1 kHz — default for music/podcast audio-only streaming
- **LPCM** — uncompressed PCM, used when the sender requests minimal processing latency

> **Not implemented.** Surround (Dolby Atmos E-AC3 JOC, AC-3) is planned for v2
> (see §11). Nothing negotiates, advertises, or decodes those formats today, and the
> mDNS `features` bitmask does not claim them.

Audio packets are **AES-128-CBC encrypted** (`AudioStreamServer`, `AudioPlayer`). The
key and IV are provided in the SDP body of the ANNOUNCE request. CTR is used for the
mirroring stream instead (`MirrorCrypto`) — the two are not interchangeable, and an
earlier revision of this document had them swapped.

**Audio path:**
```
RTP bytes (UDP) → AES-128-CBC decrypt → AudioDecoder extracts AAC/ALAC frame →
AudioTrack (hardware output)
```

### Step 5 – Timing Synchronization

A separate UDP socket (timing port) handles **NTP-based time synchronization** between sender and receiver. This is used to keep audio and video in sync.

### Step 6 – Audio-Only Mode (music / podcasts from macOS and iOS)

When the sender initiates an **audio-only** AirPlay stream (e.g., playing music from Apple Music, Spotify, or a podcast app), the SDP body in the ANNOUNCE request contains **only an audio media section** — there is no `m=video` line.

Detection in `RtspHandler`:
```kotlin
val hasVideo = sdp.contains("m=video")
val hasAudio = sdp.contains("m=audio")
// audio-only: hasVideo == false, hasAudio == true
```

Audio-only path:
```
RTP bytes (UDP) → AES-128-CBC decrypt → AAC/ALAC decode →
AudioTrack (hardware output)
↕ no VideoDecoder started, no SurfaceView shown
```

UI behaviour: the app remains on the **HomeScreen**. The AirPlay protocol card updates to show sender name and "Audio streaming" detail text. No fullscreen streaming activity is launched.

---

## 2. System Architecture

### 2.1 Module layout

The build is 5+1 Gradle modules. The boundaries are enforced by the compiler, which is
the point: a protocol module cannot quietly reach into the app module, because it does
not compile.

```
:core         kotlin-jvm          Contracts only — receiver/session interfaces, shared
                                  value types, network abstractions. Deliberately NOT
                                  an Android module, so `ProtocolReceiver` cannot grow
                                  a Context or Surface parameter.
:platform     android-library     Android-facing infrastructure shared by :app and the
                                  protocol modules: Logger, NetworkUtils, Base64Util.
:airplay      android-library     The AirPlay 2 receiver + the native libplayfair and
                                  libalac build.
:dlna         android-library     DLNA / UPnP receiver. Build config only so far.
:app          android-application Wires receivers into a foreground service and TV UI.
                                  Flavors: googletv, firetv.
:test-runner  kotlin-jvm          Aggregates every module's sources into one compilation
                                  unit so tests reach `internal` members. No SDK needed.
```

Two consequences worth stating, because both are easy to trip over:

- **Anything needing `android.*` cannot live in `:core`.** That rule is why `:platform`
  exists: `NetworkUtils` reads `WifiManager` so it cannot be in `:core`, and Timber 5.x
  ships as an AAR with no JAR variant, so `Logger` cannot be either.
- **`:test-runner` is a test gate, not a structural check.** It merges all modules into
  one compilation unit, so it cannot detect a broken module boundary and it excludes
  `**/ui/**` outright. The structural check is the Android build.

### 2.2 Runtime composition

```
┌──────────────────────────────────────────────────────────────────────┐
│                        :app  (opentvcast)                            │
│                                                                      │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │                    UI Layer (View-based fragments)             │  │
│  │   MainActivity ─ HomeFragment ─ SettingsFragment               │  │
│  │   StreamingScreen ─ NowPlayingScreen ─ PinScreen               │  │
│  └────────────────────────────┬───────────────────────────────────┘  │
│                               │ binds / observes StateFlow           │
│  ┌────────────────────────────▼───────────────────────────────────┐  │
│  │          CastService  (ForegroundService)                      │  │
│  │  serviceState / airPlayState / dlnaState / activeConnection    │  │
│  │  ConnectionMapping — state table shared with its test          │  │
│  └────────┬──────────────────────────────────┬────────────────────┘  │
│           │                                  │                       │
│  ┌────────▼──────────────┐        ┌──────────▼──────────┐           │
│  │ :airplay              │        │ :dlna               │           │
│  │  AirPlayReceiver      │        │  (not implemented)  │           │
│  │   MdnsService         │        │   SSDP responder    │           │
│  │   RtspHandler         │        │   SOAP control      │           │
│  │   MirrorStreamServer  │        │   GENA events       │           │
│  │   AudioStreamServer   │        │                     │           │
│  │   VideoDecoder        │        │                     │           │
│  │   AudioPlayer         │        │                     │           │
│  │  ── JNI ──            │        └─────────────────────┘           │
│  │   libplayfair.so      │                                          │
│  │   libalac.so          │                                          │
│  └───────────────────────┘                                          │
│                                                                      │
│  ┌───────────────────────────────────────────────────────────────┐   │
│  │ :core  contracts (no Android)                                 │   │
│  │   ProtocolReceiver / CastSession / CastEvent / SurfaceSink    │   │
│  │   ReceiverRegistry — session arbitration + event fan-out      │   │
│  │   net/: MulticastLockHandle, PortAllocator, InterfaceSelector, │   │
│  │        NetworkMonitor + their implementations (see §12)       │   │
│  └───────────────────────────────────────────────────────────────┘   │
│                                                                      │
│  ┌───────────────────────────────────────────────────────────────┐   │
│  │ :platform  Logger · NetworkUtils · Base64Util                 │   │
│  └───────────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────┘
                      │ MediaCodec / AudioTrack
              ┌───────▼─────────────────────┐
              │   Android OS / Hardware     │
              │  GPU decode, AudioFlinger,  │
              │  SurfaceView, NsdManager    │
              └─────────────────────────────┘

Data flows (sender → receiver):

macOS / iOS  ──[mDNS]─────────►  MdnsService
macOS / iOS  ──[RTSP/TCP:7000]►  RtspHandler ──► VideoDecoder ──► SurfaceView
macOS / iOS  ──[RTP/UDP]──────►  AudioStreamServer ──► AudioPlayer ──► AudioTrack
macOS / iOS  ──[HTTP PUT]─────►  PhotoHandler ──► Bitmap ──► UI
Windows / DLNA sender ──[SSDP]►  :dlna SSDP responder            (Phase 7)
```

---

## 3. Component Responsibilities

Paths are relative to the module named in the second column.

### Service layer (`:app`)

| Component | File | Responsibility |
|---|---|---|
| `CastService` | `service/CastService.kt` | ForegroundService: owns the receiver lifecycle, holds the multicast lock, exposes `StateFlow` for the UI |
| `ServiceController` | `service/ServiceController.kt` | Singleton helper: `start` / `stop` / `restart` from any `Context` |
| `ServiceState` | `service/ServiceState.kt` | Sealed hierarchy: `Running`, `Stopped`, `Restarting`, `Error(msg)` |
| `ConnectionMapping` | `service/ConnectionMapping.kt` | Pure `ProtocolState` → UI decision table, shared by the service and its test |
| `BootReceiver` | `service/BootReceiver.kt` | `BOOT_COMPLETED` receiver; starts the service when the setting is on |
| `SettingsRepository` | `settings/SettingsRepository.kt` | DataStore-backed; `Flow<AppSettings>` and `suspend update {}` |
| `AppSettings` | `settings/AppSettings.kt` | Immutable data class of user settings |

### Protocol — AirPlay (`:airplay`)

| Component | File | Responsibility |
|---|---|---|
| `AirPlayReceiver` | `airplay/AirPlayReceiver.kt` | Orchestrates mDNS, RTSP, and the media pipeline; emits `ProtocolState` |
| `MdnsService` | `airplay/MdnsService.kt` | Registers `_airplay._tcp` + `_raop._tcp` via `NsdManager` |
| `RtspHandler` | `airplay/RtspHandler.kt` | RTSP state machine: OPTIONS → SETUP → ANNOUNCE → RECORD → TEARDOWN, plus the AirPlay HTTP verbs |
| `RtspRequestReader` | `airplay/RtspRequestReader.kt` | Byte-exact request parser; must not consume beyond one request, because the same socket becomes binary interleaved RTP after `RECORD` |
| `handshake/` | 14 classes | `/info`, pair-setup, pair-verify, fp-setup, TLV8, control cipher, PIN pairing |
| `MirrorStreamServer` | `airplay/handshake/MirrorStreamServer.kt` | Interleaved RTP reassembly + AES-128-CTR decryption of the mirroring stream |
| `AudioStreamServer` | `airplay/handshake/AudioStreamServer.kt` | Real-time mirroring audio: UDP RTP, AES-128-CBC, duplicate suppression |
| `AlacDecoder` | `airplay/handshake/AlacDecoder.kt` | JNI bridge to `libalac` |
| `FairPlay` | `airplay/handshake/FairPlay.kt` | JNI bridge to `libplayfair` |
| `VideoDecoder` | `airplay/VideoDecoder.kt` | `MediaCodec` H.264 decode → Surface |
| `SpsParser` | `airplay/media/SpsParser.kt` | Pure-Kotlin SPS parsing, split out so the interesting part is testable without `MediaCodec` |
| `AudioPlayer` | `airplay/AudioPlayer.kt` | Decrypt → decode → `AudioTrack` |
| `PhotoHandler` | `airplay/PhotoHandler.kt` | `PUT /photo` / `DELETE /photo` with size and format validation |
| `DacpClient` | `airplay/DacpClient.kt` | `_dacp._tcp` discovery and reverse transport control |

### Protocol — DLNA (`:dlna`)

Not implemented. Intended structure (`ssdp/`, `http/`, `soap/`, `gena/`, `player/`) is
described in `REQUIREMENTS.md` §1.3.

### Contract layer (`:core`)

| Component | File | Responsibility |
|---|---|---|
| `ProtocolReceiver` | `protocol/ProtocolReceiver.kt` | Lifecycle contract every protocol implements |
| `CastSession` | `protocol/CastSession.kt` | One session, protocol-agnostic; the UI reads only this |
| `CastEvent` / `CastEventBus` | `protocol/CastEvent.kt` | One-shot occurrences, consumed exactly once |
| `ReceiverRegistry` | `protocol/ReceiverRegistry.kt` | Session arbitration, state aggregation, event fan-out |
| `SurfaceSink` | `surface/SurfaceSink.kt` | Brokers rendering targets as leases that can be invalidated |
| `net/*` | see §12 | Network compatibility infrastructure |

### UI layer (`:app`)

| Component | File | Responsibility |
|---|---|---|
| `MainActivity` | `MainActivity.kt` | Single-activity host; binds to `CastService`; supplies the video surface |
| `HomeFragment` | `ui/HomeFragment.kt` | Protocol status cards and service controls |
| `SettingsFragment` | `ui/SettingsFragment.kt` | All settings, saved immediately |
| `StreamingScreen` | `ui/StreamingScreen.kt` | Aspect-fit SurfaceView; portrait streams stay portrait |
| `NowPlayingScreen` | `ui/NowPlayingScreen.kt` | Metadata and artwork overlay |
| `PinScreen` | `ui/PinScreen.kt` | On-screen PIN display for legacy SRP pairing |

### Shared platform (`:platform`)

| Component | File | Responsibility |
|---|---|---|
| `Logger` | `util/Logger.kt` | Timber wrapper; five levels, throwable-aware warn and error |
| `NetworkUtils` | `util/NetworkUtils.kt` | Device name, MAC address, persistent UUID |
| `Base64Util` | `util/Base64Util.kt` | Pure-Kotlin Base64, so SDP parsing is testable without Android |

---

## 4. Libraries

Only libraries that cannot be replaced by Android built-in APIs.

| Library | Version | Justification | Alternative considered |
|---|---|---|---|
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.8.1 | Structured concurrency for all async I/O | Raw threads — too low-level; RxJava — too heavy |
| `com.jakewharton.timber:timber` | 5.0.1 | Tagged, level-filtered logging with pluggable trees | `android.util.Log` directly — cannot filter per-build |
| `org.bouncycastle:bcprov-jdk18on` | 1.78.1 | Ed25519/X25519 for pairing, ChaCha20-Poly1305 for the control channel, SRP-6a for legacy PIN | `javax.crypto` — no SRP, no X25519 on API 25 |
| `com.googlecode.plist:dd-plist` | 1.28 | Binary and XML property lists, used by `/info`, SETUP, and playback-info | Hand-rolled plist codec — error-prone for a binary format |
| `androidx.datastore:datastore-preferences` | 1.1.1 | Async, typed key-value settings | SharedPreferences — blocking I/O |
| `androidx.leanback:leanback` | 1.2.0 | TV focus handling and the on-screen keyboard for the display name | Plain views — no usable text entry on a TV remote |
| `androidx.appcompat`, `androidx.constraintlayout`, `androidx.core-ktx`, `androidx.lifecycle` | 1.7.0 / 2.1.4 / 1.13.1 / 2.8.7 | View-based UI, layout, and lifecycle | — |

**Test scope only:** JUnit 4, MockK, Robolectric, `kotlinx-coroutines-test`.

**Deliberately excluded:**

- No Retrofit/OkHttp — RTSP and RTP are raw TCP/UDP sockets.
- No Room/SQLite — no relational data.
- No Jetpack Compose — View-based UI for maximum TV and D-pad compatibility (ADR-003).
- No Hilt/Dagger — manual composition is sufficient at this size.
- **No Google Play Services of any kind.** This is a scope decision with a build
  consequence: it is what keeps the `googletv` and `firetv` flavors
  dependency-identical.

---

## 5. Security Concept

### 5.1 Input Validation

Every byte from the network is treated as hostile:

- **RTSP messages**: maximum message size enforced (64 KB); a per-line guard stops an
  unterminated header flood. Unknown methods return `501`.
- **Request bodies**: length checked against a per-endpoint ceiling before reading —
  64 KB generally, a larger ceiling for `PUT /photo`, which carries a whole image.
- **SDP body**: length validated before parsing; key lengths asserted byte-exactly.
- **RTP packets**: header length validated; payload size checked against the packet
  before copying.
- **Keys**: length asserted to be exactly 16 bytes (AES-128) before use. A wrong-length
  key is rejected rather than padded.
- **AVCC NAL lengths**: a length that is zero, negative, or larger than the remaining
  payload terminates conversion instead of reading out of bounds.

### 5.2 Permissions minimisation

No permission is requested that is not exercised:

- `RECORD_AUDIO` — not requested; audio is received, not captured.
- `READ_EXTERNAL_STORAGE`, `MANAGE_EXTERNAL_STORAGE`, `INSTALL_PACKAGES` — not requested.
- `CHANGE_WIFI_MULTICAST_STATE` — requested **and used**: `CastService` acquires a
  multicast lock through `RefCountedMulticastLock` while a receiver is advertising
  (FR-07). Declaring it without acquiring the lock was an upstream defect: the
  permission alone does nothing.

### 5.3 No secrets in code

- The device ID (MAC address) is read at runtime — never hardcoded.
- AES session keys arrive per session in ANNOUNCE and are never persisted.
- The Ed25519 pairing seed is generated on first use and stored in app-private
  preferences; it is device identity, not a credential.
- No API keys, tokens, or passwords in the codebase.
- `playfair`'s FairPlay key is compiled into `libplayfair.so`, as it is in every open
  implementation. It is a protocol constant, not a project secret; see `NOTICE` §1.

### 5.4 Exception handling policy

- Every `try`/`catch` logs through `Logger`.
- A caught exception triggers a graceful state reset — never a silent ignore.
- Failures that merely degrade a capability (no multicast lock, no Wi-Fi service) are
  logged at **warn** with their throwable, so a release build filtering by level does
  not lose the stack trace.
- `OutOfMemoryError` and other fatal JVM errors are not caught; they propagate.

### 5.5 Authentication

v1 supports **two** pairing paths, both implemented:

| Path | Mechanism | Used by |
|---|---|---|
| HomeKit-style | Ed25519 identity, X25519 ECDH, TLV8, `pair-setup` / `pair-verify` | macOS 12+, iOS/iPadOS 16+ |
| Legacy SRP | SRP-6a with an on-screen PIN | Older senders, AirPort Express-class devices |

- **PIN auth is off by default.** Any device on the same network can mirror unless the
  user enables it in Settings. This matches other open receivers and is called out in
  the README.
- Failed pair attempts are counted and lock the receiver out after a threshold, so a
  shared network cannot be brute-forced.
- After `pair-verify`, the control channel is encrypted with ChaCha20-Poly1305.

> An earlier revision stated that v1 used "unauthenticated mode" with pairing deferred
> to a later version. That was inherited from upstream and is wrong: both paths ship.

## 6. Performance Goals & Strategies

| Goal | Target | Strategy |
|---|---|---|
| Video latency | ≤ 100 ms | Direct MediaCodec surface output; no intermediate buffer copies |
| Frame rate | ≥ 25 fps | Hardware decode only; I/O on background coroutine; UI on main thread |
| RAM usage | ≤ 150 MB | Fixed-size ring buffers for RTP; MediaCodec manages its own buffers |
| CPU usage | ≤ 30% avg | MediaCodec offloads to GPU; coroutines avoid busy-waiting |
| A/V sync | ≤ 40 ms drift | NTP-based presentation timestamps fed to MediaCodec |

### Thread Model
```
[Main Thread]      UI updates, Surface creation/destruction
[IO Dispatcher]    TCP/UDP socket reads, RTSP parsing
[Default Dispatcher] RTP packet processing, decryption
[MediaCodec]       Runs on its own internal thread (hardware)
[AudioTrack]       Runs on its own internal thread (hardware)
```

No network operation may run on the Main Thread. No UI operation may run off the Main Thread.

---

## 7. Build Flavors

Two product flavors are configured from day one:

| Flavor | applicationId | minSdk | Notes |
|---|---|---|---|
| `googletv` | `tv.opentvcast` | 29 | Google TV / Android TV, Leanback UI, may use newer APIs |
| `firetv` | `tv.opentvcast.firetv` | 25 | Amazon Fire TV, must avoid Google-only APIs |

Shared code lives in `app/src/main/`. Flavor-specific overrides live in `app/src/googletv/` and `app/src/firetv/`.

The `firetv` flavor MUST NOT use any API gated on API level 26+ without a version check at runtime.

---

## 8. Supported AirPlay Feature Flags

The `features` TXT record is a bitmask that tells macOS which AirPlay capabilities the receiver has. opentvcast v1.0 will advertise the following flags (based on the open AirPlay spec):

| Bit | Feature | opentvcast v1 | Notes |
|---|---|---|---|
| 0 | Video | ✅ Supported | H.264 AVC mandatory |
| 1 | Photo | ✅ Supported | JPEG/PNG via `/photo` endpoint — see §9 |
| 2 | VideoFairPlay | ❌ Not supported | Requires Apple FPS license — see §11 |
| 5 | Screen | ✅ Supported | Screen mirroring |
| 6 | Screen Rotate | ✅ Supported | Landscape/portrait |
| 7 | Audio | ✅ Supported | ALAC / AAC-LC / AAC-ELD / LPCM |
| 9 | AudioRedundant | ✅ Supported | |
| 14 | AudioSyncedVideo | ✅ Supported | A/V sync via NTP |
| 23 | HasUnifiedAdvertiserInfo | ✅ Supported | |
| 26 | SupportsAirPlayVideoV2 | ✅ Supported | |
| 27 | MetaDataFeatures_0 | ✅ Supported | |

The resulting hex value for the `features` field: `0x5A7FFFF7,0x1E` (will be refined during implementation to include the Photo bit).

---

## 9. Photo / Image Sharing via AirPlay

### Overview

AirPlay supports sending individual still images (JPEG or PNG) from macOS/iOS to a receiver. This is distinct from video mirroring: no RTSP session is involved; instead, the sender makes an HTTP `PUT` or `POST` request to the receiver's `/photo` endpoint.

### Protocol Flow

```
macOS/iOS                        opentvcast (Android TV)
    │                                    │
    │── PUT /photo HTTP/1.1 ───────────► │
    │   Content-Type: image/jpeg         │
    │   X-Apple-AssetKey: <uuid>         │
    │   Content-Length: <N>              │
    │   [JPEG body]                      │
    │◄─ HTTP/1.1 200 OK ────────────────│
    │                                    │
    │   [photo displayed on TV]          │
    │── DELETE /photo HTTP/1.1 ─────────►│  "Clear the image"
    │◄─ HTTP/1.1 200 OK ────────────────│
```

The HTTP server listens on the same port as the RTSP server (port 7000) or a separate HTTP port (to be decided during implementation). The `RtspHandler` is extended to detect non-RTSP requests (HTTP verbs `PUT`, `GET`, `DELETE`) and route them to a new `PhotoHandler` component.

### Android Implementation

```
HTTP PUT /photo → PhotoHandler.receive(inputStream, contentType, contentLength)
  → BitmapFactory.decodeStream(inputStream)   // JPEG or PNG
  → PhotoScreen (full-screen ImageView)
  → BitmapDrawable displayed via ImageView.setImageBitmap()
```

No video decoder or MediaCodec is used. Image decoding runs on the `IO Dispatcher` (never on Main Thread). Display update runs on the `Main Thread`.

### Supported Image Formats

| Format | MIME Type | Android API | Notes |
|---|---|---|---|
| JPEG | `image/jpeg` | All API levels | Most common from AirPlay |
| PNG | `image/png` | All API levels | Transparency support |
Anything else — HEIC/HEIF in particular — is rejected with HTTP 415. HEIC support is
_not_ implemented (planned for v2); an earlier revision of this file described a
`BitmapFactory.isSupportedMimeType` gate that does not exist in `PhotoHandler`.

### New Component: `PhotoHandler`

| Component | File | Responsibility |
|---|---|---|
| `PhotoHandler` | `airplay/PhotoHandler.kt` | Receives HTTP PUT requests; decodes JPEG/PNG via BitmapFactory; emits Bitmap to UI |
| `PhotoScreen` | `ui/PhotoScreen.kt` | Full-screen Fragment with ImageView; displayed when a photo is received |

### Memory Management

- Images are loaded into `Bitmap` objects. Large images (e.g., 4K JPEG) are downsampled using `BitmapFactory.Options.inSampleSize` to fit the display resolution.
- Bitmaps are recycled immediately when the photo session ends or a new image replaces the previous one.
- Maximum loaded image size: display resolution (e.g., 1920×1080 pixels), never larger.

---

## 10. Codec matrix

### 10.1 Video

| Codec | AirPlay 2 | DLNA | Android API | Hardware required |
|---|---|---|---|---|
| H.264 AVC (Baseline / Main) | ✅ Required | ✅ Required | API 16+ | Yes (`MediaCodec`) |
| H.264 AVC (High Profile, up to L5.2) | ✅ Required | — | API 16+ | Yes |
| H.265 HEVC | ⬜ v2, capability-gated | ⬜ v2 | API 21+ | Yes |
| MPEG-4 Part 2 | — | ⬜ Optional | API 16+ | Device-dependent |
| MPEG-2 | — | ⬜ Optional | API 16+ | Device-dependent |

There is **no software decoder fallback**. When no hardware decoder is available, the
stream is rejected gracefully rather than played badly. Runtime capability check for
optional codecs:

```kotlin
val codecList = MediaCodecList(MediaCodecList.SECURE_CODECS_ONLY)
val format = MediaFormat.createVideoFormat(mimeType, width, height)
val isSupported = codecList.findDecoderForFormat(format) != null
```

### 10.2 Audio

| Codec | AirPlay 2 | DLNA | Notes |
|---|---|---|---|
| ALAC 16-bit | ✅ Required | — | Native `libalac`; macOS system audio and RAOP music |
| AAC-ELD | ✅ Required | — | Mirroring audio, low latency |
| AAC-LC | ✅ Required | ✅ Required | `MediaCodec` `audio/mp4a-latm` |
| LPCM / WAV | ✅ Required | ✅ Required | Direct to `AudioTrack` |
| MP3 | — | ✅ Required | `MediaCodec` `audio/mpeg` |
| FLAC | — | ⬜ Optional | `MediaCodec` `audio/flac`, API 21+ |
| AC-3 (Dolby Digital) | ⬜ v2 | ⬜ Optional | `AudioFormat.ENCODING_AC3` |
| E-AC-3 / Dolby Atmos (JOC) | ⬜ v2 | — | `AudioFormat.ENCODING_E_AC3_JOC` |
| Buffered Audio (AirPlay 2 type 103) | Accepted, not played | — | Instrumented for a later phase |

### 10.3 Containers

| Container | AirPlay 2 | DLNA | Notes |
|---|---|---|---|
| RTP / RTSP | ✅ Required | — | Live mirroring transport |
| SDP-described streams | ✅ Required | — | ANNOUNCE / SETUP |
| MP4 / ISOBMFF | ✅ Required | ✅ Required | |
| MOV | ✅ Required | — | Apple QuickTime |
| M4V | ✅ Required | — | iTunes video |
| MPEG-TS | — | ✅ Required | Common DLNA push container |
| AVI | — | ✅ Required | Common DLNA push container |
| HLS | ⬜ v2 | ⬜ v2 | Adaptive bitrate |
| DASH | — | — | Out of scope |

### 10.4 Resolution and HDR

| Protocol | Required max | Deferred | HDR |
|---|---|---|---|
| AirPlay 2 | 1080p @ 60 fps | 4K UHD @ 60 fps | HDR10, Dolby Vision — v2, API 31+ |
| DLNA | 1080p @ 60 fps | 4K UHD | — |

Portrait streams (phones) are rendered portrait. The decoded dimensions come from the
SPS, not from an assumed 16:9, so the surface is aspect-fit rather than stretched.

### 10.5 DLNA status

The `:dlna` module contains no implementation. Its build configuration exists, the
settings toggle and status card are wired to it, and the wire behaviour it must
implement is specified in `REQUIREMENTS.md` §1.3. Treat any DLNA claim elsewhere in the
documentation as a target, not a description.

---

## 11. Copy protection and DRM

### 11.1 HDCP — not applicable

HDCP is negotiated at the Wi-Fi Display level and exists only for Miracast, which is out
of scope. Nothing in opentvcast implements or requires it.

### 11.2 Widevine and PlayReady — not applicable

These are handled entirely by the Google Cast SDK, and Cast is out of scope. No DRM
logic of any kind is implemented for them.

### 11.3 FairPlay — two mechanisms, only one of them out of scope

Earlier revisions of this document conflated these and concluded, incorrectly, that
FairPlay was "not implemented". The distinction matters, and the project's licence turns
on it.

**(a) AirPlay session-key exchange (`/fp-setup`) — IMPLEMENTED.**

Every AirPlay 2 mirroring session is gated behind a per-session key exchange. Without it
the receiver cannot decrypt the video stream at all, so it is not optional. Implemented
through the native `libplayfair`; `FairPlay.kt` is the JNI bridge.

Session *establishment* being encrypted is not the same as *content* being protected:
the key exchange produces the AES key for the transport, and what flows through it is
ordinary screen content.

This is the sole reason opentvcast is GPLv3 rather than a permissive licence. `playfair`
is GPL, every publicly available FairPlay implementation is, and the project does not own
the component well enough to relicense it. See `NOTICE` §1.

**(b) FairPlay Streaming (FPS) content DRM — NOT IMPLEMENTED, not planned.**

FPS is Apple's content DRM for HLS, used by Apple TV+, iTunes purchases, and other
Apple-licensed platforms. It is a different mechanism from (a).

| Aspect | Assessment |
|---|---|
| Technical feasibility on Android | Not feasible — requires an Apple-supplied KSM binary, and Apple has never released an Android-compatible one |
| Licence compatibility | Incompatible with GPLv3 redistribution even if a binary existed |
| Scope of impact | Premium DRM-protected content only (Apple TV+, iTunes purchases) |
| Unencrypted and session-encrypted AirPlay | Fully supported |
| Workaround | None in scope — such content will not play |

Full evaluation in `REQUIREMENTS.md` §3.

---

## 12. Network compatibility infrastructure

The failure modes this section exists to prevent are all *silent*. In each case the app
starts, logs nothing, and simply does not appear to the sender:

| Failure | Symptom |
|---|---|
| No multicast lock | Receiver invisible; mDNS registration reports success |
| Advertised address unreachable | Visible in the picker, every connection attempt times out |
| Hardcoded port already bound | Protocol refuses to start |
| Stale address after a network change | Visible and connectable-in-theory, times out; only an app restart clears it |

Upstream had all four. Their fix is a single choke point in `:core`, expressed as four
contracts that protocol modules reach through `ReceiverEnvironment`. Because they live in
`:core` — a plain Kotlin/JVM module — all four are implemented and tested without a
device or an emulator.

| Contract | Implementation | Why the counting/preference exists |
|---|---|---|
| `MulticastLockHandle` | `RefCountedMulticastLock` over `PlatformMulticastLock` | mDNS advertising and DACP discovery have different lifetimes; first-release-wins would leave one deaf. Idempotent per owner tag, so a restart without a release cannot leak the lock. |
| `PortAllocator` | `ServerSocketPortAllocator` over `PortProbe` | A preferred port is a request. Substitution is normal, and callers must advertise the granted port. |
| `InterfaceSelector` | `DefaultInterfaceSelector` over `LocalAddress` | Ethernet > Wi-Fi; site-local > global; IPv4 > IPv6. Excludes down, loopback, virtual and point-to-point interfaces. |
| `NetworkMonitor` | `CoalescingNetworkMonitor` | Reports a change only after it settles, so an interface coming up causes one re-advertise rather than four. A flap that returns to the last advertised identity causes none. |

### 12.1 Design notes worth keeping

**Counting is by owner tag, not by call.** Two independent consumers need the multicast
lock and they do not start or stop together. A reference count that increments per call
would let a receiver that restarts without a matching release accumulate references
forever; a set of owners cannot.

**In-process reservations outrank probing.** The probe asks the OS whether a port is
bindable, but the owner that reserved a port may not have bound it yet. Without the
reservation table, two receivers would both be told the same port is free.

**Elapsed time is accumulated, not read from a clock.** `CoalescingNetworkMonitor`
counts settled time by summing poll intervals rather than calling `System.nanoTime()`.
A monotonic clock reading stays real even under a coroutine test dispatcher's virtual
time, which would make every timing assertion a sleep.

**The trigger is separate from the policy.** Polling is used today because it is correct
on every device, including TVs whose Wi-Fi driver does not deliver `ConnectivityManager`
callbacks. An Android adapter can later drive the same coalescing policy from
`NetworkCallback` without changing the policy or its tests.

### 12.2 What is not yet wired

- `CoalescingNetworkMonitor` has no Android trigger; nothing calls it in `:app` yet.
- `DefaultInterfaceSelector` and `ServerSocketPortAllocator` have no consumer: the
  AirPlay path still takes its address from `NetworkUtils` and hardcodes its RTSP port.
- There is no `ReceiverEnvironment` implementation composing all four.

These are the remaining Phase 2 items in `PROJECT_PLAN.md`, and they are the
prerequisite for Phase 7 — DLNA needs a port and an address on its first day.
