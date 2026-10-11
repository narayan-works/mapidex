# MapéDex

<p align="center">
  <img src="pitch/mapedex-logo.png" alt="MapéDex" width="280">
</p>

<p align="center"><b>A Playdate Pokédex for the walk home.</b></p>

Pick a vibe. Bend the commute. Capture what you’re looking at.  
Ask names what’s under your eyes — not another map app to manage.

---

## What it is

**MapéDex** turns a work → detour → home walk into something you can *collect*.

- **Playdate** is the toy UI — Path, Capture, Ask, logs (1-bit, crank, A/B).
- **Pixel companion** is the quiet pocket brain — GPS, OSM routes, hi-res photos, on-device Ask.
- **Pupil Labs Neon** is the eyes — scene frame + gaze, so Ask knows *what* you meant.

You walk. The phone thinks. The Playdate feels like a Pokédex.

---

## How it works

```
Neon (eyes)  →  Pixel (pocket)  →  JSON wire  →  Playdate (toy UI)
 scene+gaze     GPS · OSRM/OSM     /feed.json      Menu · vibe · Path
                Capture store      /ask.json       tray Ask/Tasks/Capture
                Nano → Wiki/OSM                    looking it up… · Ask
                Gemini fallback                    Captures (recents)
```

1. Pick a **vibe** on Playdate → phone builds office → detour → home (`POST /route`).
2. Walk the **Path**. Tap **Capture** → Neon snap (hi-res + gaze) lands on the phone.
3. Phone runs **Ask**: Gemini Nano (under the gaze dot) → web hours/places → Gemini API if thin.
4. Playdate shows **looking it up…** then the Ask card. Phone keeps the full archive.

Pitch walkthrough (web): [narayan-works.github.io/mapidex/pitch](https://narayan-works.github.io/mapidex/pitch/)

---

## Repo layout

| Folder | What |
|---|---|
| [`demo/`](demo/) | **Mapidex - demo** — baked walk, no phone. Reliable stage demo. |
| [`live/`](live/) | **Mapidex - live** — same app, pulls phone feed + Neon Capture over Wi‑Fi. |
| [`companion/`](companion/) | **Android pocket engine** — server, GPS, OSRM/Overpass, Ask cascade, logs. |
| [`pitch/`](pitch/) | Click-through web overview (Playdate yellow + 1-bit). |
| `demo/pipe/` | Optional Python OSM detour script (reference for the Kotlin port). |

---

## What’s built

**Works**
- Playdate Menu → vibe → Path → tray (Ask · Tasks · Capture) → looking it up → Ask
- Companion auto-starts LAN server · Places (Home/Work) · Captures · Ask log · Detour log · Daily detours
- Neon Capture (Sim → Mac Neon monitor → phone) · gaze dot for the model
- Ask cascade: **Nano → Wiki/OSM enrich → Gemini cloud**
- Vibe on Playdate triggers **`POST /route`** (no phone Build needed for the story)
- Demo zip for offline stage walks

**Still soft / unfinished**
- Continuous you-are-here smoothing while walking
- Hardware Playdate → phone (Sim is the proven hop today)
- Neon battery row on companion (worn works)
- Overpass can flake → falls back to a direct route with a note

---

## Quick start

### Demo (no phone)

```sh
cd demo
pdc Source mapidex.pdx
# open mapidex.pdx in Playdate Simulator
```

### Live + companion

1. Install companion on a Pixel (Studio JBR helps):

```sh
cd companion
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew installDebug
```

2. Open **Mapidex companion** — server auto-starts. Note the LAN IP. Set **Home** and **Work** to *different* places.

3. Point live at the phone — `PHONE_IP` in `live/Source/main.lua` — then:

```sh
cd live
pdc Source mapidex-live.pdx
```

4. Same Wi‑Fi. Sim: Start → pick a vibe → A. Path should say **live from phone**.

5. Neon Capture (Sim): Neon monitor on Mac (`localhost:8787`), glasses on, Path → Capture → looking it up → Ask.

Optional Gemini cloud key (never commit):

```properties
# companion/local.properties
GEMINI_API_KEY=your_key_here
GEMINI_MODEL=gemini-flash-latest
```

---

## Companion endpoints (port 8080)

| | |
|---|---|
| `GET /health` | ok · nano_status · gemini_key |
| `GET /feed.json` | live route + you_are_here |
| `POST /route?vibe=…` | build office→detour→home |
| `POST /capture` | hi-res + gaze → Ask cascade |
| `GET /ask.json` | title · blurb · layers · gaze |
| `POST /verify` | task pass/fail |

---

## Stack & credits

- [Panic Playdate](https://play.date/) — UI / crank / 1-bit
- [Pupil Labs Neon](https://pupil-labs.com/) — scene + gaze
- Pixel 9 Pro XL — pocket brain
- OSRM + OpenStreetMap / Overpass — walking routes & places
- Gemini Nano (AICore) + Gemini API — Ask under the gaze dot
- Wikipedia / OSM enrich — hours & facts

Original MapéDex UI + Narayan’s WAVs — not Nintendo assets.

---

## Who

**Narayan Ashanahalli** · [narayan.works](https://narayan.works) · [hello@narayan.works](mailto:hello@narayan.works)

---

<p align="center"><i>Walk. Capture. Ask. Pocket engine optional. Joy required.</i></p>
