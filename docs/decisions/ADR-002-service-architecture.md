# ADR-002: ForegroundService Architecture

**Date:** 2026-03-23
**Status:** Accepted

> The receivers named in this record have changed since it was written — v1 ships
> AirPlay 2 rather than AirPlay, Miracast and Cast (DLNA is planned as a second
> protocol and has no implementation yet). The **decision** is unaffected: it is
> about where the receivers live, not how many there are. See the amendment in
> [ADR-001](ADR-001-multi-protocol.md).

---

## Context

The protocol receivers need to run continuously in the background — even when the user switches to a screensaver or a different app. Android may kill background processes. A ForegroundService with a persistent notification is the correct pattern for long-running background operations in Android.

## Decision

Implement `CastService` as an Android `ForegroundService`:
- Shows a persistent notification with status and quick actions (Stop, Restart)
- `ServiceController` provides a clean API from the UI layer to the service
- `MainActivity` binds to the service to receive state updates for the UI
- Service survives Activity lifecycle (rotation, screensaver, task switch)

## Architecture

```
MainActivity / HomeFragment
      │  bind()
      ▼
CastService (ForegroundService)
  ├── AirPlayReceiver
  └── DlnaReceiver            (planned; :dlna has no source yet)
```

How many receivers sit under the service is not part of this decision: the service
enumerates whatever has been registered, so adding DLNA is a new module plus one
registration rather than an edit here. See ADR-001's amendment.

## Consequences

- Requires `FOREGROUND_SERVICE` permission
- Requires a persistent notification (Android 8+ requirement for foreground services)
- Slightly more complex than a simple Activity-owned receiver
- The service is also the natural owner of process-wide resources — the multicast lock
  lives here for that reason, since it must outlive any single protocol's advertising
- Service can be started via `start on boot` BroadcastReceiver in the future

## Alternatives Considered

1. **Activity-bound only** — simple but dies when Activity is backgrounded.
2. **WorkManager** — for periodic work, not continuous background operation.
3. **JobScheduler** — not suitable for a persistent network server.
