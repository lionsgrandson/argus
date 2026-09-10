# Remote app updates

ARGUS now supports user-confirmed APK updates delivered through the existing Cloudflare Worker and the `moshebackup` R2 bucket.

## One-time bootstrap

The first APK containing the updater must still be installed manually on each existing phone. This is currently versionCode 21 (`3.5.0-cloud-updater`). Android cannot add an updater to an APK that is already installed without first installing new app code.

Run:

```cmd
git pull
buildapp.cmd
```

Install `OUTPUT\ARGUS-debug.apk` over the existing app. Keep the same package ID and signing key so Android treats it as an update and keeps app data.

Also deploy the new Worker once:

```cmd
deploy-cloudflare.cmd
```

## Every update after that

1. Make the code changes.
2. Increase `versionCode` and `versionName` in `app/build.gradle`.
3. Commit/push the source as normal.
4. Run:

```cmd
publish-update.cmd "Short release notes"
```

For a required update:

```cmd
publish-update.cmd "Critical compatibility update" -Required
```

The publisher builds the signed APK locally, verifies the preserved signing key, deploys the Cloudflare Worker, uploads the APK to the private R2 bucket, publishes `argus-updates/latest.json`, and verifies the public update manifest.

Installed apps check for updates when opened and also periodically in the background. When a newer version is available, Android shows an update notification/prompt. The app downloads the APK from Cloudflare, checks its SHA-256 hash, and submits it to Android's `PackageInstaller`.

On Android 8 and newer, the user may need to enable **Allow from this source** for this app once. Android may still show its own final install/update confirmation. The updater does not bypass Android's installation security.

## Signing key

The installed APK and every future update APK must be signed by the same key. `publish-update.ps1` preserves the current local Android debug signing key under `.signing\argus-debug.keystore` and refuses to publish if the active key later differs. `.signing/` is ignored by Git and must never be committed.

Back up that local signing key securely. Losing it means existing installations cannot be updated with newly signed APKs.

## Cloudflare endpoints

- `/client-config` supplies relay and update-manifest configuration.
- `/app-update` supplies the currently published version, release notes, APK hash, size, and download URL.
- `/app-update/apk` streams the current signed APK from private R2 storage.
- APKs and `latest.json` live under the `argus-updates/` prefix in the `moshebackup` bucket.
