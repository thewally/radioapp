# Radio

A single Android app that plays a curated list of Dutch radio stations, built
on Media3/ExoPlayer with Android Auto support. One app, one media session, a
browsable list of stations — tap (or say) a station and it plays.

This replaced an earlier design of one app per station: multiple media apps
fight Android Auto's single-media-app model, causing stations to start/stop
each other. A single browsable app is the idiomatic Android Auto design.

## Station list

`stations.json` is the single source of truth (fields: `key`, `display_name`,
`country`, `stream_url`, `media_id`). The order there is the order shown in the
app. `app/src/main/java/com/arjenvdw/radioapp/Stations.kt` is generated from it.

`country` is an ISO 3166-1 alpha-2 code (e.g. `nl`, `be`). It drives the flag
emoji shown next to each station and the country filter row above the list
(`Countries.kt` defines which countries appear and in what order). The
Netherlands (`nl`) shows all its stations; other countries show their top 5
(the first five in `stations.json`). Streams may be plain HTTP(S) or HLS
(`.m3u8`) — the HLS ExoPlayer module is included.

Each station has a logo at `app/src/main/res/drawable-nodpi/ic_logo_<key>.png`,
shown in the now-playing header, the list, the media notification, and Auto.

### Managing stations

Use the skills (`add-station`, `edit-station`, `remove-station`,
`install-radio-app`) or the helper scripts directly:

```bash
# set/replace a station's logo
python3 scripts_set_logo.py <key> <image_path>
# regenerate Stations.kt from stations.json (prunes orphan logos)
python3 scripts_sync_stations.py
```

## Build & install

```bash
ANDROID_HOME=/Users/arjen/Library/Android/sdk ./gradlew :app:installDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
