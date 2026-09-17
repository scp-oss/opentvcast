# ADR-001: Multi-Protocol Support (AirPlay + Miracast + Cast)

**Date:** 2026-03-23
**Status:** Accepted by upstream PhairPlay — **partly superseded** for opentvcast, see the amendment

---

## Amendment: opentvcast v1 ships two protocols, not three

Upstream's decision below stands as history, and its *structure* — one independent
component per protocol, enabled independently in Settings — is exactly what
opentvcast adopted. The *roster* changed.

**opentvcast v1 ships AirPlay 2.** Miracast and Google Cast are out of scope:

- **Google Cast** is the only feature that requires Google Play Services. Dropping it
  keeps the Google TV and Fire TV flavors dependency-identical, so the Fire TV build
  never has to degrade gracefully around a missing GMS.
- **Miracast** needs Wi-Fi Direct, whose availability and behaviour vary enough across
  TV hardware that supporting it well costs more than it returns — most senders offer
  AirPlay or Cast when both are available.

**DLNA is the planned second protocol**, not a shipped one. It is a plain UPnP AV
MediaRenderer — no GMS, no Wi-Fi Direct, no vendor-specific permissions — and it
covers the Windows/Android senders Miracast was there to serve. The `:dlna`
module exists as a scaffold (build config only, no source), so adding it is
filling in an implementation rather than re-plumbing the service.

The consequence for the code is the same as upstream intended, which is why this is an
amendment rather than a new ADR: adding a protocol is a new module plus one
`register()` call, because `ProtocolKind` is an open `value class` and the UI renders
from `ProtocolCapabilities` rather than branching on a protocol enum.

The spec files carry their own scope notes: `REQUIREMENTS.md` and
`PROJECT_PLAN.md` name Miracast and Cast explicitly as *not* planned, and
`TECHNICAL_SPEC.md` marks DLNA as not implemented.

---

## (Historical) Context, Decision, Rationale, Consequences

Everything below this line is upstream's three-protocol decision, retained as
history. **The amendment above is authoritative for scope.** Read the
Consequences section accordingly: the Cast SDK size estimate and the Wi-Fi P2P
permissions it lists do not apply, because neither protocol shipped.

### Context

PhairPlay v1.0 was scoped to AirPlay 2 only (macOS senders). User feedback indicated demand for Miracast (Windows/Android senders) and Google Cast (Chrome/Android senders). Supporting all three makes the product a universal wireless display receiver.

### Decision

Support all three protocols simultaneously:
- **AirPlay 2** — for macOS and future iOS senders
- **Miracast (WFD)** — for Windows 10+ and Android senders
- **Google Cast** — for Chrome, Android, and iOS senders

Each protocol is implemented as an independent component that can be enabled/disabled via Settings.

### Rationale

1. **User experience**: Users should not need to know which protocol their sender uses. opentvcast simply works.
2. **Independence**: Protocols don't share network ports or state. One can fail without affecting others.
3. **Graceful degradation**: If a protocol is unavailable (e.g., Cast on Fire TV without GMS), it is hidden in the UI.

### Consequences

- Adds ~3 new package directories (`airplay/`, `miracast/`, `cast/`)
- Increases APK size by ~2-5 MB (Cast SDK dependency)
- Fire TV flavor must gracefully handle missing Google Play Services
- Miracast requires `CHANGE_WIFI_STATE` and `ACCESS_FINE_LOCATION` permissions (Wi-Fi P2P)

### Alternatives Considered

1. **AirPlay-only** — simpler, but limits audience to macOS users only.
2. **AirPlay + Miracast, no Cast** — reduces dependencies but misses Chrome users.
