---
name: add-station
description: Add a new radio station to the Radio app - record it in stations.json, add its logo, regenerate the station list, and reinstall the app. Use when the user asks to add a new zender/station.
---

# Add a new station

There is one app, **Radio** (`com.arjenvdw.radioapp`), at
`/Users/arjen/Projects/Personal/RadioApp`. Adding a station means: record it in
`stations.json`, add its logo drawable, regenerate `Stations.kt`, and rebuild
and install the app. No forking, no separate repo.

## 1. Gather the required info

Ask the user for whatever isn't already given:

- `key` — short lowercase slug, e.g. `radionl`. Used as the id in
  `stations.json` and for the logo drawable name (`ic_logo_<key>`). Must be
  unique and a valid resource-name suffix (lowercase letters, digits,
  underscores; no leading digit).
- `display_name` — human-readable station name shown in the app/notification.
- `stream_url` — the direct audio stream URL. If the user only gives a
  station's website or brand name, look for the actual stream URL (e.g. an
  icecast/streamtheworld URL) rather than guessing one.
- `media_id` — short id like `radionl_live`.
- `country` — ISO 3166-1 alpha-2 code, lowercase (e.g. `nl`, `be`, `de`). Drives
  the flag emoji shown next to the station in the app and Android Auto. Default
  to `nl` for Dutch stations.
- logo image — path to a logo image file. Ask for it if not provided; it
  becomes the artwork in the app, notification, and Android Auto.

Check `stations.json` first to make sure `key` and `media_id` aren't taken.

## 2. Record it in stations.json

Add an entry to the `stations` array in
`/Users/arjen/Projects/Personal/RadioApp/stations.json` mirroring existing
entries — these fields: `key`, `display_name`, `country`, `stream_url`,
`media_id`.

## 3. Add the logo and regenerate the station list

```bash
cd /Users/arjen/Projects/Personal/RadioApp
python3 scripts_set_logo.py <key> <logo_image_path>
python3 scripts_sync_stations.py
```

`scripts_sync_stations.py` rewrites `Stations.kt` from `stations.json` and
warns if any station is missing its `ic_logo_<key>.png`.

## 4. Build and install

```bash
cd /Users/arjen/Projects/Personal/RadioApp
ANDROID_HOME=/Users/arjen/Library/Android/sdk ./gradlew :app:installDebug -q
```

Confirm a device is connected first via
`/Users/arjen/Library/Android/sdk/platform-tools/adb devices -l` if unsure.

Report back the new station's key and confirm the Radio app reinstalled with
it in the list.
