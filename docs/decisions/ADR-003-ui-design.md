# ADR-003: Google TV Streamer UI Design Language

**Date:** 2026-03-23
**Status:** Accepted

---

## Context

opentvcast runs on TVs controlled via D-pad remote controls. The UI must be:
- Navigable with a 5-button remote (Up/Down/Left/Right/OK)
- Readable from 3 meters on large displays
- Visually consistent with the Google TV system UI

## Decision

Adopt the **Google TV Streamer** design language:
- True black (`#000000`) background, dark surface cards (`#1C1C1E`)
- Card-based layout with prominent focus rings
- Large typography (body ≥ 18sp, titles ≥ 32sp)
- Blue accent (`accent_blue` = `#1A73E8`, pressed variant `#1557B0`) for active/focus states
- Status dots (green = running, red = stopped, yellow = transitioning, gray = disabled)
- Side navigation drawer for screen switching (Home / Settings)
- Service status cards with icon, name, state, and active sender info

## Implementation

- View-based (not Jetpack Compose) for maximum TV API compatibility
- No `androidx.leanback` dependency: the views are plain View-based layouts, and the
  Leanback entry was removed from the build as unused
- Manual D-pad focus management via `nextFocusDown/Up/Left/Right` XML attributes
- All interactive elements use `@drawable/focus_ring_selector` for consistent focus feedback

## Alternatives Considered

1. **Jetpack Compose** — modern but has known D-pad focus issues; SurfaceView integration is complex.
2. **Material Design 3** — designed for touch; focus rings not optimized for TV remotes.
3. **Full Leanback** — too opinionated; GuidedStepFragment is overkill for a simple app.
