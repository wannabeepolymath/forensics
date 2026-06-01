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
- **Picker only shows JPEGs:** intentional for now (`launch(arrayOf("image/jpeg"))` in
  `MainActivity`); broaden the MIME filter to add formats later.
- **First emulator boot is slow** — give it a minute.

Fastest route from a clean machine: **Path A** (Android Studio is installed; it creates the
emulator and downloads the API 34 image for you).
