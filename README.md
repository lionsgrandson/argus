# ARGUS Android — Secure Camera + Audio

This repository contains the ARGUS Android app and encrypted internet relay.

## What it does

- One APK with **Child phone** and **Parent phone** modes.
- Live Child-phone **camera + audio** over the internet.
- Child phone keeps the camera and microphone foreground service running while its display is locked/off.
- Parent phone shows the live camera feed and plays the live audio.
- End-to-end AES-256-GCM encryption for audio, camera and status packets.
- TLS-validated `wss://` transport.
- Separate random relay authentication token.
- Pairing secret stored with Android Keystore.
- No recordings or media database; live frames are kept in memory only.
- Automatic reconnect.
- Parent connection-loss alarm after previously-live audio disappears for about 8 seconds.
- Remote Child-phone battery/charging status and low-battery warning.
- Sticky foreground services, wake/Wi-Fi locks, and `stopWithTask=false` keep ARGUS running when the app UI is closed or the screen is locked.
- Optional root-assisted reboot recovery, while retaining the normal non-rooted behavior on ordinary phones.

The currently deployed relay is baked into the app.

## First use

### Child phone

1. Install the APK and open **ARGUS**.
2. Select **THIS IS THE CHILD PHONE**.
3. Grant camera and microphone permission when Android asks.
4. ARGUS creates the secure pairing QR automatically and starts transmitting.
5. Plug the phone into power for long sessions and lock the screen if desired.

### Parent phone

1. Install the same APK.
2. Select **THIS IS THE PARENT PHONE**.
3. Tap **SCAN CHILD QR** and scan the QR shown on the Child phone.
4. ARGUS stores the pairing securely and starts watching automatically.

Treat the pairing QR/link like a password. If it leaks, create a new pairing QR on the Child phone.

## Background and reboot behavior

While Child transmission is active, ARGUS runs as a sticky foreground camera/microphone service with wake and high-performance Wi-Fi locks. Closing the app UI or locking the screen does not intentionally stop transmission.

ARGUS remembers the active role across reboots and listens for boot, user-unlock, and app-update events.

### Rooted phone

If a working `su` implementation is installed and ARGUS has persistent root permission, ARGUS uses root after the first unlock to launch its normal resume action automatically. The app then starts the ordinary camera/microphone foreground service from a foreground state and continues transmitting when the screen is locked or the user leaves the app.

When that root-assisted restart succeeds, ARGUS does **not** show the separate **Resume ARGUS** reboot notification. The normal ongoing ARGUS camera/microphone indicator remains visible while transmission is active.

Root permission should be granted permanently in the phone's root manager. If root is missing, denied, or unavailable, ARGUS automatically falls back to the non-root behavior below.

### Non-rooted phone

On Android versions that permit direct boot restart, ARGUS resumes automatically. Current Android privacy rules do not allow apps targeting modern SDKs to silently create a camera/microphone foreground service from the background after reboot. On those versions, ARGUS shows **Resume ARGUS**; tapping it immediately resumes the previously active mode without requiring pairing or setup again.

For maximum reliability, open **Advanced settings → Background battery settings** and set ARGUS to the least restrictive battery mode offered by the phone manufacturer.

## Simplified interface

The main screen only shows the controls needed for the selected role. Relay configuration, battery/background settings, and Android app settings are under **Advanced settings**.

## Build

The project uses:

- Android SDK 35.
- minSdk 26, target/compileSdk 35.
- Java 11 source compatibility.
- Local Windows build helper: `buildapp.cmd`.

Run `buildapp.cmd` from the repository root to build locally without consuming GitHub Actions minutes.

## Relay

`relay-cloudflare/` contains the Cloudflare Worker/Durable Object relay source. The relay never receives the AES encryption key; it forwards ciphertext and can observe ordinary network metadata such as connection times and traffic volume.

## Reliability

For the Child phone:

- Keep it plugged in for long sessions.
- Set the app battery mode to Unrestricted/Not optimized if the manufacturer provides that option.
- Test camera + audio with the display locked on the exact phone model.
- Reboot the phone once during testing and verify the rooted or non-rooted resume path you intend to use.
- Test a disconnect so you know the Parent alarm behaves as expected.

## Important files

- `app/src/main/java/com/example/babymonitor/MainActivity.java` — simplified Parent/Child UI.
- `app/src/main/java/com/example/babymonitor/SenderService.java` — Child foreground sender.
- `app/src/main/java/com/example/babymonitor/CameraStreamer.java` — screen-off Camera2 capture.
- `app/src/main/java/com/example/babymonitor/ReceiverService.java` — Parent audio/video receiver.
- `app/src/main/java/com/example/babymonitor/BootReceiver.java` — reboot/update resume handling.
- `app/src/main/java/com/example/babymonitor/RootSupport.java` — optional root-assisted restart launcher.
- `app/src/main/java/com/example/babymonitor/PacketCodec.java` — encrypted packet protocol.
- `relay-cloudflare/` — Cloudflare relay source.
- `SECURITY.md` — security model.
- `TESTING.md` — acceptance tests.
