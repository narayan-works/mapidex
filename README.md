# Mapidex

A Pokédex-style walking companion on Playdate, with an Android phone as a quiet GPS + OpenStreetMap engine in your pocket.

## Layout

| Folder | What it is |
|---|---|
| `demo/` | **Mapidex - demo** (Playdate). Uses a baked-in walk, no phone needed. This is the reliable stage demo. Also has `pipe/` (Python OSM detour script), `web-shell/` and `assets/`. |
| `live/` | **Mapidex - live** (Playdate). Same app, but it pulls the route/places feed from the phone companion over Wi-Fi. |
| `companion/` | **Mapidex Companion** (Android, Kotlin). Runs in the background: tracks GPS, builds routes from OSM and serves the feed on your LAN for `live/`. |

## Build

**Playdate (demo or live)**, with the Playdate SDK installed:

```sh
cd demo   # or: cd live
pdc Source mapidex.pdx        # live: pdc Source mapidex-live.pdx
```

Open the `.pdx` in the Simulator, or sideload the zipped `.pdx` to the device.

**Android companion:**

```sh
cd companion
./gradlew assembleDebug
# APK: companion/app/build/outputs/apk/debug/app-debug.apk
```

Or open `companion/` in Android Studio.

**Detour pipeline (optional):**

```sh
python3 demo/pipe/commute_detour.py --help
```

Build outputs (`*.pdx`, `*.pdx.zip`, `*.apk`, `build/`) are git-ignored. Attach them to a GitHub Release instead.
