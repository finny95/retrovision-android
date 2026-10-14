# RetroVision for Android

The Android app for **RetroVision / Cathode Tube**, the CRT media player built in Base44
(app `6ac7728a9741de53fe460747`, live at https://gorgeous-retro.base44.app).

**Download:** [latest APK](https://github.com/finny95/retrovision-android/releases/latest/download/RetroVision.apk)

## How updates reach the app

```
Base44: edit + Publish
   └─ build writes /ota/manifest.json + bundle-<version>.zip   (vite-ota-plugin.js in the Base44 app)
        └─ installed app checks the manifest on launch and when reopened
             └─ downloads the new bundle in the background (sha256 checked)
                  └─ switches to it the next time the app is opened
```

- No reinstall for normal changes. Publish in Base44 and phones update themselves.
- If a new version fails to start within 15 seconds, the app rolls back to the previous one
  and won't retry that version.
- If an update needs a newer APK (`minNativeBuild` in the Base44 app's `native.config.json`
  is higher than the installed build number), the app shows a "new app version" banner
  linking to the latest APK instead of installing the update.

## When you need a new APK

Only when the native shell changes (this repo). Push to `main` or run
**Actions > Build APK > Run workflow**. Each run:

1. downloads the currently published Base44 bundle and puts it inside the APK,
2. builds launcher icons, the Android TV banner and splash screens from the app's icon,
3. builds and signs the APK (build number = run number),
4. publishes it as a GitHub release,
5. boots it in an emulator and saves screenshots and logs (artifact `smoke-test`).

**Actions > Smoke test latest APK** repeats step 5 on the current release, handy after a
Base44 publish to watch an over-the-air update land (look for `[ota]` lines in `logcat-app.txt`).

If you add a native feature the web app relies on, bump `minNativeBuild` in the Base44 app's
`native.config.json` to this repo's new build number.

## Signing

`android/keystore/release.p12` is AES-256 encrypted. Its password is only in the
`ANDROID_KEYSTORE_PASSWORD` Actions secret (and your password manager). Every APK must be
signed with this key, or Android won't install it over the existing app.

## What's native

| File | Why |
| --- | --- |
| `MainActivity.java` | Full-screen (system bars hidden), registers everything below |
| `MediaFolderPlugin.java` | "Link" a folder via Android's folder picker: files stay in place, read access is kept |
| `MediaWebViewClient.java` | Streams linked files to the player with proper seeking and support for files over 2 GB |
| `FullscreenChromeClient.java` | Makes the player's fullscreen button work inside the app |
| `AndroidManifest.xml` | Phone, tablet, handheld and Android TV launcher support |

Web-side counterparts live in the Base44 app under `src/native/`.

## Local build

```
npm ci
npm run prepare-android        # fetch bundle, icons, cap sync
cd android && ./gradlew assembleDebug
```
