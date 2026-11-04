---
name: remove-station
description: Remove a station from the Radio app - drop it from stations.json, delete its logo, regenerate the station list, and reinstall the app. Use when the user asks to remove/delete a zender/station they no longer want.
---

# Remove a station

There is one app, **Radio** (`com.arjenvdw.radioapp`), at
`/Users/arjen/Projects/Personal/RadioApp`. Removing a station just drops it
from `stations.json`, deletes its logo, regenerates `Stations.kt`, and
reinstalls the app. Nothing is uninstalled from the phone (there is only the
one app), so this is low-risk — but confirm which station if there's any
ambiguity.

## 1. Identify the station

Find the entry in `/Users/arjen/Projects/Personal/RadioApp/stations.json` by
name or key.

## 2. Remove it from stations.json and regenerate

Delete the matching object from the `stations` array in `stations.json`, then:

```bash
cd /Users/arjen/Projects/Personal/RadioApp
python3 scripts_sync_stations.py
```

`scripts_sync_stations.py` regenerates `Stations.kt` and automatically deletes
the now-orphaned `ic_logo_<key>.png`.

## 3. Rebuild and reinstall

```bash
cd /Users/arjen/Projects/Personal/RadioApp
ANDROID_HOME=/Users/arjen/Library/Android/sdk ./gradlew :app:installDebug -q
```

Confirm a device is connected first via
`/Users/arjen/Library/Android/sdk/platform-tools/adb devices -l` if unsure.

## 4. Report back

Confirm the station was removed from the list and the Radio app was reinstalled.
