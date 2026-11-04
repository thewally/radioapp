---
name: install-radio-app
description: Build and install the single Radio app (all stations in one app) onto the connected Android phone. Use when the user asks to install, update, reinstall, or rebuild the radio app / their zenders on the phone.
---

# Install the Radio app on the phone

There is one app: **Radio** (`com.arjenvdw.radioapp`) at
`/Users/arjen/Projects/Personal/RadioApp`. It contains every station from
`stations.json` as a browsable list (this is the Android Auto–friendly design
that replaced the old one-app-per-station setup). Installing is a single build.

## Steps

1. Locate adb and confirm a device is connected:

   ```bash
   ADB=/Users/arjen/Library/Android/sdk/platform-tools/adb
   $ADB devices -l
   ```

   If no device shows up, stop and tell the user to connect/authorize their phone.

2. Make sure the station list in the app matches `stations.json` (in case it
   was hand-edited), then build and install:

   ```bash
   cd /Users/arjen/Projects/Personal/RadioApp
   python3 scripts_sync_stations.py
   ANDROID_HOME=/Users/arjen/Library/Android/sdk ./gradlew :app:installDebug -q
   ```

   `local.properties` (pointing at the SDK) must exist in the repo; it already
   does, but recreate it with `sdk.dir=/Users/arjen/Library/Android/sdk` if missing.

3. Report back that the Radio app was installed/updated and, if useful,
   confirm it launched (`$ADB shell monkey -p com.arjenvdw.radioapp -c
   android.intent.category.LAUNCHER 1`).
