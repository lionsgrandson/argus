# Baby Monitor Android — Secure Camera + Audio v2.6

This repository contains the Android app and encrypted internet relay for the Baby Monitor project.

## What it does

- One APK with **Baby phone** and **Parent phone** modes.
- Live baby-room **camera + audio** over the internet.
- Baby phone keeps the camera and microphone foreground service running while its display is locked/off.
- Parent phone shows the live camera feed and plays the live audio.
- End-to-end AES-256-GCM encryption for audio, camera and status packets.
- TLS-validated `wss://` transport.
- Separate random relay authentication token.
- Pairing secret stored with Android Keystore.
- No recordings or media database; live frames are kept in memory only.
- Automatic reconnect.
- Parent connection-loss alarm after previously-live audio disappears for about 8 seconds.
- Remote baby-phone battery/charging status and low-battery warning.
- Conservative camera mode is used initially to reduce battery/data use and remain compatible with the older deployed relay bandwidth limits.

The configured relay is baked into the app:

`wss://baby-monitor-secure-relay.mosheschwartzberg.workers.dev/ws`

## First use

### Baby phone

1. Install the APK and open **Baby Monitor**.
2. Select **Baby phone**.
3. Tap **New code** if there is no pairing code yet, then **Copy code**.
4. Tap **Start baby monitor**.
5. Grant **camera** and **microphone** permission when Android asks.
6. Plug the phone into power and lock the screen.

Android will keep a foreground-service notification visible while the camera/microphone service is active. After a full phone reboot, Android requires a user-visible resume flow before camera/microphone monitoring can restart.

### Parent phone

1. Install the same APK.
2. Select **Parent phone**.
3. Paste the Baby phone pairing code.
4. Tap **Start watching**.
5. The live camera appears at the top while the encrypted audio plays.

Treat the pairing code like a password. If it leaks, create a new code on the Baby phone.

## Simplified interface

The main screen now only shows the controls needed for the selected role. Relay configuration and Android app settings are hidden under **Advanced settings**.

## Build

GitHub Actions builds the debug APK on pushes to `main` and on pull requests. The project uses:

- JDK 17 for the build runner.
- Android SDK 35 / Build Tools 35.0.0.
- Gradle 8.11.1.
- Android Gradle Plugin 8.9.2.
- minSdk 26, target/compileSdk 35.
- Java 11 source compatibility.

The existing Windows build helpers can also be used when present in a local copy of the project.

## Relay

`relay-cloudflare/` contains the Cloudflare Worker/Durable Object relay source. The relay never receives the AES encryption key; it forwards ciphertext and can observe ordinary network metadata such as connection times and traffic volume.

The camera build remains deliberately low-bandwidth enough to work with the earlier deployed relay limits. The updated Worker source also includes larger guarded limits for future higher-quality camera modes.

## Reliability

For the Baby phone:

- Keep it plugged in for long monitoring sessions.
- Set the app battery mode to Unrestricted/Not optimized if the manufacturer provides that option.
- Test camera + audio with the display locked on the exact phone model before relying on it overnight.
- Test a disconnect so you know the Parent alarm behaves as expected.

This is a personal monitoring project, not a certified medical/safety device and not a replacement for normal child-safety precautions.

## Important files

- `app/src/main/java/com/example/babymonitor/MainActivity.java` — simplified UI.
- `app/src/main/java/com/example/babymonitor/SenderService.java` — Baby foreground sender.
- `app/src/main/java/com/example/babymonitor/CameraStreamer.java` — screen-off Camera2 capture.
- `app/src/main/java/com/example/babymonitor/ReceiverService.java` — Parent audio/video receiver.
- `app/src/main/java/com/example/babymonitor/PacketCodec.java` — encrypted packet protocol.
- `relay-cloudflare/` — Cloudflare relay source.
- `SECURITY.md` — security model.
- `TESTING.md` — acceptance tests.
