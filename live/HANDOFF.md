# Mapidex — handoff log

Silent tracking by **logger** so this can continue in Claude / Antigravity / another bot if Grok dies.  
Last updated: **2026-10-10 ~8:58 PM PT** (group chat: `mapidéx - Play Date`)

---

## What this is

**Mapidex** — Playdate commute + Pokédex: at work, pick a category, get **one walk work → detour in the middle → home**, crank turns on that bent path, Capture/Ask/Tasks on top. AI on laptop; device shows state.


### Planned IA — Narayan confirmed ~8:24 PT (say **build** to implement)

**Home menu** (replaces category-as-start):
1. **Start** → vibe pick → loading → Path  
2. **Detour log** → finished walks (when / vibe / place) — ≠ Captures  
3. **Captures** → two folders  
4. **Daily tasks** → task list + XP  

**Vibe pick** (after Start):
- Categories + final **no detour** (last; don’t steal default)  
- **no detour** = straight OSRM work→home (no Via, no ▲)  
- Bottom center soft flicker: **select the vibe!**  

**Captures**
- Two-up **folders**: left = free · right = daily-task  
- Crank switches folder · A opens folder  
- Inside folder: **4-across** image tiles wrapping down; A opens; preview mid-side arrows + crank/d-pad  
- Opened chrome: location **BL** · time/date **BR** (both); task name **TR** on task captures only  

**Path** (in a walk)
- Header: **detour** (+ vibe / place) — no `work -> detour -> home` string  
- Map = scaled zigzag, start bottom, ▲ on Via (unless no detour)  
- Tray: **Ask → Tasks → Capture**  
- 3-2-1: breathing logo, blank digits punched through, dotted field  
- Footers: B left / A right  

**Daily tasks:** growing arc (3px track / 7px progress) · padded `x/5 done`  

**Still true:** one bent walk work→waypoint→home when a vibe is chosen; category/vibe after Start.

Status: **built** (~8:34 PT). Demo captures seeded.

### UI polish checklist — **done** (~8:49 PT)
1–10 from go: Menu + `<`, even rows, small titles, log spacing + date/time, Captures counts BL + GG dummies, Path vibe-only, vibe prompt moved up.

### Captures / log follow-ups (also landed)
- **Detour log:** list above footer (no B Back overlap); **scrollbar on the right**; crank/↑↓ scrolls  
- **Captures grid:** **4 image tiles in a row**, then wrap down (not 2×2 fill panels / stretched cells)  
- **Open preview:** `<` `>` mid-sides · **crank / d-pad** flip images  


### Demo ship — built, user finishing install (~8:53 PT)
- Launcher title: **Mapidex - demo** (`v0.1.0`)
- Cover / launchImage: logo + **demo** at bottom
- Artifacts: `/Users/narayan/Documents/GitHub/mapidex/mapidex.pdx` + `mapidex.pdx.zip` (~65KB)
- ~8:58: Simulator quit + PLAYDATE drive ejected so device is normal launcher again. Narayan will drop `mapidex.pdx.zip` himself via sideload when ready.
- Walkthrough: **Menu → Start → vibe → Path → Ask → Capture → Captures grid → Daily tasks**
- Not Catalog — sideload only

### Hardcoded → live plug map (architect)
**Pipe / laptop (half-live)**
1. Office/home latlng (`DEFAULT_*` in commute_detour.py) → live GPS or saved places
2. Waypoint pick — first POI in bucket; Via baked in Sim → OSRM **via** that place
3. Feed into Playdate — baked `feed.lua`/`entry.lua` from last_feed.json → laptop writes, device **loads** (HTTP/USB), not compile-in
4. Loader density 0→1 — timer → real fetch progress

**On-device stubs**
5. Ask — ask_demo.lua Golden Gate → Pupil capture + vision/lookup JSON
6. Ask image — fixed entry_vert → dithered capture from laptop
7. You-dot — crank/step fake → GPS snapped to path (`you_are_here`)
8. Daily tasks — hardcoded leaves/doors/signs ×5 → remote or day-seeded
9. Captures / Detour log — local + demo seeds → metadata from capture event
10. XP/levels — local OK for MVP; optional sync later

**OK hardcoded for now:** vibe list, OSM→bucket map, UI chrome/fonts/tray

**Plug order:** (1) live feed load (2) OSRM via waypoint (3) Ask from real capture (4) live location on path (5) office/home from GPS

UI checklist ≠ plug map — Sim polish waits on **go**; plug map is separate.

### Concept locked (2026-10-10 ~7:31 PT) — Narayan confirmed

**Mapidex Path = one walk:**
1. At **work**, going **home**
2. Pick category (play / social / …)
3. Build **one OSRM path**: work → **one waypoint (detour)** → home
4. **Map** = **scaled zigzag from real turns**: each L/R changes heading, each segment length ∝ that step’s distance, then fit to the card. Toy but true to turn+distance scale. **Orientation:** **start at bottom**, walk up the card; zigzag mirrored so **map L/R = turn arrows**. Markers scale with map box: **office ○ and home ■** same **small** matched size (don’t grow with ▲); **detour = equilateral ▲** a bit larger (~1/14 of map short side), point-up. Via badge: ▲ fills circle larger. No rings-as-place.
5. **Crank** = turns on that walk (+ **Via** at the ▲). Arrive label = **home** (not Greenwich address). Capture / Ask / Tasks on top.

**Killed:** “second mode” after last turn that flipped to Fort Mason / Museo / Marina Green with **3 dots**. That was our bug, not his ask.

**Sim now:** e.g. **play** → via Marina Green (~1.5 mi).  

**Open later (ideation, not blocking):** when category has 3 candidates, which becomes the waypoint — first / closest / user picks once?

Team in room (participation rule ~7:58 PT):
- **mapidéx** — day-to-day Playdate build (primary voice on Sim iterates)
- **architect** — only system locks / “this will break”
- **logger** — only **big** shifts into `HANDOFF.md` (not every crank)
- **ideation / designer** — when Narayan pulls them (feel, web shell), not every screenshot

Don’t all answer the same Sim frame.


---

## Paths (do not move SDK)

| What | Path |
|------|------|
| Playdate SDK (toolchain only — **not** in git) | `/Users/narayan/Developer/PlaydateSDK` |
| Game repo | `/Users/narayan/Documents/GitHub/mapidex` |
| Built bundle | `/Users/narayan/Documents/GitHub/mapidex/mapidex.pdx` |
| Source of truth for baked “feed” | `Source/entry.lua` |

Env: set `PLAYDATE_SDK_PATH=/Users/narayan/Developer/PlaydateSDK` for `pdc` / Simulator. Shell on Narayan’s Mac often has it unset until the profile loads.

---

## Repo layout (current)

```
mapidex/
  .gitignore
  HANDOFF.md
  Source/
    main.lua              # splash → start → loading → path → ask/saved
    feed.lua              # commute feed for current flow
    entry.lua             # generated from last_feed.json (legacy card shape)
    pdxinfo               # Mapidex - demo v0.1.0
    SystemAssets/         # system launch / launcher art
      launchImage.png     # OS load screen (1-bit MaPéDex wordmark)
      card.png
      icon.png
    images/
      splash.png          # in-game splash (~same wordmark)
      preview.png         # viewfinder / “live” shot
      entry.png           # 1-bit card art
  assets/
    golden-gate-source.png
    mapedex-logo-source.png   # color MaPéDex logo used to make 1-bit splash
  pipe/
    commute_detour.py     # OSRM + Overpass → last_feed.json
    last_feed.json        # latest thin feed
  web-shell/
    index.html            # designer Playdate mirror @ localhost:8765
    last_feed.json        # copy for string lengths
  mapidex.pdx/            # build output (gitignored)
```

**Not on GitHub yet** — local only under Documents/GitHub. mapidéx offered to push when Narayan says go.

---

## How the toolchain works

1. `PLAYDATE_SDK_PATH` → SDK root  
2. `pdc Source mapidex.pdx` (or SDK build) compiles Lua + images → `.pdx`  
3. Playdate Simulator (or device) runs `mapidex.pdx`  
4. Only the **project** belongs in git — never the SDK

---

## Screen map + IA (updated for commute-detour test flow)

**Simulator flow now (mapidéx):**
`splash → start (at work + category) → loading → path → ask | capture → saved`
B from saved → start. Crank on **path** only.

| State | Role | Notes | Status |
|-------|------|-------|--------|
| splash | logo | skip with A/S | done |
| start | **at work** badge (top right) + pick category ↑/↓, A/S commit | social pre-highlighted; B opens Saved | done |
| loading | fake OSRM hold (~1.1s) | swap for real `commute_detour.py` next | done (fake) |
| path | big circled arrow + onto/ft bar; full-height map; place pins on detour stops | A actions / B Back; tray | iterating to sheet |
| ask | full-height image L / info R; `1/N` + layer | A=Capture card; B=Back | matched |
| tasks | 3 dailies, ring x/5, 15 XP/level | A=+1 (3-2-1); B=Back | matched |
| saved | persisted list (category · label) | A/B back to start | done |
| idle / receiving / error | earlier pipe IA | not in this test flow yet | parked |

**Earlier pipe IA (still valid for later):** splash→idle→receiving→catch→card; receiving never times out into catch; AI on laptop only.

**Voice packs** — still open (Minimal vs Pokédex-ish).

**Feel (ideation) for this flow**
- **at work** = quiet context badge, not a button  
- Category = one tap commit (no double confirm)  
- Crank currently: **TBT (turn+distance)** then place names — Narayan asked for this; may collapse to gist+places later  
- **Ask** = soft entry into Pokédex card (simulated GG); **Capture** = heavier “caught it” / save  
- When reacting, note whether you’re judging **Directions** or **Pokédex** mode  
- Saved = names + category only for now  


## App flow (what’s built — Simulator)

Code states: `splash → start → loading → path → ask | saved` (`Source/main.lua`, data from `feed.lua` / pipe).

1. **Splash** → start (A/S skip)  
2. **Start** — “at work” top-right; pick **relax / play / social / essentials** (↑/↓); **A/S** loads; **B** → Saved  
3. **Loading** — MaPéDex logo **sparse→full density** (~1.5s placeholder; later = real pipe progress)  
4. **Path** — summary + path mini-draw; **crank** / ←→ through **live OSRM maneuvers** (`Turn right · 197 ft`, …) then **3 detour places** (e.g. Mel’s → Naked Fish → Mamacita)  
5. **Ask** — vertical GG card; crank location→facts→hours; A Capture / B Back.  
6. **Capture** / **Tasks** via Path tray (3-2-1 on Capture/Ask/task +1).  

**Two modes on device (keep distinct):**
1. **Directions mode** — path crank = turn + distance (+ places) — Maps energy; OSRM live  
2. **Pokédex mode** — Ask → Golden Gate card = catch + learn — Mapidex soul; Ask is simulated  

If TBT crank feels like work later: collapse directions to **gist + places only**, leave turn-by-turn on the phone.

Controls reminder: keyboard **S** = Playdate A, **A** = Playdate B (or click yellow buttons).


## Fonts + screen (Playdate constraints)

Full designer simulation rules are under **Designer track** below. Summary:

**Device canvas:** always **400×240** px, 1-bit. Safe margins ~**8px** → usable ≈ **384×224**.  
Web mocks: design IA inside a **400×240** frame (or 2× at **800×480**); web can be larger for editing, overflow judgments happen in that box.

**System fonts** (no bundle needed):
- Roobert 10/11/20/24 Medium & Bold
- Asheville Sans 14 Light/Bold, 24 Light
- Load e.g. `gfx.font.new("/System/Fonts/Roobert-11-Medium")`

**SDK copy-into-game** (`Resources/Fonts/`): Asheville, Bitmore, Crooked Park, Cuberick, Full Circle, Mikodacs-Clock, Newsleak Serif, Nontendo, Oklahoma, Pedallica, Quickboot, Rains, Roobert, Sasser Slab.

**Type sizes (architect, after overlap bug)**
- Path / UI chrome → **Roobert-11** (was 20 — too big, collided with path box)
- Title ≈ **Roobert-11/14**; body **10/11**
- **Roobert-20** only for short titles (e.g. splash / card), not path header

**Glyph gotcha**
- Middle-dot `·` (and fancy dashes `—`) **missing in Roobert** → Playdate draws **`?`**
- Seen on path: `foot ? 0.9 mi ? 4 min`, `Head out ? 118 ft`
- Fix in copy: use `-` or spaces → `foot - 0.9 mi - 4 min`

## Loading / logo

- Splash / system launch still use MaPéDex 1-bit wordmark assets.
- **Post-category loader:** logo fills **sparse → full density** (~1.5s timer; later = pipe 0→1). Silent. Density *is* the progress bar.
- **Do not** replace with diagonal blank slits / chunky-block side-fixes (tried ~8:07, reverted).


## Designer track (web UI mirror)

**Role:** @designer builds a **simple web UI** mirroring Simulator screens so Narayan can design interactions in 1:1. Integrate clear UI back into sim later.  
**Out of scope:** OSRM/Overpass, Lua, phone GPS. **Live copy:** `pipe/last_feed.json` (use real string lengths so web fails like device).

**Screens:** Start → Loading → Path → Ask/card → Capture/Saved

### Playdate UI capabilities (source of truth — mapidéx + architect)

**Display**
- **400×240**, **1-bit** only (no gray/color/alpha blends)
- Default **30 fps** (max 50) — chunky frames, not continuous scroll
- Invert = full-screen trick (catch beat), not a style layer
- Usable after ~8px margin ≈ **384×224**
- Images = 1-bit `.pdi`; text on busy dither fails
- Max **two competing regions** (e.g. path graphic | copy). Three chrome bands = overflow

**Input (the product)**
- **A / B** only (two actions). Sim: **S = A**, keyboard **A = B**
- **D-pad** 8-way (↑↓ category, ←→ crank fallback)
- **Crank** = continuous angle; our UX = **discrete ticks** (`getCrankTicks`) → web = prev/next stops, **not** scrollbar scrub
- Menu button = system pause/exit — not app chrome
- **No touch, no keyboard, no cursor**

**Type**
- System Roobert: **10 Bold, 11 Medium/Bold, 20 Medium, 24 Medium**; Asheville Sans 14/24
- Missing glyphs → `?` — charset: `A-Z a-z 0-9 - / ->` only (no `·` `→` `…` `—`)
- ~**35–40 chars/line** at 11pt (~**36** measure for web); **20pt = short titles only**

**CoreLibs UI (available)**
- `gridview` — lists/menus with crank or d-pad (category, Saved)
- `crankIndicator` — “extend the crank” when docked
- `drawTextInRect` — wrap/truncate into a box (prefer over free `drawText`)
- Sprites / tilemaps / 9-slice — chrome, not CSS layout
- System menu — max **3** custom items; not in-app nav

**Not available:** touch, hover, scrollbars, sheets, color UI kit, arbitrary Unicode, semi-transparent overlays (1-bit: XOR/invert/patterns only)

**Controls — evolution → Path action tray (locked intent)**

Hardware is only **A / B / d-pad / crank**. “More buttons” = more meanings.

**SDK timing (architect)**
- `buttonJustPressed` / `JustReleased` — edges  
- `AButtonHeld` / `BButtonHeld` — ~**1s** hold  
- Double-tap = DIY timestamps — **avoid as primary** (easy to miss)

**Earlier ship (stack + contextual B)** — still on sim/web until tray lands:
| Screen | A | B |
|--------|---|---|
| Start | Confirm category | Saved |
| Path | Ask | Capture |
| Ask / Saved | — | **Back** |

**Progress mode (Path) — Narayan markup + chrome lock**

**Chrome (only these labels):** **B** left · **A** right (e.g. `B Back` · `A actions`) — hardware order.  
No crank hints, no “A ok / B dismiss”, no rings on detour.

| Input | Meaning |
|-------|---------|
| **Crank / ←→** | Advance direction stops (depth) — no on-screen crank chrome |
| **↑↓** in tray | Move highlight (no hint text) |
| **B** | Back — leave path; dismiss tray without losing stop |
| **A** | Open tray **Ask · Tasks · Capture** (Ask highlighted first) → A confirms |

**Path layout (Narayan sheet = 1:1 source of truth)**
- Left: **big circled L/R/straight arrow** (~half left column height) + **onto/towards** + street + **`- X ft`** + **ft progress bar**  
- Gist drops **`foot`** only → e.g. `social  0.9 mi - 4 min`  
- **`in X ft` / `- X ft` = until this turn**, not total walk left  
- Right: **full-height flush** map (top → A/B band) — not floating/cut  
  - Inset **drawn route + all dots/pins** by **12px** (not just the frame) — nothing kisses the border; left copy must not clip into the map  
  - **Map grammar:** scaled zigzag · **start bottom / walk up** · L/R matches arrows · office ○ / home ■ small matched · **▲** scales larger (~1/14 short side) · you ● · **12px inset**  
  - **Crank:** turns on that walk (+ Via at ▲). Arrive = **home**. Arrow + where + `in X ft` sheet still applies.  
  - **Footer chrome:** **B left · A right** on every screen  
  - **Actions tray:** looser row spacing (~**26px**)  
- Chrome: only **A actions** / **B Back**  
- Under the name on Path: only the turn/place line — no empty pips / mystery dots  
**Path type / alignment (Narayan ref — locked)**
- **Gist / header:** category **left** (`play`) with **mi/min under it**; **detour place name top-right** (e.g. Marina Green)  
- **Center:** big circled arrow — **drop lower** (not kissing the gist); drawn thick / sprite so it always shows  
- **Left / small:** `onto` / `towards` — same size as **A actions**  
- **Left / big (where rule):** answers **where you turn** — street / next named street / `next corner`. Arrow *is* the turn; **never** orphan `Turn right` / `Head out` as the title. Fallback order if OSRM has no street: **cross street → nearest named place on route → next corner**. Never bare distance as the big line.  
- **Turn distance:** only **`in X ft`** — one `ft` on screen  
- **Bar:** fill only — no second `ft`  
- **Map:** **12px inset** — route/dots must not sit on the frame edge; full-height flush outer box still  
- Do **not** re-center onto/street; don’t put feet in the big name line  

**Do not:** remove arrows entirely; use giant arrow *without* copy; inconsistent/random-looking pins; leave map floating; center the whole left copy stack; orphan “Turn right”; use word **detour**; let pins/route kiss the frame  

**Plain English (place stop):** You picked a category (e.g. play), cranked past turns onto a **suggested stop**. The 3 pins are that category’s 3 options on the walk — not glitches. Big pin = the one you’re looking at.


**After tray confirm**
- **Capture / Ask** → **3-2-1 catch ritual** = holes punched through breathing logo:  
  1. **Light dotted** background (field has dots)  
  2. Breathing **logo ink**  
  3. **Solid blank** small `3`/`2`/`1` punched through (no dots inside the digit shapes) so they read clear  
  Wrong: white digit behind, full-frame invert, oversized punches, diagonal-slit loader. Tasks stays plain. Integer-pixel breath only.  
- **Tasks** → list immediately (**no** breathing countdown — not a Pokédex catch moment)

**Capture meanings (architect lock)**
- **Tray → Capture** = snap what’s in front (start a new catch)  
- **Ask → A Capture** = save *this* card to Saved  

**Ask (Pokédex)**
- **Full-height image left** (~160×200) / **info right** (title + `1/N` + real layer text) — stacked portrait was a deviation, reverted  
- **No** `whats that?` billboard; under title = readable crank line, never empty pips  
- Crank: location → facts → hours  
- A Capture / B Back to Path (keep stop)

**Tasks**
- 3 dailies; left = **SDK clean circular progress** — track **3px**, progress arc **7px** (1/5→5/5). Dots gone.  
- Wider gap name ↔ `x/5 done`; highlighted row taller with inset so `x/5` doesn’t kiss the black box  
- XP only here (**15 XP/level**); footer `Lv N` + `x/15` + bar + `A +1` / `B Back` only  

**Saved** after Capture → B Back to Path (not home). Start: A confirm / B Saved. No double-tap.

**Web mirror rules**
1. Fixed **400×240** stage (2× ok for eyes; layout math stays 400×240)  
2. Hardware = **two buttons**; on Path, A opens tray (**Ask · Tasks · Capture**), B = Back  
3. Crank = prev/next stop (buttons or dial), not scrollbar  
4. Path = **3 lines max** (category+gist | hero stop | dots) + optional path graphic — no billboard title + label + body  
5. Copy charset ASCII-safe as above  
6. Measure ~36 chars at 11pt  

**Path screen IA:** one band, three lines — category already chosen, don’t re-billboard it.

### Web shell status

- Path: `Documents/GitHub/mapidex/web-shell/` (`index.html` + copied `last_feed.json`)
- URL: **http://localhost:8765** (Cmd-Shift-R; if portfolio shows, wrong server/cache)
- Yellow bezel; LCD hard-coded **400×240**; fill **white** (sage bleed fixed via `#lcd` / `.pad` overflow clip)
- Outside LCD: crank +/- and A/B only
- First shell looked too “clean” / big / green — **IA possible, look wrong**; rebuilt to Simulator constraints

### Judge rule (ideation — stop spinning)

| Surface | Judge on |
|---------|----------|
| **Web shell** | Structure — what’s on screen, A/B, crank stops, hierarchy |
| **Simulator** | Texture — jagged 1-bit type is truth |

- Playdate **cannot** match browser anti-aliased smoothness. Same **layout hierarchy** yes; Retina-clean type **no**.
- If hierarchy matches and sim only feels uglier from pixels → designer is right.
- If hierarchy differs (extra lines, wrong buttons, UI outside LCD) → that’s the bug.

### Sim ↔ designer sync (mapidéx)

Mirrored designer IA into Simulator: Start = `pick a detour` + list; Path = 3-line band + path art + heavy **B Capture**; same A/B meanings. Compare structure side-by-side, not pixel polish.

## Path UI recipe (laptop → device)

Laptop converts; Playdate only draws. JSON coords are **card-local pixels** (already fitted).

1. Route in (OSRM polyline) → 2. Flatten local x/y → 3. Fit bbox ~180×100 → 4. Douglas–Peucker ~20–40 pts → 5. Ship `path: {w,h,pts}` (+ optional gist one-liner).  
Start ● / end ■; live `you_are_here` tick later. Picture of the walk, not navigation.

## Design notes (ideation)

- **Top view** = toy map of the walk (line + you-dot), not Google Maps.  
- Under Path name = only `onto X in Y ft` (until that turn).  
- Under Ask title = one readable crank line — never empty pips; prefer **`1/N` + text**.  
- On a detour stop = same map + **3 place pins** (how you see the bunch).  
- Capture → feel “caught”; Ask Capture vs tray Capture meanings stay distinct.  
- Logo: letter shapes first on splash.

---

## Pipe experiment (laptop) — office→home + detour buckets

**Goal:** pick category → build **one** walk office → **through a detour** → home → thin JSON. (Not: straight commute + 3 pin browser.)

**Status: live OSM + curated 12 + social wired to Playdate**  
- Script: `mapidex/pipe/commute_detour.py`  
- Output: `mapidex/pipe/last_feed.json` → generates `Source/entry.lua`  
- Stack (no Google key):
  1. **OSRM** `router.project-osrm.org` — walking polyline + distance/time  
  2. **Overpass** — POIs near path → raw candidates  
  3. **ideation curation** — re-bucket for Detour feel (machine finds, human picks 3)

**Ends (defaults baked in script)**  
- Office: **Founders Inc** @ Fort Mason — `37.8067, -122.4318` (script: `37.8067218, -122.4318093`)  
- Home: **2143 Greenwich St** — `37.7990, -122.4349` (script: `37.7989871, -122.4349331`)  
- Route: **foot · 0.9 mi · 4 min**

**Curated 3 per category (in `last_feed.json`)**

| Bucket | Places |
|--------|--------|
| relax | Equator Coffees, Réveille Coffee, Fort Mason Great Meadow |
| play | Fort Mason Center, Museo Italo Americano, Marina Green |
| social | Mel's Drive-in, Naked Fish Sushi, Mamacita |
| essentials | Marina Supermarket, Le Marais Bakery, Fort Mason Market & Deli |

Dataset rule: **3 max per bucket**, name-only for Playdate; lat/lng stay on laptop for pins. Prefer ~2–3 min walk off the foot path.

**Selected default:** `social`

**Playdate:** start→loading→path wired; path crank = OSRM TBT then places; Ask → Golden Gate card; Capture→Saved.

**How raw vs curated:** Overpass dumps tagged nearby; ideation swaps for Detour intents (e.g. Starbucks out, Great Meadow in; BodyROK/Lily Cai out).

**Feel (ideation)**
- Pick category first; short names; path + pins on laptop map later.  
- Card: one name per crank stop after gist — don’t dump whole list on one layer.

## Live location (planned — phone → laptop → optional Playdate)

Playdate has **no GPS**. Live position from **phone** (browser geolocation / Core Location).

**Flow**
1. Phone on same Wi‑Fi posts lat/lng to laptop (tiny local webpage)  
2. Laptop snaps to nearest point on OSRM polyline → `you_are_here` (card-local `[x,y]` or progress 0–1)  
3. Write into feed / map UI; later push to Simulator/device for path-layer tick  

**MVP cut:** phone/laptop shows live progress; Playdate can stay static card until pipe pushes that field.

**Feel (ideation)**
- Dot **only on path layer**, not every place stop  
- Snap hard to polyline; if off-corridor, freeze last good point  
- Update ~every **3–5s**, not every frame  
- Peripheral “I’m on the walk,” not Google Maps  

**Next:** mapidéx stubs phone→laptop geolocation page; architect agreed. Playdate tick after.

## Architecture next (architect)

- **Locked:** OSRM work→waypoint→home; scaled zigzag; start bottom; L/R=arrows; equilateral ▲ (+Via badge); footer B left/A right.  
- **Done in sim.** **Designer:** match 8765.  
- **Built** home menu IA. **HOLD** UI polish until Narayan says go.  
- **Plug track:** live feed → OSRM via → real Ask → you_are_here → GPS ends.

---

## Session timeline (this room)

| When (PT) | What |
|-----------|------|
| ~3:59–4:00 | SDK stays at Developer/PlaydateSDK; game under Documents/GitHub. |
| ~4:00+ | Hello-world scaffolded → Simulator “Mapidex / hello world”. |
| Later | Fake capture → 1-bit card with Golden Gate + 4 crank layers (baked `entry.lua`). |
| | Catch beat added (invert + “got it”). |
| | Narayan stuck on capture screen — key map + crank import crash. |
| | Crank import fixed; keys clarified (S=A, A=B). |
| ~4:14 | Narayan @logger: keep silent MD tracking for Claude/Antigravity handoff. |
| ~4:23 | Fonts: Roobert-20 title / Roobert-11 body. Launch + splash from MaPéDex logo. |
| ~4:24 | Logo too pixelated → more detail (~2×). |
| ~4:25 | Wordmark at **80%** size, more margin. Still awaiting Narayan OK. |
| ~4:26 | Ideated screen map + IA: splash→idle→receiving→catch→card; AI on laptop; copy Minimal proposed. Waiting Narayan on voice. |
| ~4:50 | Pipe rule: receiving never times out into catch. Voice packs Minimal vs Pokédex-ish offered. Geo full map = not MVP. |
| ~4:53 | Path UI recipe locked (laptop project/fit/simplify → card-local `path` JSON). Fake GG path layer offered to feel. |
| ~4:55 | Walk-screen data: yes on laptop; thin slice to device. Directions default = OpenRouteService (free). Google later optional. |
| ~4:59 | Pipe experiment: office→home + 4 detour buckets → thin JSON. |
| ~5:01 | OSM live pipe (OSRM+Overpass). |
| ~5:11 | Founders Inc → Greenwich; curated 12; social crank; live GPS planned. |
| ~5:16 | Simulator flow: start → loading → path → Ask / Capture → Saved. |
| ~5:20 | Path crank = OSRM TBT then places; Ask = GG card (simulated). |
| ~5:22 | `?` = missing `·`; 400×240; designer web mirror; path 3-line band. |
| ~5:25 | Playdate UI capabilities brief locked for designer. |
| ~5:53 | Web-shell :8765; judge web=structure / sim=texture. |
| ~6:08 | Path action tray direction locked. |
| ~6:40 | Tray Capture·Ask·Tasks; Ask vertical; Tasks XP footer. |
| ~6:59 | Chrome only A actions / B Back; Capture meanings. |
| ~7:12 | Path sheet 1:1; Ask split; detour pins = matching fills. |
| ~7:14 | Path type lock (center arrow / left copy hierarchy). |
| ~7:16 | One `ft`; bar fill-only; sprite arrows. |
| ~7:19 | Gist L/R; big line ≠ bare ft; arrow fallback. |
| ~7:23 | Where-rule; arrow lower; map 12px inset. |
| ~7:25 | (Wrong turn) Built Turns+Stops / 3 pins — Narayan rejected. |
| ~7:28–7:29 | HOLD on bent-path understanding. |
| ~7:31 | One path work→waypoint→home; 3-dot mode killed. |
| ~7:34 | Footer B left / A right; tried H/V abstract (wrong). |
| ~7:36 | Scaled zigzag map (not H/V). |
| ~7:38 | ▲ equilateral; start bottom; L/R = arrows. |
| ~7:39 | Marker scale + tray ~26px. |
| ~7:41 | Sideload path documented. |
| ~7:52 | Loader density 0→1; USB Data Disk tips. |
| ~7:53 | Breathing logo 3-2-1 (first pass). |
| ~7:55 | White digits through cutouts (first contrast pass). |
| ~7:56 | Tried black→white digit→logo (wrong). |
| ~7:57 | 3-2-1 = punch holes through breathing logo. |
| ~7:58 | Integer-pixel breath; room participation rule. |
| ~8:07 | Loader sparse→full restored; digits smaller inside logo. |
| ~8:08 | 3-2-1 dotted field + blank punched digits. |
| ~8:09 | Path header: category+mi/min left; detour name top-right. |
| ~8:11 | Tasks dots = 1 per done; wider name/`x/5` gap. |
| ~8:12 | Tasks dots vs arc clarified. |
| ~8:14 | Tasks growing arc locked. |
| ~8:16 | Tasks SDK clean arc. |
| ~8:17–8:18 | Tasks arc: track 3px / progress **7px**. |
| ~8:19 | Tray order: Ask → Tasks → Capture. |
| ~8:21 | Planned Home menu + Captures two-up (hold). |
| ~8:22 | Captures folders → tiles → full chrome. |
| ~8:24 | Plan confirmed. |
| ~8:34 | Built home/vibe/Captures/log; plug map documented. |
| ~8:49 | UI 10 done; Captures was 2×2. |
| ~8:51 | Captures = 4 tiles/row wrap down. |
| ~8:52 | Intent: demo app ship. |
| ~8:53 | Mapidex - demo v0.1.0 built. |
| ~8:56 | User felt stuck in game/Data Disk. |
| ~8:58 | Sim quit + PLAYDATE ejected; user will sideload zip themselves. |

---

## Open / next

- [ ] Narayan: sideload `mapidex.pdx.zip` himself when ready; walkthrough on device  
- [ ] Device walkthrough: Menu → Start → vibe → Path → Ask → Capture → Captures → Daily tasks  
- [ ] Plug track (architect): live feed load → OSRM via → real Ask → you_are_here → GPS ends  
- [ ] device re-upload after polish  
- [ ] Optional: GitHub push; feed schema doc  


## How to rebuild / open

```bash
export PLAYDATE_SDK_PATH=/Users/narayan/Developer/PlaydateSDK
cd /Users/narayan/Documents/GitHub/mapidex
"$PLAYDATE_SDK_PATH/bin/pdc" Source mapidex.pdx
open mapidex.pdx   # or open via Playdate Simulator
```

## Sideload to Playdate hardware

Build/run once in Simulator first so the `.pdx` is fresh.

1. USB: unlock Playdate → Simulator **Device → Upload Game to Device…** → `~/Documents/GitHub/mapidex/mapidex.pdx`  
   If Sim waits for disk: unlock, firm cable, **MENU+A** or Settings → System → **Reboot to Data Disk** until **PLAYDATE** mounts; install finishes, or cancel and drag `.pdx` into **Games**, then **eject** (don’t just unplug).  
2. No cable: upload `mapidex.pdx.zip` (~45KB, same folder) at [play.date/account/sideload](https://play.date/account/sideload) — appears when device is online.  
3. After loader change: re-upload fresh `.pdx` / `.pdx.zip`.

**On-device feel checklist:** crank L/R vs map · Via ▲ as detour · A tray Capture/Ask/Tasks with real B-left / A-right.

---

## Logger duty

Update this file on **big** shifts only (concept locks, control model, map grammar, ship/sideload, major bugs) — not every Sim polish. Keep it current enough that a cold Claude / Antigravity session can continue without the chat history. Stay quiet in the room unless logging a big shift or asked.
