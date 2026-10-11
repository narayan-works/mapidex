# Mapidex - live

Experimental / wiring copy of Mapidex for Playdate. This is where live data work happens
(phone companion feed, GPS, HTTP) so the shipped demo stays frozen.

- **Demo (frozen, do not edit):** `../mapidex` — "Mapidex - demo" v0.1.0, bundleID `works.narayan.mapidex.demo`
- **This app:** "Mapidex - live" v0.2.0, bundleID `com.narayan.mapidex.live` — installs side by side with the demo
- **Phone companion:** `../mapidex-companion` (Android) — serves `http://<phone-ip>:8080/feed.json` and `/health`

## Live feed (wired, v0.2.0+)
- Set the phone IP at the top of `Source/main.lua`: `PHONE_IP = "10.104.7.254"` (builds `LIVE_FEED_URL = http://<ip>:8080/feed.json`), rebuild.
  No-rebuild override: one-line URL in `Data/com.narayan.mapidex.live/live_url.txt`
  (Simulator: `~/Developer/PlaydateSDK/Disk/Data/com.narayan.mapidex.live/`).
- On every Start -> vibe -> loading, `FeedLive.start()` (Source/feed_live.lua) does `playdate.network.http` GET (6 s timeout);
  loading holds until it finishes. Fallbacks: `Data/.../live_feed.json` drop file, then baked `Source/feed.lua`.
- The phone's built route replaces only its own vibe (`route.vibe`); other vibes stay baked.
- Path footer (and loading screen) shows one line: `live from phone` / `live_feed.json (file)` / `baked: <error>` /
  `phone route is <vibe>; this is baked`.
- First fetch triggers the OS "allow network access" prompt (Sim + device) — accept it once.
- Playdate and phone must be on the same Wi-Fi (or phone hotspot); check from the Mac: `curl http://<ip>:8080/feed.json`.

## Build
```sh
~/Developer/PlaydateSDK/bin/pdc Source mapidex-live.pdx
open -a "$HOME/Developer/PlaydateSDK/bin/Playdate Simulator.app" mapidex-live.pdx
```
Sideload: zip `mapidex-live.pdx` and upload at https://play.date/account/sideload, or drop into `Games/` on the PLAYDATE data disk.

Cover / launch image / splash / load frames say **live** under the logo (demo says **demo**).
`HANDOFF.md` is inherited from the demo for history.

## Capture -> Neon snap (Sim only, local for now)
- Start the Neon monitor on the Mac: `/tmp/neon-web/start.sh` -> http://localhost:8787 (Neon Companion IP via `NEON_IP`, default 192.168.16.142).
- `GET|POST http://127.0.0.1:8787/snap` grabs the current Neon scene frame + gaze xy + worn and saves to
  `live/captures_local/snap-<ts>.png` (full frame, red gaze ring), `_1bit.png` (240x180 dithered thumb) and `.json`.
  Returns `{ok, path, gaze_x, gaze_y, worn, ts, when, thumb_w, thumb_h, bits}` (`?bits=0` drops the hex thumb).
- In the Sim, Path -> Capture (3-2-1) calls it (`Source/neon_snap.lua`); the 1-bit thumb is saved to
  `Data/com.narayan.mapidex.live/snaps/` and the new Captures entry ("Neon snap", gaze shown top-left in view).
  If the monitor is down the entry still saves as "just now (no snap)".
- Send to phone: every successful `/snap` also fire-and-forgets a multipart `POST http://<PHONE_IP>:8080/capture`
  (PHONE_IP read from `Source/main.lua`; override with env `PHONE_IP=` or `COMPANION_URL=` on start.sh).
  Fields: `image` (hi-res PNG, filled red gaze DOT burned in), `gaze_x`, `gaze_y`, `worn`, `ts`, `scene_w`, `scene_h`, `meta` (full snap json).
  Result lands in the local `.json` under `companion` and at `GET http://127.0.0.1:8787/companion`. Re-send newest: `curl -X POST localhost:8787/companion/resend`.
  Emulator test: `adb forward tcp:8080 tcp:8080` + `COMPANION_URL=http://127.0.0.1:8080/capture /tmp/neon-web/start.sh`.
