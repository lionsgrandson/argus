# ARGUS FCM remote wake setup

ARGUS can use Firebase Cloud Messaging (FCM) as a best-effort remote wake path after the child phone reboots or the process is no longer connected.

Important Android limitation: on Android 14+ a background FCM message does **not** grant camera/microphone while-in-use access. ARGUS therefore uses FCM to wake/reconnect only and does not force an Activity, use a root bypass, or show a fallback recovery notification. On older Android versions, where the OS permits it, the child foreground service can resume automatically.

## 1. Create the Firebase Android app

Create an Android app in Firebase with package name:

`com.example.babymonitor`

Enable Firebase Cloud Messaging / the FCM HTTP v1 API.

Record these values from the Firebase Android app configuration:

- API key
- Mobile SDK App ID
- Project ID
- Project number / sender ID

ARGUS initializes Firebase directly, so `google-services.json` is not required in the repository.

## 2. Build the APK with the Firebase client values

Before running `buildapp.cmd`, set these environment variables in the same CMD window:

```cmd
set ARGUS_FIREBASE_API_KEY=YOUR_API_KEY
set ARGUS_FIREBASE_APP_ID=YOUR_MOBILE_SDK_APP_ID
set ARGUS_FIREBASE_PROJECT_ID=YOUR_PROJECT_ID
set ARGUS_FIREBASE_SENDER_ID=YOUR_PROJECT_NUMBER
buildapp.cmd
```

These can alternatively be supplied as Gradle properties with the same names.

If these values are missing, ARGUS still builds and works normally, but FCM remote wake is disabled.

## 3. Add the Firebase service account to Cloudflare

Create/download a Firebase/Google service account JSON that is allowed to send FCM HTTP v1 messages.

Do **not** commit that JSON or its private key to GitHub.

From `relay-cloudflare`, save the JSON as a persistent Wrangler secret:

```cmd
npx wrangler secret put FCM_SERVICE_ACCOUNT_JSON
```

Paste the complete JSON when Wrangler asks for the secret value. The Worker reads the Firebase project ID from this JSON automatically. `FCM_PROJECT_ID` is only needed as an optional override.

Then deploy using the existing Cloudflare deploy command.

## 4. How recovery works

1. The child app obtains an FCM registration token and registers it with the paired Cloudflare Durable Object using the existing pairing authorization.
2. After a reboot, `BootReceiver` attempts the Android-version-appropriate recovery path and refreshes the FCM registration.
3. When the parent reconnects and the child WebSocket is absent, Cloudflare sends a data-only `ARGUS_RECONNECT` FCM message to the registered child phone.
4. Android 13 and older can attempt to resume the child foreground service automatically.
5. Android 14+ keeps this path silent and compliant. If Android does not allow camera/microphone access from the background, ARGUS does not force a popup and does not create a fallback notification.

## 5. Verification

The Worker `/health` response contains `fcmWake: true` only when `FCM_SERVICE_ACCOUNT_JSON` is configured.

For an end-to-end test:

1. Install an APK built with the four Firebase client values.
2. Pair parent and child normally and confirm a live connection.
3. Reboot the child phone.
4. Unlock it once if the device requires unlock before app storage is available.
5. Open/reconnect the parent using the saved pairing code.
6. Check Cloudflare logs for `fcm_registered` followed by `fcm_wake_sent`.

Because data-only high-priority FCM messages do not display a notification, Google may eventually deprioritize them on some devices. Treat this path as best effort rather than a guaranteed background camera/microphone restart on modern Android.
