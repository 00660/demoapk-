# LAN Remote Control for a Broken-Screen Android Phone

This project is a starter Android app that:

- runs a local HTTP server on the phone
- exposes a simple LAN web UI
- uses an `AccessibilityService` to perform taps, swipes, text input, and global actions
- starts a foreground service after boot

Important limits:

- A normal third-party app cannot replace `adb tcpip 5555` or permanently enable ADB over TCP by itself.
- A normal app also cannot silently enable its own accessibility service. You must enable it once through Settings or one-time ADB setup.

## One-time setup with USB ADB

```powershell
adb install app-debug.apk
adb shell am start -n com.codex.lanremote/.MainActivity
adb shell settings put secure enabled_accessibility_services com.codex.lanremote/com.codex.lanremote.accessibility.RemoteAccessibilityService
adb shell settings put secure accessibility_enabled 1
adb shell dumpsys deviceidle whitelist +com.codex.lanremote
```

Then open:

```text
http://PHONE_IP:8080/
```
