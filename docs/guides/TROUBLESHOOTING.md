# Troubleshooting

---

## Device not appearing in the AirPlay menu

DLNA is not implemented yet, so this section is AirPlay only.

**Cause 1: Not on the same network**
- Ensure your Mac/iPhone/iPad and the TV are on the **same network** (same router, same subnet).
- If your router exposes 2.4 GHz and 5 GHz as separate SSIDs, make sure both devices use the same one.

**Cause 2: AP isolation / client isolation**
- Some routers prevent clients from seeing each other.
- Log into your router and disable "AP Isolation", "Client Isolation", or "Wireless Isolation".

**Cause 3: Multicast filtering**
- mDNS needs multicast. Some routers block it.
- Look for "Enable Multicast", "IGMP Snooping", or "mDNS" in the router's advanced settings.
- On the app side this is handled: a multicast lock is held while advertising, and the
  manifest declares `CHANGE_WIFI_MULTICAST_STATE` for exactly that reason. If the
  problem persists it is the router, not the app.

**Cause 4: The service is stopped**
- Check the home screen: the protocol cards should read **Advertising**.
- If stopped, press **Start**, or **Restart** on the service card.

**Cause 5: The Android TV killed the app**
- Some TVs aggressively stop background apps. The foreground-service notification
  should prevent this; if the notification is gone, the service is gone.
- Check Settings → Apps → opentvcast, and exempt it from battery optimisation if the
  TV offers that setting.

---

## Connected but black screen

**Cause 1: FairPlay-protected content**
- Apple TV+ and iTunes purchases use Apple's content DRM, which no open receiver can
  decrypt. See the FairPlay section of the README for why.
- Netflix, Disney+ and similar also block mirroring of protected video, through the
  platform's own DRM rather than Apple's.
- This is not something opentvcast can fix, and it does not affect ordinary content.

**Cause 2: MediaCodec decoder unavailable**
- Rare: a few cheap Android TV boxes lack H.264 hardware decode.
- There is deliberately no software fallback — a software H.264 decoder cannot keep up
  with a mirroring stream, so playing badly is worse than refusing.
- Check the log: `tools/collect-device-logs.sh`, then look for `MediaCodec` errors.

---

## High latency (>200ms)

1. Switch from 2.4 GHz Wi-Fi to **5 GHz Wi-Fi** or **Ethernet**.
2. Move the TV closer to the router.
3. Check if other devices are using the same Wi-Fi band heavily.

---

## Audio out of sync

1. Try stopping and restarting the stream from your Mac.
2. Restart the opentvcast service (HomeScreen → Restart button).
3. If persistent, check logcat for NTP timing errors.

---

## App crashes on startup

1. Check you are using the correct flavor APK for your device — `googletv` for Android
   TV, `firetv` for Amazon Fire TV. They have different `applicationId`s:
   `tv.opentvcast` and `tv.opentvcast.firetv`.
2. Try reinstalling:
   ```bash
   adb uninstall tv.opentvcast          # or tv.opentvcast.firetv
   adb install app/build/outputs/apk/googletv/debug/app-googletv-debug.apk
   ```
3. Capture logs with `tools/collect-device-logs.sh` and open an issue.

---

## Still stuck?

Open an issue in this repository with:
- Your TV model and OS version
- Which protocol you were trying to use
- What happened, and what you expected
- The `summary.txt` and `logcat.txt` that `tools/collect-device-logs.sh` writes
  (it filters logcat by process id, which is more reliable than filtering by tag —
  logging goes through Timber, so the tag is the calling class name)
