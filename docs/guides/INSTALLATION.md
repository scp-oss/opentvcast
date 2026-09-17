# Installation Guide

This guide covers every way to install opentvcast on your Android TV or Fire TV device.

---

## Prerequisites

- A Google TV or Fire TV device (see [supported devices](../spec/REQUIREMENTS.md))
- A computer (Windows, macOS, or Linux) with ADB installed — OR — a direct APK sideload method
- Both devices on the same Wi-Fi network

---

## Method 1: ADB (Recommended for developers)

### Step 1: Enable ADB on your TV

**Google TV (Chromecast with Google TV):**
1. Settings → System → About → Android TV OS Build → click 7 times
2. Settings → System → Developer Options → USB debugging → ON

**Fire TV:**
1. Settings → My Fire TV → About → Build → click 7 times
2. Settings → My Fire TV → Developer Options → ADB debugging → ON
3. Settings → My Fire TV → Developer Options → Apps from Unknown Sources → ON

### Step 2: Find your TV's IP address

**Google TV:** Settings → Network & Internet → your Wi-Fi → scroll down to see IP
**Fire TV:** Settings → My Fire TV → About → Network

### Step 3: Connect ADB

```bash
adb connect <TV-IP-ADDRESS>:5555
# Example: adb connect 192.168.1.42:5555
```

Confirm the connection prompt that appears on your TV.

### Step 4: Install

Debug builds are the easiest route — they are signed with the debug key:

```bash
# For Google TV:
adb install app/build/outputs/apk/googletv/debug/app-googletv-debug.apk

# For Fire TV:
adb install app/build/outputs/apk/firetv/debug/app-firetv-debug.apk
```

Release builds (`app-googletv-release-unsigned.apk`) install only if you sign
them. The build reads `KEYSTORE_PATH` (and the usual keystore/password
variables); without them Gradle emits an unsigned APK that `adb install` will
reject.

### Step 5: Launch

Find **opentvcast** in your app list and launch it.

---

## Method 2: Direct Sideload via USB (Fire TV Stick only)

Use the **Downloader** app (available in the Fire TV app store) to download the APK directly to your Fire TV from a URL.

1. Install "Downloader" from the Fire TV app store
2. Open Downloader and enter the APK download URL
3. Follow the prompts to install

---

## Method 3: Build from Source

```bash
git clone <this repository>
cd opentvcast

# Build both flavors
./gradlew assembleGoogletvRelease assembleFiretvRelease
```

APKs are in `app/build/outputs/apk/`.

This needs a JDK 17, Android SDK `platforms;android-35` + `build-tools;35.0.0`, and
for the native libraries NDK `28.2.13676358` + CMake `3.22.1`. See the README for the
full prerequisite table.

---

## After installation

1. Launch opentvcast — the home screen appears with one status card per protocol.
2. AirPlay is enabled by default. The DLNA card is a placeholder: the module is not
   implemented yet.
3. On your Mac, iPhone or iPad: click the AirPlay icon, then select your TV.

See [Troubleshooting](TROUBLESHOOTING.md) if the TV does not appear in the sender's list.
