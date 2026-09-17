---
name: Bug Report
about: Something is not working as expected
title: "[BUG] "
labels: bug
assignees: ''
---

## Bug Description

<!-- A clear, concise description of what the bug is. -->

## Steps to Reproduce

1. Go to '...'
2. Click on '...'
3. See error

## Expected Behavior

<!-- What you expected to happen. -->

## Actual Behavior

<!-- What actually happened. -->

## Device Information

**TV Device:**
- Device model: (e.g., Chromecast with Google TV 4K)
- Platform: [ ] Google TV  [ ] Fire TV
- Android/Fire OS version: (e.g., Android 12 / Fire OS 7.3.2)
- opentvcast version: (e.g., 1.0.0)

**Mac (AirPlay sender):**
- macOS version: (e.g., macOS 14.4 Sonoma)

**Network:**
- Connection type: [ ] Wi-Fi 2.4 GHz  [ ] Wi-Fi 5 GHz  [ ] Ethernet
- Router model (if known):

## Logs

<!--
If you can reproduce the bug, please attach the logcat output.

The easiest way is the bundled script, which also captures package state, memory
and CPU usage:

  tools/collect-device-logs.sh

Do NOT filter logcat by tag. Logging goes through Timber's DebugTree, which
derives the tag from the calling class name, so there is no single tag that
matches — the script filters by process id instead.
-->

```
Paste logcat output here
```

## Additional Context

<!-- Any other context, screenshots, or information that might be helpful. -->
