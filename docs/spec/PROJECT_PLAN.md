# opentvcast – Project Plan

Version: 3.0
Status: Active
Date: 2026-09-16
Scope: v1 (AirPlay 2 + DLNA)

---

## Scope

v1 delivers an AirPlay 2 receiver on Google TV and Fire TV. DLNA / UPnP is the second
protocol and is **specified but not implemented**. Miracast and Google Cast are out of
scope — see `REQUIREMENTS.md` §0 for why, in one paragraph each.

## Delivery order

```
 Phase 0   Specification + module skeleton        ✅
 Phase 1   Service, UI, build flavors             ✅
 Phase 2   Network compatibility infrastructure   ✅
 Phase 3   Discovery (mDNS)                       ✅
 Phase 4   AirPlay handshake + photo endpoint     ✅
 Phase 5   AirPlay video                          ✅
 Phase 6   AirPlay audio                          ✅
 Phase 7   DLNA / UPnP MediaRenderer              ⬜ next
 Phase 8   Stability + device validation          🚧
 Phase 9   i18n completion                        ⬜
 Phase 10  Release                                ⬜
```

The compatibility infrastructure comes before discovery in this order because
discovery is what silently fails without it: a receiver that never took the
multicast lock is invisible on some firmware and logs nothing. The infrastructure
phase therefore gates the discovery phase, not the other way round.

## Status overview

| # | Phase | Status | Notes |
|---|---|---|---|
| 0 | Specification | ✅ | Rewritten for v1 scope |
| 1 | Service, UI, flavors | ✅ | `CastService`, home/settings/streaming screens, googletv + firetv |
| 2 | Network compatibility | ✅ `:core`, 🚧 adapters | All four contracts implemented and tested in `:core`; only the multicast lock has an Android adapter wired in |
| 3 | Discovery (mDNS) | ✅ | `MdnsService` (`_airplay._tcp`, `_raop._tcp`) |
| 4 | AirPlay handshake | ✅ | `/info`, pair-setup, pair-verify, fp-setup, encrypted SETUP, photo PUT/DELETE |
| 5 | AirPlay video | ✅ | RTSP interleaved RTP, AES-128-CTR, H.264 → `MediaCodec`, SPS-driven reinit |
| 6 | AirPlay audio | ✅ | AAC-ELD / AAC-LC / ALAC; RAOP retransmit; A/V sync via NTP |
| 7 | DLNA | ⬜ | Module and build config only |
| 8 | Stability + device validation | 🚧 | Unit-tested; **no real-device validation has been recorded** |
| 9 | i18n | ⬜ | en complete; de / fr partial |
| 10 | Release | ⬜ | Nothing published |

---

## Phase 0 – Specification ✅

**Deliverable:** a specification set that describes what ships, not what upstream
intended.

- [x] `REQUIREMENTS.md` — FR/NFR set for AirPlay 2 + DLNA
- [x] `TECHNICAL_SPEC.md` — protocol and system architecture
- [x] `ACCEPTANCE_CRITERIA.md` — verifiable criteria per milestone
- [x] `PROJECT_PLAN.md` — this document
- [x] Scope narrowed and Miracast / Cast content removed rather than annotated

## Phase 1 – Service, UI, build flavors ✅

- [x] `CastService` as a `ForegroundService`, with start/stop/restart intents and a
      persistent notification
- [x] State exposed as `StateFlow`; UI renders from `ProtocolCapabilities` rather than
      branching on a protocol enum
- [x] Home screen with per-protocol status cards and service controls
- [x] Settings screen: display name, protocol toggles, PIN auth, resolution, audio,
      start-on-boot, debug overlay
- [x] Streaming screen (aspect-fit SurfaceView), now-playing overlay, PIN screen
- [x] Two flavors: `googletv` (API 29) and `firetv` (API 25)
- [x] `ConnectionMapping` extracted so the service and its test share one state table

## Phase 2 – Network compatibility infrastructure ✅

**Goal:** every failure mode that makes a receiver invisible, unreachable, or stuck on
a stale address, handled in one place.

Implemented in `:core`, which is a plain Kotlin/JVM module — so all four are testable
without a device, and protocols reach them only through `ReceiverEnvironment`.

| Contract | Implementation | Tests |
|---|---|---|
| `MulticastLockHandle` | `RefCountedMulticastLock` + `PlatformMulticastLock` | 12 |
| `PortAllocator` | `ServerSocketPortAllocator` + `PortProbe` | 21 |
| `InterfaceSelector` | `DefaultInterfaceSelector` + `LocalAddress` | 22 |
| `NetworkMonitor` | `CoalescingNetworkMonitor` | 20 |

- [x] Multicast lock, reference-counted by owner tag (FR-07 … FR-10)
- [x] Port allocation with substitution and per-owner release (FR-19 … FR-23)
- [x] Address selection: Ethernet > Wi-Fi, site-local > global, IPv4 > IPv6 (FR-11 … FR-13)
- [x] Coalesced network-change detection (FR-14 … FR-18)
- [x] Android adapter for the multicast lock, wired into `CastService`
- [ ] Android adapter for `NetworkMonitor` (`ConnectivityManager.NetworkCallback`)
      driving `CoalescingNetworkMonitor` as its trigger
- [ ] A `ReceiverEnvironment` implementation that composes all four and is passed to
      protocols on `start`

**Definition of done:** a protocol can obtain a rendering target, reserve a port,
select an address and be told about network changes without importing a single
`android.*` symbol. Not yet reached — the last two items above are what remain, and
they are the prerequisite for Phase 7 (DLNA needs a port and an address on day one).

## Phase 3 – Discovery (mDNS) ✅

- [x] Register `_airplay._tcp` and `_raop._tcp`
- [x] TXT records: `deviceid`, `features`, `flags`, `pk` (Ed25519 public key), `pi`
- [x] Device name from settings, falling back to the Android device name
- [x] Honest failure: registration errors are logged rather than retried silently
- [ ] Re-registration on network change (depends on Phase 2's monitor adapter)

## Phase 4 – AirPlay handshake + photo endpoint ✅

- [x] `GET /info` capability response (binary plist)
- [x] Pair-setup / pair-verify, HomeKit-style Ed25519 + X25519
- [x] Legacy SRP PIN pair-setup with on-screen PIN and attempt lockout
- [x] `fp-setup` v2/v3 and legacy `rsaaeskey` recovery via `libplayfair`
- [x] ChaCha20-Poly1305 on the control channel after pair-verify
- [x] `PUT /photo` and `DELETE /photo` with size limits and format validation

## Phase 5 – AirPlay video ✅

- [x] Interleaved RTP reassembly (single NAL and FU-A)
- [x] AES-128-CTR stream decryption with a continuous keystream
- [x] AVCC → Annex-B conversion
- [x] `VideoDecoder` with SPS/PPS-driven reinit, keyframe resync, decode-queue backpressure
- [x] Aspect-fit rendering, portrait streams rendered portrait

## Phase 6 – AirPlay audio ✅

- [x] Mirroring audio: AAC-ELD / AAC-LC via `MediaCodec`, AES-128-CBC
- [x] RAOP / music path: ALAC via native `libalac`, per-packet IV
- [x] RTP duplicate suppression and retransmit handling
- [x] NTP-based A/V synchronisation
- [x] Decode-health mute guard (a wrong key produces silence, not noise)
- [x] Buffered audio (type 103) accepted and instrumented, not played back

## Phase 7 – DLNA / UPnP MediaRenderer ⬜

**Deliverable:** a conformant `MediaRenderer` that Windows and Android senders can push to.

- [ ] SSDP responder: `M-SEARCH` on UDP 1900, `ssdp:alive` / `ssdp:byebye`
- [ ] Device description + SCPD served over an allocated port (Phase 2's allocator)
- [ ] SOAP control: `AVTransport`, `RenderingControl`, `ConnectionManager`
- [ ] GENA subscription and `LastChange` notifications
- [ ] Transport state machine driving a URL media player
- [ ] Status card wired to a real receiver
- [ ] Unit tests for the SSDP parser, SOAP envelope handling, and the state machine

**Definition of done:** Windows "Cast to Device" lists the TV and can push a video that
plays, pauses, seeks, and stops from the sender's UI.

## Phase 8 – Stability + device validation 🚧

- [x] `SupervisorJob` isolation so one protocol failing to bind does not stop the others
- [x] App-swipe-away stops receivers rather than leaving a zombie service
- [x] Idempotent `ACTION_START` (a recreated Activity does not start a second receiver)
- [ ] **Real-device validation on Google TV and Fire TV hardware — none recorded**
- [ ] Verify the multicast lock actually changes discovery behaviour on a device that
      needs it (the fix is in, the evidence is not)
- [ ] Verify a release build (R8-shrunk) still resolves the JNI symbols at runtime
- [ ] Measure the NFR performance targets on real hardware

**This is the largest gap in the project.** Every test in the suite is a JVM unit test.
RTSP, mirroring, pairing and the native libraries have never been exercised on a TV.

## Phase 9 – i18n completion ⬜

- [x] All strings in resources; no hardcoded user-visible text
- [x] `de` and `fr` resource directories
- [ ] Complete `de` and `fr`
- [ ] Add the remaining target languages
- [ ] Re-enable lint's `MissingTranslation` as an error

## Phase 10 – Release ⬜

- [ ] `:app:assembleRelease` verified on a device (R8 rules build, but are unproven at runtime)
- [ ] Signing credentials provisioned (`scripts/release.sh --setup`)
- [ ] CI green on the full module test set, not just `:test-runner`
- [ ] Tag and publish the first opentvcast release

---

## Milestone summary

| # | Phase | Key deliverable | Status | Criteria |
|---|---|---|---|---|
| 0 | Specification | Accurate v1 spec set | ✅ | AC-0.x |
| 1 | Service + UI | Foreground service and TV UI | ✅ | AC-1.x |
| 2 | Network compatibility | Four contracts implemented and tested | ✅ `:core` | AC-2.x |
| 3 | Discovery | Receiver visible to senders | ✅ | AC-3.x |
| 4 | Handshake | Sender completes pairing and SETUP | ✅ | AC-4.x |
| 5 | Video | Mirroring renders | ✅ | AC-5.x |
| 6 | Audio | Audio plays in sync | ✅ | AC-6.x |
| 7 | DLNA | Sender can push media | ⬜ | AC-7.x |
| 8 | Stability | Runs for 30 minutes without loss | 🚧 | AC-8.x |
| 9 | i18n | Locales complete | ⬜ | AC-9.x |
| 10 | Release | Signed release APK | ⬜ | AC-10.x |

## Risk register

| Risk | Impact | Likelihood | Mitigation |
|---|---|---|---|
| **No device validation** | High — the suite cannot see a decoder or socket bug | Certain (already true) | Phase 8 is the priority; the test suite is a floor, not a ceiling |
| Real-device multicast still filtered after the lock | Medium — discovery fails on some vendor | Medium | FR-10 degradation path; log the lock state in the debug overlay so a report shows it |
| DLNA scope creep | Medium — UPnP is large and has many optional profiles | Medium | Implement exactly the three services in FR-34; no optional profiles in v1 |
| R8 strips something the mirroring path needs | High — release-only failure | Low | Only the JNI bridges are name-sensitive, and they are pinned; verify on device in Phase 8 |
| A protocol reaching for Android APIs directly | High — restores the coupling the module split exists to prevent | Low | `:core` cannot see `android.*`, so it fails at compile time |
| A protocol bypassing `ReceiverEnvironment` | High — restores the coupling the module split exists to prevent | Low | The interface exists (`DefaultReceiverEnvironment`, `:platform`'s `AndroidReceiverEnvironment`); review rejects a protocol that reaches for `Context` instead |
| Upstream drift | Low — this is a divergent fork | Low | Attribution in `NOTICE` §2 is the record of the divergence |
