# Running the Forensics app

A metadata inspector + in-place editor for Android. This guide covers building and
running the debug app.

## Project facts
- Repo: `/Users/daksh/mySpace/code/forensics` (branch `main`)
- App module: `:app` · applicationId `com.forensics.app` · launcher `com.forensics.app/.MainActivity`
- Debug APK output: `app/build/outputs/apk/debug/app-debug.apk`
- Toolchain: AGP 8.13.2 / Gradle 9.5.1 / Kotlin 2.2.20 / Compose BOM 2024.09.03 · compileSdk 34 · minSdk 26

## Prerequisites (one-time)
All terminal Gradle commands need JDK 17. Set it once per terminal session:

```bash
cd /Users/daksh/mySpace/code/forensics
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
```

(Android Studio uses its own bundled JDK, so inside the IDE this isn't needed.)

`local.properties` already points at the SDK (`sdk.dir=/Users/daksh/Library/Android/sdk`);
it is gitignored — keep it.

You need a device to run on. Pick **one** of the three paths below.

---

## Path A — Android Studio + emulator (easiest, self-contained)
1. **Open the project:** Android Studio → *File ▸ Open* → select
   `/Users/daksh/mySpace/code/forensics`. Let Gradle sync.
2. **Create an emulator** (none exist yet): *Tools ▸ Device Manager* → *Add a virtual
   device* → e.g. **Pixel 7** → choose a system image **API 34 / Android 14** (download it
   if needed; API 34 is the installed platform) → *Finish*.
3. **Run:** pick the `app` configuration + your emulator in the toolbar → click ▶ **Run**
   (`Cmd+R`). The emulator boots and the app installs and launches automatically.

---

## Path B — A real Android phone over USB (most authentic test)
1. On the phone: *Settings ▸ About phone* → tap **Build number** 7× to unlock Developer
   options → *Developer options* → enable **USB debugging**.
2. Plug it into the Mac via USB; tap **Allow** on the debugging prompt.
3. Confirm it's seen:
   ```bash
   adb devices          # lists your device as "device" (not "unauthorized")
   ```
4. Build + install + launch:
   ```bash
   export JAVA_HOME=$(/usr/libexec/java_home -v 17)
   ./gradlew :app:installDebug
   adb shell am start -n com.forensics.app/.MainActivity
   ```

---

## Path C — Build the APK and install it manually
1. Build:
   ```bash
   export JAVA_HOME=$(/usr/libexec/java_home -v 17)
   ./gradlew :app:assembleDebug
   # -> app/build/outputs/apk/debug/app-debug.apk
   ```
2. Install onto any connected device/emulator:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   adb shell am start -n com.forensics.app/.MainActivity
   ```
   Or copy `app-debug.apk` to a phone and tap it (enable "install from unknown sources").

> A CLI-only emulator (without Android Studio) would need an AVD, but this SDK has no
> `cmdline-tools`/`sdkmanager` installed — use Android Studio's Device Manager (Path A)
> for the emulator. Paths B and C work purely from the terminal.

---

## Using the app / verifying the edit actually works
1. Tap **Open file**, pick a **JPEG that has EXIF** (a real camera/phone photo —
   screenshots often have none).
2. You'll see filesystem info, the **⚠ Edits modify your original file directly** banner,
   and the **Metadata / Hex / Strings** tabs.
3. On **Metadata**, find **Orientation** (or Make/Model/DateTime) → **Edit** → the dialog
   shows **"Will patch in place ✓"** → change the value → **Apply**.
4. You'll get **"Patched N bytes in place ✓"**, and the list re-reads from disk with the
   new value.
5. **Prove it's real** — the on-disk bytes change:
   ```bash
   adb shell 'md5sum /sdcard/Download/yourphoto.jpg'   # differs before vs after an edit
   ```
   (or `adb pull` the file and inspect with `exiftool`).

---

## Deploying a release build (signed APK → `apk-files/`)

This produces a **signed release APK** and parks a user-friendly copy in `apk-files/`.
`apk-files/`, `*.apk`, `*.jks`, `*.keystore` are gitignored — **build artifacts and the keystore
are never committed.**

> Adapted from the local `must_read.md` note (its `gps-simulator` / `android/...` paths are
> examples). This repo's app module is `:app` at the **repo root**, so the release APK lands at
> `app/build/outputs/apk/release/app-release.apk` (no `android/` prefix).

### One-time signing setup (required before the first release)
The debug builds above are auto-signed with a throwaway debug key. A release APK needs **your own
keystore**, set up once:

1. **Generate a release keystore** (keep it OUTSIDE the repo):
   ```bash
   keytool -genkeypair -v \
     -keystore ~/.forensics-release.jks \
     -alias forensics -keyalg RSA -keysize 2048 -validity 10000
   ```
   ⚠️ **Back this file up off-machine and remember the passwords.** Lose the keystore and you can
   never ship an update that overwrites an installed copy — Android requires the same signing key.

2. **Put the credentials in your *global* Gradle props** (`~/.gradle/gradle.properties`, never the
   repo — it's outside version control):
   ```properties
   FORENSICS_KEYSTORE=/Users/daksh/.forensics-release.jks
   FORENSICS_KEYSTORE_PASSWORD=********
   FORENSICS_KEY_ALIAS=forensics
   FORENSICS_KEY_PASSWORD=********
   ```

3. **Wire the signing config into `app/build.gradle.kts`** (one-time edit) — add a `release`
   signing config that reads those props, and point the `release` build type at it:
   ```kotlin
   android {
       signingConfigs {
           create("release") {
               val ksPath = project.findProperty("FORENSICS_KEYSTORE") as String?
               if (ksPath != null) {
                   storeFile = file(ksPath)
                   storePassword = project.findProperty("FORENSICS_KEYSTORE_PASSWORD") as String?
                   keyAlias = project.findProperty("FORENSICS_KEY_ALIAS") as String?
                   keyPassword = project.findProperty("FORENSICS_KEY_PASSWORD") as String?
               }
           }
       }
       buildTypes {
           release {
               signingConfig = signingConfigs.getByName("release")
               isMinifyEnabled = false   // no shrinker configured yet
           }
       }
   }
   ```
   (Until this is added, `assembleRelease` builds an **unsigned** APK that won't install.)

### Per-release ritual (~3 min each ship)
1. **Bump the version** in `app/build.gradle.kts` (`defaultConfig`):
   ```kotlin
   versionCode = 2          // currently 1 — must STRICTLY increase or Android rejects the update
   versionName = "0.2.0"    // currently "0.1.0" — must equal the git tag minus the leading "v"
   ```
2. **Build the signed release APK** (JDK 17, from the repo root):
   ```bash
   export JAVA_HOME=$(/usr/libexec/java_home -v 17)
   ./gradlew :app:assembleRelease
   # -> app/build/outputs/apk/release/app-release.apk
   ```
3. **Copy a user-friendly named build into `apk-files/`** (gitignored, so this never gets committed).
   This derives the version from `build.gradle.kts` and grabs whichever release APK was produced
   (`app-release.apk` when signed, `app-release-unsigned.apk` before the keystore is set up), so you
   never hand-edit the names:
   ```bash
   VERSION=$(sed -n 's/.*versionName *= *"\([^"]*\)".*/\1/p' app/build.gradle.kts)
   cp app/build/outputs/apk/release/app-release*.apk "apk-files/forensics-v${VERSION}.apk"
   ```
   (If both a signed and an unsigned APK are present from earlier builds, `rm` the stale one first,
   or name the exact file — `cp` can't copy two sources onto one destination.)
4. **Commit the version bump, tag, and push:**
   ```bash
   git commit -am "bump to 0.2.0"
   git tag v0.2.0
   git push && git push --tags
   ```

### Release gotchas
- **`versionCode` must strictly increase** every release, or a device refuses to install it over
  an existing copy.
- **Tag must equal `v` + `versionName`** (e.g. `versionName "0.2.0"` → tag `v0.2.0`). This keeps the
  convention an in-app update check would rely on (that updater isn't built yet — it's planned).
- **Never commit or lose the keystore** (`~/.forensics-release.jks`). Off-machine backup. Losing it
  means you can never update an installed build.
- Install/verify a release APK like any other:
  ```bash
  adb install -r app/build/outputs/apk/release/app-release.apk
  ```

---

## Stopping & cleanup

### Stop the running app
- **Android Studio:** click the red **Stop** ■ button in the toolbar (or *Run ▸ Stop 'app'*).
- **Terminal (device/emulator):**
  ```bash
  adb shell am force-stop com.forensics.app
  ```

### Uninstall the app
```bash
adb uninstall com.forensics.app
# or, via Gradle:
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :app:uninstallDebug
```

### Stop the emulator
- **Android Studio:** close the emulator window, or *Device Manager* → ▾ → **Stop**.
- **Terminal:** kill the running emulator instance:
  ```bash
  adb emu kill          # stops the currently running emulator
  adb devices           # confirm it's gone
  ```

### Stop a build / the Gradle daemon
- A running build in the terminal: press **Ctrl+C**.
- Shut down the background Gradle daemon (frees memory):
  ```bash
  export JAVA_HOME=$(/usr/libexec/java_home -v 17)
  ./gradlew --stop
  ```

### Disconnect a USB device
Just unplug it. To fully reset the adb connection: `adb kill-server`.

---

## Troubleshooting
- **`adb devices` empty / "unauthorized":** replug USB, accept the on-phone prompt; try
  `adb kill-server && adb start-server`.
- **Gradle can't find a JDK:** you forgot `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`
  in that terminal.
- **"SDK location not found":** ensure `local.properties` has
  `sdk.dir=/Users/daksh/Library/Android/sdk`.
- **Picked image shows no EXIF fields:** that file has no EXIF (still shows filesystem
  info + Hex + Strings). Try a real camera photo.
- **Editing only works on JPEGs with EXIF:** the picker now opens any file (`*/*`) so you can
  inspect filesystem metadata + hex + strings on anything, but in-place metadata *edits* currently
  need the JPEG/EXIF handler. Other formats are read-only until more handlers land.
- **First emulator boot is slow** — give it a minute.

Fastest route from a clean machine: **Path A** (Android Studio is installed; it creates the
emulator and downloads the API 34 image for you).
