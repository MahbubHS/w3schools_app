# W3Schools Personal App

A personal, non-commercial Android app that wraps [w3schools.com](https://www.w3schools.com)
as a full-screen, permanently-logged-in native shell. Built for one person's
own device — not published, not monetized, not distributed.

✦ Made for Saad ✦

## What it does

- Full-screen, no browser chrome, **no loading bar** during navigation (by design — see below)
- Login persists indefinitely — the app never clears cookies, cache, or history
- Splash screen with the W3Schools mark, on a background that follows system light/dark
- The website content itself re-themes with system dark mode (not just native UI)
- File uploads, `target="_blank"` links, and authenticated downloads all work
- Offline detection with a retry screen
- Survives rotation, backgrounding, and process death without losing your place

## The two rules this app never breaks

1. **No cookie/cache/history clearing, anywhere.** Search the codebase — there
   is no call to `CookieManager.removeAllCookies`, `WebStorage.deleteAllData`,
   `WebView.clearCache/clearHistory/clearFormData`, or any deletion of the
   WebView data directory. That *omission* is the entire mechanism behind a
   "permanent" login. `CookieManager.flush()` is called in `onPause()` — that
   only forces cookies already accepted to be written to disk, it does not
   delete anything.
2. **`onProgressChanged()` is an empty override.** That's what keeps a
   loading bar from ever appearing.

## Honest limits (things this app can't control)

- **Cookie lifetime is still W3Schools' call.** This app never *shortens* a
  session, but the server still decides how long a cookie is valid for. If
  W3Schools' login page has a "remember me" option, enable it — that's what
  actually extends the *server-side* session length.
- **Service workers are a website technology.** WebView already runs a site's
  own service worker automatically if the site registers one. An app cannot
  inject a service worker into someone else's site from the outside — there's
  nothing to add here beyond `cacheMode = LOAD_DEFAULT`, which lets whatever
  caching W3Schools already does behave normally.

## Project layout

```
W3SchoolsApp/
├── app/
│   ├── src/main/java/com/saad/w3schoolsapp/
│   │   ├── MainActivity.kt          # everything lives here — single Activity, single WebView
│   │   └── W3SchoolsApplication.kt
│   ├── src/main/res/                # layouts, day/night colors & themes, icons, splash art
│   └── build.gradle.kts
├── .github/workflows/
│   ├── android-ci.yml               # builds a debug APK on every push/PR (sanity check)
│   └── release.yml                  # builds + publishes a tagged release APK
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

**Tech:** Kotlin, traditional Views + ViewBinding (no Compose), single
Activity, `minSdk 26` / `targetSdk 34`, AGP 8.3.2, Kotlin 1.9.24, Gradle 8.6,
Java 17.

## Building it yourself

### Android Studio (easiest)

1. Unzip the project.
2. **File → Open…** and select the `W3SchoolsApp` folder.
3. Let Gradle sync. If Android Studio prompts about the Gradle wrapper (see
   note below), accept its offer to fix it.
4. **Run ▶** on a device/emulator, or **Build → Build Bundle(s)/APK(s) → Build APK(s)**.

### Command line

```bash
cd W3SchoolsApp
./gradlew assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

> **Gradle wrapper jar:** `gradle/wrapper/gradle-wrapper.jar` (a small
> compiled bootstrap `.jar`) is **not committed** to this repo — it's a
> binary file this environment couldn't produce without network access.
> Android Studio regenerates it automatically on first sync. From the
> command line, run `gradle wrapper --gradle-version 8.6` once (with any
> local Gradle install) to create it, after which `./gradlew` works normally.
> CI doesn't need it at all — see below.

## CI/CD (GitHub Actions)

Two workflows, both using `gradle/actions/setup-gradle` +
`android-actions/setup-android` to get a working Android SDK and Gradle 8.6
on GitHub's runner directly (sidestepping the missing wrapper jar entirely —
CI runs `gradle ...`, not `./gradlew ...`):

- **`android-ci.yml`** — runs on every push/PR to `main`. Builds a debug APK
  and uploads it as a workflow artifact, as a build-health check. No release
  is created.
- **`release.yml`** — runs when you push a version tag. Builds a release APK
  stamped with that version, and publishes a GitHub Release with the APK
  attached.

### Cutting a release

```bash
git tag v1.0.0
git push origin v1.0.0
```

That's it — the workflow reads `1.0.0` out of the tag, builds
`app-release.apk` with `versionName = "1.0.0"` and an auto-incrementing
`versionCode`, and attaches `W3SchoolsApp-1.0.0.apk` to a new Release at
`https://github.com/<you>/<repo>/releases`. You can also trigger it manually
from the **Actions** tab (`workflow_dispatch`) with a version typed in,
without needing a tag.

### On signing

The release build uses the standard auto-generated **debug keystore**
(`signingConfig = signingConfigs.getByName("debug")` in
`app/build.gradle.kts`), not a dedicated production key. That's a deliberate
choice for a personal app that's never distributed through the Play Store —
it keeps the workflow self-contained with zero signing secrets to manage. If
you ever do want a real key, generate a keystore, add it (base64-encoded) plus
its passwords as repository secrets, and point `signingConfig` at it instead.

## Icon & splash art

The launcher icon and splash logo are cropped and resized from the logo image
you provided — not redrawn or generated — the same principle a browser's "Add
to Home Screen" feature relies on. If you want a sharper source at very large
sizes later, swap it via Android Studio's **Image Asset** tool.

## Scope

Personal, non-commercial, single-device use only. No ads, no analytics or
tracking SDKs, no Play Store listing metadata. The W3Schools name and logo
belong to W3Schools/Refsnes Data — this project just displays their own site
inside a plain wrapper on one person's phone; it isn't a redistribution of
their brand.
