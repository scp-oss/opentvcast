---
name: Feature Request
about: Suggest a new feature or improvement for opentvcast
title: "[FEATURE] "
labels: enhancement
assignees: ''
---

## Summary

<!-- A one-sentence description of the feature you'd like. -->

## Problem / Motivation

<!-- What problem does this feature solve? Why do you need it?
     Example: "I often want to... but currently I have to..." -->

## Proposed Solution

<!-- How should this feature work? Be as specific as possible.
     If you have multiple ideas, list the one you prefer first. -->

## Alternatives Considered

<!-- Have you considered any alternatives? Why do you prefer your proposed solution? -->

## Scope Check

Before requesting a feature, please confirm it is within opentvcast's scope.

**Already in scope** — AirPlay 2 (macOS 12+ and iOS/iPadOS 16+ senders) and DLNA.

**Deliberately out of scope for v1** — Google Cast (would pull in Google Play
Services, which Fire TV does not have), Miracast (Wi-Fi Direct support varies too
much across TV hardware), and FairPlay Streaming *content* DRM (Apple TV+, iTunes
purchases — this needs a binary Apple never ships for Android; the AirPlay
session-key exchange is a different mechanism and *is* implemented).

- [ ] It works over the local network (no internet required)
- [ ] It doesn't add advertisements or analytics
- [ ] It doesn't need Google Play Services on the Fire TV flavor
- [ ] It doesn't need FairPlay Streaming content DRM
- [ ] If it is a new protocol, it can be added as its own Gradle module

## Additional Context

<!-- Any other context, mockups, or references that would help explain the feature. -->
