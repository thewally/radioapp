---
name: edit-station
description: Update an existing station's stream URL, display name, or logo in the Radio app (e.g. when a broadcaster changes their stream URL), then reinstall the app. Use when the user says a station's stream is broken/changed or wants to rename/re-icon an existing station.
---

# Edit an existing station

There is one app, **Radio** (`com.arjenvdw.radioapp`), at
`/Users/arjen/Projects/Personal/RadioApp`. `stations.json` is the single source
of truth; `Stations.kt` is generated from it. Editing means: change
`stations.json` (and/or the logo), regenerate, rebuild, reinstall.

## 1. Identify the station and the change

Find the station's entry in
`/Users/arjen/Projects/Personal/RadioApp/stations.json` by name or key. Ask
the user which field changed if unclear: `stream_url`, `display_name`,
`country` (ISO alpha-2 code driving the flag emoji), or logo. If they only give
a station name/brand for a new stream, look up the actual current stream URL
rather than guessing.

Note: changing `key` also renames the logo drawable (`ic_logo_<key>`) and is
rarely needed — prefer changing only `display_name`/`stream_url`/logo. If the
`key` really must change, also rename/recreate the logo for the new key.

## 2. Update stations.json

Edit the matching entry's `display_name` and/or `stream_url` (and `media_id`
if it truly changed) in `stations.json`.

## 3. Update the logo (only if it changed) and regenerate

```bash
cd /Users/arjen/Projects/Personal/RadioApp
# only if the logo changed:
python3 scripts_set_logo.py <key> <new_logo_image_path>
python3 scripts_sync_stations.py
```

## 4. Rebuild and reinstall

```bash
cd /Users/arjen/Projects/Personal/RadioApp
ANDROID_HOME=/Users/arjen/Library/Android/sdk ./gradlew :app:installDebug -q
```

Confirm a device is connected first via
`/Users/arjen/Library/Android/sdk/platform-tools/adb devices -l` if unsure.

## 5. Report back

Confirm what changed (old value -> new value) and that the Radio app was
reinstalled.
