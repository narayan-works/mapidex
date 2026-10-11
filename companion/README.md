# Mapidex companion v0.5

## Ask (v0.5) — Capture → Nano → web → Gemini → /ask.json

**How Capture arrives.** Playdate Capture → Mac Neon bridge (`/tmp/neon-web/server.py`, `/snap`) → `POST http://<phone>:8080/capture`.
- multipart: `image` (PNG/JPEG hi-res scene frame), `gaze_x`, `gaze_y` (scene px, origin top-left), optional `scene_w`, `scene_h`, `worn`, `ts`, `lat`, `lng`, `question`, `meta` (json), `ask=0` to store only.
- or JSON: `{"image_b64": "...", "gaze_x": 623.7, "gaze_y": 455.6, ...}`
- Stored in `<app files>/captures/`: `last.png` (unmarked original), `last_marked.png` (black dot + white rim at gaze — what models see), `last_gaze.json`, plus archive `cap-<ms>.png/.json`.
- Returns at once (`{"ok":true,"id":…,"ask":{status:"thinking"}}`); add `?wait=1` to block ≤40 s for the answer.

**Cascade** (`AskEngine.kt`):
1. **Gemini Nano on-device** — ML Kit GenAI Prompt API (`com.google.mlkit:genai-prompt`) → AICore, image + text. Prompt pins the dot pixel/percent + GPS lat/lng: name only what is under the dot, reply `TITLE / BLURB / CONFIDENCE`.
2. **Web enrich** (no key, runs in parallel): OSM Overpass named places ≤120 m, Nominatim street, Wikipedia geosearch + summary. A title that matches a nearby name = confirmed (`source: nano+web`, adds `place` / `wiki` layers).
3. **Gemini API cloud** (`GeminiCloud.kt`, REST `generateContent`, image + text) when Nano failed, is low-confidence/generic, or unconfirmed and not high. Gets image + dot + GPS + Nano's guess + web hints. No key → skipped, `needs_gemini_key: true`.

Key: add `GEMINI_API_KEY=...` (optional `GEMINI_MODEL=gemini-3.8-flash`, default `gemini-flash-latest`) to `companion/local.properties` (git-ignored) → BuildConfig at build time. Never commit it.

**Endpoints**: `POST /capture` · `GET /ask.json` · `POST /ask` (re-run last capture, `question=`) · `POST /verify` (`task=` → pass/fail) · `GET /verify.json` · `GET /capture/last.png|last_marked.png|last_gaze.json` · `GET /captures` · `GET /capture/latest.json|png` · `GET /health` (now includes `nano_status`, `gemini_key`).

**Example `/ask.json`**
```json
{
  "kind": "ask", "status": "ok",
  "title": "Ferry Building clock tower",
  "blurb": "1898 landmark; its clock tower was modeled on Seville's Giralda.",
  "confidence": "high", "source": "nano+web",
  "gaze": {"x": 623.8, "y": 455.7, "w": 1600, "h": 1200},
  "layers": [{"name":"what","text":"Ferry Building clock tower"},{"name":"about","text":"…"},{"name":"place","text":"Ferry Building"},{"name":"wiki","text":"…"}],
  "location": {"lat": 37.7955, "lng": -122.3937, "has_fix": true},
  "question": null, "capture_id": "cap-1791747816476",
  "nano_status": "available", "needs_gemini_key": false,
  "cascade": [{"step":"nano","ok":true,"note":"high · …"},{"step":"web","ok":true,"note":"6 places · 3 wiki"},{"step":"gemini","ok":true,"note":"not needed"}],
  "error": null, "updated_at": "2026-10-11T20:00:29Z"
}
```
`status`: idle | thinking (previous answer stays visible) | ok | error (title "Can't tell yet", `error` says why). Verify adds `verdict` (PASS/FAIL/UNSURE) + `pass` bool and a first `verdict` layer.

**Phone setup (Pixel 9 Pro XL)**: Nano via AICore needs a locked bootloader, AICore + Play system updates current, Wi‑Fi so AICore can download Nano (first run: `nano_status` downloading → available). AICore blocks background inference, so keep the companion **open on screen** for Nano; otherwise Ask falls through to Gemini cloud.


## Background serving (v0.4)

**Start server** launches a foreground service (notification: "Mapidex serving on :8080") that owns the HTTP server + GPS, so `/feed.json` keeps serving with the app backgrounded or the screen off. **Stop server** in the app or **Stop** on the notification ends it.

One-screen Android app (Kotlin + Compose) that serves a Mapidex feed over Wi‑Fi so the Playdate can pull it.

- `GET /feed.json` — stub Mapidex feed; `you_are_here` updates live from GPS
- `GET /health` — `{"ok":true}`
- Port **8080**, server lives as long as the app process (no foreground service in v0)

## Open & run
1. Android Studio → **Open** → `~/Documents/GitHub/mapidex/companion` (let Gradle sync; JDK = Studio's bundled JBR)
2. Plug in phone (USB debugging on) or start an emulator → **Run ▶ app**
   - CLI: `./gradlew installDebug` (APK: `app/build/outputs/apk/debug/app-debug.apk`)
3. Grant **location** when asked (or tap *Grant location*)
4. Tap **Start server** — screen shows `http://<phone-ip>:8080/feed.json`

## Live route (v0.3)
Route section: pick a vibe (relax / play / social / essentials / no detour) → **Build route**.
Uses saved Work/Home (or defaults). Phone needs internet.
- vibe: OSRM office→home for the corridor → Overpass POIs (3 mirrors) → nearest named place in that bucket to mid-route → OSRM office→via→home with steps
- no detour (or no place found): OSRM office→home
Result lands in `/feed.json` → `route` (+ `detour.status`). On failure the feed keeps the last good route and `detour.status = "error"`.
Port of `mapidex/pipe/commute_detour.py`. Public OSRM demo ignores the foot profile, so `summary` minutes are a walking estimate (1.4 m/s) from distance.

## Test from laptop
Laptop and phone on the **same Wi‑Fi**:
```
curl http://<phone-ip>:8080/health
curl http://<phone-ip>:8080/feed.json
```
or open the URL in a browser.

Emulator: the emulator IP isn't reachable from the LAN — use `adb forward tcp:8080 tcp:8080` then `curl localhost:8080/feed.json`. Set a fake GPS in emulator **Extended controls → Location**.

## Playdate
Playdate must be on the same Wi‑Fi as the phone and fetch `http://<phone-ip>:8080/feed.json`.

## Stack
AGP 8.5.2 · Kotlin 2.3.21 (+ compose compiler plugin) · Gradle 8.7 · compileSdk 35 · Compose BOM 2024.02.00 · ML Kit GenAI Prompt 1.0.0-beta4 · NanoHTTPD 2.3.1 · LocationManager (GPS + network) · minSdk 26 / targetSdk 34

## Capture receive (v0.5)

`POST /capture` multipart — `image` (hi-res PNG, gaze dot burned in), `gaze_x`, `gaze_y`, `worn`, `ts` (+ optional `scene_w`, `scene_h`, `meta`).
Saves `<filesDir>/captures/cap-<ts_ms>.png` + `.json`, returns `{"ok":true,"id":...}`. Logcat tag `MapidexCapture`.
Read back: `GET /captures`, `GET /capture/latest.json`, `GET /capture/latest.png`. Ask/Nano reads from `filesDir/captures`.

```
curl -F image=@snap.png -F gaze_x=800 -F gaze_y=600 -F worn=true -F ts=$(date +%s) http://<phone-ip>:8080/capture
```
