import "CoreLibs/graphics"
import "CoreLibs/crank"
import "CoreLibs/timer"
import "feed"
import "ask_demo"
import "feed_live"
import "neon_snap" -- Capture -> Neon snapshot via http://127.0.0.1:8787/snap (Sim)

-- ===== LIVE CONFIG (edit me) =====
-- Phone companion (../mapidex-companion) serves the feed on Wi-Fi.
-- Set PHONE_IP to the IP the companion app shows, then rebuild.
-- (No-rebuild override: one-line URL in Data/com.narayan.mapidex.live/live_url.txt)
PHONE_IP = "192.168.43.52"
LIVE_FEED_URL = "http://" .. PHONE_IP .. ":8080/feed.json"
-- Fetched on every Start -> vibe -> loading. Falls back to Data/live_feed.json, then baked feed.lua.
-- =================================

local pd <const> = playdate
local gfx <const> = pd.graphics

local S_SPLASH <const> = "splash"
local S_HOME <const> = "home"
local S_VIBE <const> = "vibe"
local S_LOADING <const> = "loading"
local S_PATH <const> = "path"
local S_ASK <const> = "ask"
local S_TASKS <const> = "tasks"
local S_LOG <const> = "detour_log"
local S_CAP_FOLDERS <const> = "cap_folders"
local S_CAP_TILES <const> = "cap_tiles"
local S_CAP_VIEW <const> = "cap_view"
local S_COUNT <const> = "countdown"

local TRAY <const> = {"Ask", "Tasks", "Capture"}
local DAILY <const> = {
	{ id = "leaves", label = "capture leaves", max = 5 },
	{ id = "doors", label = "capture doors", max = 5 },
	{ id = "signs", label = "capture signs", max = 5 },
}
local XP_PER_LEVEL <const> = 15
local LEVEL_NAMES <const> = {"Sprout", "Walker", "Scout", "Catcher", "Ranger", "Dex Keeper"}

local state = S_SPLASH
local splashFrames = 40
local loadFrames = 0
local LOAD_HOLD <const> = 50
local fetchPending = false
local loadMode = "route" -- "route" | "ask"
local pathNote = nil -- short fail/cancel line on Path
local countFromAsk = false
local askLoadToken = 0 -- invalidate Ask-load callbacks on B cancel

local catKeys = {"relax", "play", "social", "essentials", "none"}
local HOME_ITEMS <const> = {"Start", "Detour log", "Captures", "Daily tasks"}
local catIndex = 1
local selected = "relax"
local homeIdx = 1
local stepIndex = 1
local askLayerIndex = 1
local trayOpen = false
local trayIdx = 1 -- Ask first
local taskIdx = 1
local saved = {} -- free + task captures
local detourLog = {}
local tasks = { leaves = 0, doors = 0, signs = 0 }
local xpTotal = 0
local capFolder = "free" -- free | task
local capTileIdx = 1
local capViewIdx = 1
local logScroll = 0 -- first visible detour log row (0-based)
local tasksFromHome = false

-- countdown
local countN = 0
local countAction = nil
local countTaskId = nil
local countTimer = nil

local titleFont = gfx.font.new("/System/Fonts/Roobert-20-Medium")
local bodyFont = gfx.font.new("/System/Fonts/Roobert-11-Medium")
local smallFont = gfx.font.new("/System/Fonts/Roobert-10-Bold")
local splashImage = gfx.image.new("images/splash")

-- short SFX (pdc converts Source/*.wav → .pda)
local askSfx = pd.sound and pd.sound.sampleplayer and pd.sound.sampleplayer.new("ask") or nil
local taskSfx = pd.sound and pd.sound.sampleplayer and pd.sound.sampleplayer.new("task") or nil
local function playSfx(sp)
	if sp then pcall(function() sp:play(1) end) end
end

-- logo density frames: sparse → full (wire real load progress later)
local LOAD_FRAMES <const> = 10
local loadImages = {}
for i = 0, LOAD_FRAMES - 1 do
	loadImages[i + 1] = gfx.image.new("images/load_" .. i)
end
local loadAnim = 1 -- 1..LOAD_FRAMES

local seedDemoData_log
local function seedDemoData()
	-- dummy Golden Gate for both capture folders (for now)
	saved = {
		{ kind = "free", label = "Golden Gate Bridge", location = "SF waterfront", when = "Oct 10 4:12p", image = "images/entry_vert" },
		{ kind = "free", label = "Golden Gate Bridge", location = "SF waterfront", when = "Oct 10 3:40p", image = "images/entry" },
		{ kind = "free", label = "Golden Gate Bridge", location = "SF waterfront", when = "Oct 9 2:10p", image = "images/preview" },
		{ kind = "free", label = "Golden Gate Bridge", location = "SF waterfront", when = "Oct 8 11:05a", image = "images/entry_vert" },
		{ kind = "task", label = "Golden Gate Bridge", task = "capture leaves", location = "SF waterfront", when = "Oct 10 5:01p", image = "images/entry_vert" },
		{ kind = "task", label = "Golden Gate Bridge", task = "capture doors", location = "SF waterfront", when = "Oct 9 1:20p", image = "images/entry" },
		{ kind = "task", label = "Golden Gate Bridge", task = "capture signs", location = "SF waterfront", when = "Oct 9 4:00p", image = "images/preview" },
		{ kind = "task", label = "Golden Gate Bridge", task = "capture leaves", location = "SF waterfront", when = "Oct 8 9:30a", image = "images/entry" },
	}
	seedDemoData_log()
end

seedDemoData_log = function()
	if #detourLog == 0 then
		detourLog = {
			{ vibe = "play", place = "Marina Green", when = "Oct 10", time = "5:12p" },
			{ vibe = "social", place = "Mel's Drive-in", when = "Oct 9", time = "6:40p" },
			{ vibe = "relax", place = "Reveille Coffee", when = "Oct 9", time = "8:15a" },
			{ vibe = "essentials", place = "Le Marais Bakery", when = "Oct 8", time = "7:40p" },
			{ vibe = "none", place = "straight home", when = "Oct 8", time = "8:05a" },
			{ vibe = "play", place = "Marina Green", when = "Oct 7", time = "6:00p" },
		}
	else
		-- ensure time field exists on older rows
		for _, e in ipairs(detourLog) do
			if not e.time then e.time = "12:00p" end
		end
	end
end

local function persist()
	pd.datastore.write({
		list = saved,
		log = detourLog,
		tasks = tasks,
		xpTotal = xpTotal,
	}, "mapidex")
end

local function restore()
	local data = pd.datastore.read("mapidex")
	if data then
		if data.list then saved = data.list end
		if data.log then detourLog = data.log end
		if data.tasks then tasks = data.tasks end
		if data.xpTotal then xpTotal = data.xpTotal end
	end
	-- keep real captures; only seed demo mocks on first run / empty list
	if not saved or #saved == 0 then
		seedDemoData()
	else
		seedDemoData_log() -- seeds log only if empty; never touches saved
	end
end
restore()

local function dayXp()
	local n = 0
	for _, t in ipairs(DAILY) do
		n += (tasks[t.id] or 0)
	end
	return n
end

local function levelInfo()
	local level = (xpTotal // XP_PER_LEVEL) + 1
	local into = xpTotal % XP_PER_LEVEL
	local name = LEVEL_NAMES[math.min(#LEVEL_NAMES, level)]
	return level, into, name
end

local function turnKind(text)
	local s = string.lower(text or "")
	if string.find(s, "^via ") then return "via" end
	if string.find(s, "left") then return "left" end
	if string.find(s, "right") then return "right" end
	if string.find(s, "arrive") or string.find(s, "destination") or string.find(s, "home") then return "arrive" end
	return "straight"
end

local function vibeLabel(key)
	if key == "none" then return "no detour" end
	return key
end

local function capturesOf(kind)
	local out = {}
	for _, c in ipairs(saved) do
		if c.kind == kind then out[#out + 1] = c end
	end
	return out
end

-- active category route: work → one detour → home (or none = straight)
local function currentRoute()
	return Feed.categories[selected]
end

local function pathGist()
	local r = currentRoute()
	local s = (r and r.summary) or ""
	s = s:gsub("^[Ff]oot%s*%-?%s*", "")
	return s
end

local function routeInstructions()
	local r = currentRoute()
	return (r and r.instructions) or {}
end

local function routePath()
	local r = currentRoute()
	return r and r.path
end

local function routeDetour()
	local r = currentRoute()
	return r and r.place
end

local function routeDetourIndex()
	local r = currentRoute()
	return (r and r.detourIndex) or 1
end

local function routeViaStep()
	local r = currentRoute()
	return (r and r.viaStep) or 1
end

local function turnPrep(text)
	local s = string.lower(text or "")
	if string.find(s, "onto") then return "onto" end
	if string.find(s, "arrive") or string.find(s, "destination") then return "to" end
	if string.find(s, "head") or string.find(s, "continue") then return "towards" end
	return "towards"
end

local function turnStreet(text)
	local s = text or ""
	local street = s:match("[Oo]nto%s+([^%-]+)")
	if street then return street:gsub("%s+$", "") end
	street = s:match("[Tt]oward[s]?%s+([^%-]+)")
	if street then return street:gsub("%s+$", "") end
	return nil
end

local function turnLabel(text, stepIdx)
	-- big line = WHERE you turn (street), never "Turn right" — arrow already says that
	local street = turnStreet(text)
	if street and street ~= "" then return street end
	-- look ahead for next named street in later instructions
	if stepIdx then
		local instr = routeInstructions()
		for i = stepIdx + 1, #instr do
			local nxt = turnStreet(instr[i])
			if nxt and nxt ~= "" then
				return nxt
			end
		end
	end
	-- last resorts: still a place, not a maneuver verb
	local s = string.lower(text or "")
	if string.find(s, "^via ") then
		local det = routeDetour()
		return (det and det.name) or text:match("^[Vv]ia%s+(.+)$") or "detour"
	end
	if string.find(s, "arrive") or string.find(s, "destination") or string.find(s, "home") then
		return Feed.home or "home"
	end
	if string.find(s, "head") then
		return "the path"
	end
	return "next corner"
end

local function turnDist(text)
	local s = text or ""
	local d = s:match("(%d+%.?%d*%s*f?t)")
	if d then return d end
	d = s:match("(%d+%.?%d*%s*mi)")
	if d then return d end
	if string.find(string.lower(s), "destination") then return "arrive" end
	return nil
end

local function turnCount()
	return #routeInstructions()
end


local function drawTriangle(cx, cy, rad)
	-- equilateral, point-up (clearest as a place mark)
	-- rad = distance center → tip
	local half = (rad * 866) // 1000 -- cos 30° ≈ 0.866
	local down = rad // 2 -- sin 30° = 0.5
	local x1, y1 = cx, cy - rad
	local x2, y2 = cx - half, cy + down
	local x3, y3 = cx + half, cy + down
	gfx.fillTriangle(x1, y1, x2, y2, x3, y3)
end

local function drawArrow(kind, x, y, r)
	-- always draw vector so arrow never vanishes if sprites miss
	r = r or 40
	local cx, cy = x + r, y + r
	gfx.setColor(gfx.kColorBlack)
	gfx.setLineWidth(3)
	gfx.drawCircleAtPoint(cx, cy, r)
	gfx.setLineWidth(4)
	if kind == "left" then
		gfx.drawLine(cx + 12, cy + 22, cx + 12, cy - 4)
		gfx.drawLine(cx + 12, cy - 4, cx - 20, cy - 4)
		gfx.drawLine(cx - 20, cy - 4, cx - 8, cy - 16)
		gfx.drawLine(cx - 20, cy - 4, cx - 8, cy + 8)
	elseif kind == "right" then
		gfx.drawLine(cx - 12, cy + 22, cx - 12, cy - 4)
		gfx.drawLine(cx - 12, cy - 4, cx + 20, cy - 4)
		gfx.drawLine(cx + 20, cy - 4, cx + 8, cy - 16)
		gfx.drawLine(cx + 20, cy - 4, cx + 8, cy + 8)
	elseif kind == "via" then
		-- bigger equilateral ▲ inside the circle badge
		drawTriangle(cx, cy, 22)
	elseif kind == "arrive" then
		gfx.fillCircleAtPoint(cx, cy, 12)
	else
		gfx.drawLine(cx, cy + 22, cx, cy - 20)
		gfx.drawLine(cx, cy - 20, cx - 12, cy - 6)
		gfx.drawLine(cx, cy - 20, cx + 12, cy - 6)
	end
	gfx.setLineWidth(2)
end

local function drawRing(x, y, n, maxn)
	-- clean circular progress (SDK drawArc — no segment scribble)
	local r = 12
	gfx.setColor(gfx.kColorBlack)
	gfx.setStrokeLocation(gfx.kStrokeCentered)
	gfx.setLineWidth(3)
	gfx.drawCircleAtPoint(x, y, r)
	if maxn < 1 or n <= 0 then
		gfx.setLineWidth(2)
		return
	end
	local show = n
	if show > maxn then show = maxn end
	local endAngle = (show / maxn) * 360
	gfx.setLineWidth(7)
	-- angles in degrees, clockwise from north (0)
	gfx.drawArc(x, y, r, 0, endAngle)
	gfx.setLineWidth(2)
end

local function pathStops()
	-- one chapter only: turns on the bent walk (includes Via detour + Arrive home)
	local stops = {}
	for _, s in ipairs(routeInstructions()) do
		stops[#stops + 1] = { kind = "turn", text = s }
	end
	return stops
end

local function wrapStep(delta)
	local n = #pathStops()
	if n == 0 then return end
	stepIndex = ((stepIndex - 1 + delta) % n) + 1
end

local function clearCountdown()
	if countTimer then
		countTimer:remove()
		countTimer = nil
	end
	countN = 0
	countAction = nil
	countTaskId = nil
	if state == S_COUNT then
		-- caller sets next state
	end
end

local function doCapture(fromAsk)
	local det = routeDetour()
	local entry = {
		kind = "free",
		category = selected,
		label = AskDemo.title,
		location = (det and det.name) or "along the walk",
		when = "just now",
		image = AskDemo.image or "images/entry_vert",
		summary = pathGist(),
	}
	table.insert(saved, 1, entry)
	persist()
	-- Neon snap → companion Ask cascade; stay on loading until /ask.json settles
	entry.when = "snapping..."
	askLoadToken += 1
	local token = askLoadToken
	loadMode = "ask"
	loadAnim = 1
	trayOpen = false
	state = S_LOADING
	NeonSnap.take(function(ok, data, err)
		if token ~= askLoadToken then return end
		if ok and data then
			entry.snap = true
			entry.label = "Neon snap"
			entry.when = data.when or "just now"
			entry.gaze = { x = data.gaze_x, y = data.gaze_y, worn = data.worn }
			entry.macPath = data.path
			if data.imagePath then
				entry.snapImage = data.imagePath
				if data.img then NeonSnap.remember(data.imagePath, data.img) end
				AskDemo.snapImage = data.imagePath
			end
			AskDemo.gaze = entry.gaze
			askLayerIndex = 1
			persist()
			AskLive.start(function(aok, adata, aerr)
				if token ~= askLoadToken then return end
				if aok and adata and type(adata.title) == "string" and adata.title ~= "" then
					entry.label = adata.title
				end
				askLayerIndex = 1
				persist()
				if aok then
					state = S_ASK
				else
					pathNote = aerr or "ask failed"
					state = S_PATH
				end
			end)
		else
			entry.when = "just now (no snap)"
			persist()
			pathNote = err or "no snap"
			state = S_PATH
		end
	end)
end

local function bumpTask(id)
	local def = nil
	for _, t in ipairs(DAILY) do
		if t.id == id then def = t break end
	end
	if not def then return end
	local before = tasks[id] or 0
	if before >= def.max then return end
	tasks[id] = before + 1
	xpTotal += 1
	persist()
end

local function finishCountdown()
	local act = countAction
	local tid = countTaskId
	clearCountdown()
	if act == "Capture" then
		doCapture(countFromAsk)
	elseif act == "Ask" then
		askLayerIndex = 1
		state = S_ASK
	elseif act == "TaskBump" then
		if tid then bumpTask(tid) end
		state = S_TASKS
	else
		state = S_PATH
	end
end

local function stepCountdown()
	if countN <= 0 then
		finishCountdown()
		return
	end
	countTimer = pd.timer.performAfterDelay(1000, function()
		countN -= 1
		stepCountdown()
	end)
end

local function startCountdown(action, taskId, fromAsk)
	clearCountdown()
	pathNote = nil
	trayOpen = false
	countAction = action
	countTaskId = taskId
	countFromAsk = fromAsk and true or false
	countN = 3
	state = S_COUNT
	stepCountdown()
end

local function mapPoint(path, w, h, pt, x, y)
	-- inset so route/dot never kisses the frame
	local inset = 16
	local sw, sh = path.w, path.h
	return x + inset + (pt[1] * (w - inset * 2)) // sw, y + inset + (pt[2] * (h - inset * 2)) // sh
end

-- 0..1 how far a place sits between office and home (geog)

local function drawPathMini(x, y, w, h, progress)
	-- abstract bent walk: straight segments, triangle = detour
	local path = routePath()
	gfx.setColor(gfx.kColorBlack)
	gfx.setLineWidth(2)
	gfx.drawRect(x, y, w, h)
	if not path or not path.pts or #path.pts < 2 then return end
	local pts = path.pts
	local n = #pts
	local di = routeDetourIndex()
	if di < 1 then di = 1 end
	if di > n then di = n end

	-- you-index: progress already mapped so Via lands on detour
	local cut = math.floor(1 + progress * (n - 1) + 0.5)
	if cut < 1 then cut = 1 end
	if cut > n then cut = n end

	gfx.setLineWidth(3)
	for i = 1, cut - 1 do
		local x1, y1 = mapPoint(path, w, h, pts[i], x, y)
		local x2, y2 = mapPoint(path, w, h, pts[i + 1], x, y)
		gfx.drawLine(x1, y1, x2, y2)
	end
	gfx.setLineWidth(1)
	for i = cut, n - 1 do
		local x1, y1 = mapPoint(path, w, h, pts[i], x, y)
		local x2, y2 = mapPoint(path, w, h, pts[i + 1], x, y)
		gfx.drawLine(x1, y1, x2, y2)
	end
	gfx.setLineWidth(2)
	-- marker sizes from map box (simple scale)
	local m = w
	if h < w then m = h end
	local rEnd = m // 28
	if rEnd < 2 then rEnd = 2 end
	local rTri = m // 14
	if rTri < 5 then rTri = 5 end
	local rYou = m // 32
	if rYou < 2 then rYou = 2 end
	-- office = circle, home = square, detour = ▲
	local sx, sy = mapPoint(path, w, h, pts[1], x, y)
	local ex, ey = mapPoint(path, w, h, pts[n], x, y)
	gfx.drawCircleAtPoint(sx, sy, rEnd)
	gfx.fillRect(ex - rEnd, ey - rEnd, rEnd * 2, rEnd * 2)
	if selected ~= "none" then
		local vx, vy = mapPoint(path, w, h, pts[di], x, y)
		drawTriangle(vx, vy, rTri)
	end
	local dx, dy = mapPoint(path, w, h, pts[cut], x, y)
	gfx.fillCircleAtPoint(dx, dy, rYou)
end

local function drawSplash()
	gfx.clear(gfx.kColorWhite)
	if splashImage then
		gfx.setImageDrawMode(gfx.kDrawModeCopy)
		splashImage:draw(0, 0)
	end
end

local function drawHome()
	gfx.clear(gfx.kColorWhite)
	gfx.setImageDrawMode(gfx.kDrawModeFillBlack)
	gfx.setFont(smallFont)
	gfx.drawText("Menu", 8, 8)
	gfx.drawTextAligned("at work", 392, 8, kTextAlignment.right)
	gfx.setFont(bodyFont)
	for i, name in ipairs(HOME_ITEMS) do
		local y = 40 + (i - 1) * 32
		if i == homeIdx then
			gfx.fillRect(8, y - 4, 384, 28)
			gfx.setImageDrawMode(gfx.kDrawModeFillWhite)
			gfx.drawText(name, 16, y)
			gfx.drawTextAligned("<", 384, y, kTextAlignment.right)
			gfx.setImageDrawMode(gfx.kDrawModeFillBlack)
		else
			gfx.drawText(name, 16, y)
		end
	end
	gfx.setFont(smallFont)
	gfx.drawTextAligned("A open", 392, 220, kTextAlignment.right)
end

local function drawVibe()
	gfx.clear(gfx.kColorWhite)
	gfx.setImageDrawMode(gfx.kDrawModeFillBlack)
	gfx.setFont(smallFont)
	gfx.drawText("detour", 8, 8)
	gfx.drawTextAligned("at work", 392, 8, kTextAlignment.right)
	gfx.setFont(bodyFont)
	for i, key in ipairs(catKeys) do
		local y = 36 + (i - 1) * 28
		local label = vibeLabel(key)
		if i == catIndex then
			gfx.fillRect(8, y - 4, 384, 26)
			gfx.setImageDrawMode(gfx.kDrawModeFillWhite)
			gfx.drawText(label, 16, y)
			gfx.drawTextAligned("<", 384, y, kTextAlignment.right)
			gfx.setImageDrawMode(gfx.kDrawModeFillBlack)
		else
			gfx.drawText(label, 16, y)
		end
	end
	-- flickering prompt between last row and footer
	local blink = (pd.getCurrentTimeMilliseconds() // 500) % 2 == 0
	if blink then
		gfx.setFont(smallFont)
		gfx.drawTextAligned("select the vibe!", 200, 188, kTextAlignment.center)
	end
	gfx.setFont(smallFont)
	gfx.drawText("B Back", 8, 220)
	gfx.drawTextAligned("A go", 392, 220, kTextAlignment.right)
end

local function drawAskLoading()
	-- Breathing Pokédex logo + small arc + "looking it up..."
	gfx.clear(gfx.kColorWhite)
	gfx.setColor(gfx.kColorBlack)
	gfx.setDitherPattern(0.25, gfx.image.kDitherTypeBayer8x8)
	gfx.fillRect(0, 0, 400, 240)
	gfx.setDitherPattern(1.0)
	if splashImage then
		local tms = pd.getCurrentTimeMilliseconds()
		local o = math.floor(2 * math.sin(tms / 200) + 0.5) -- whole-pixel pulse
		gfx.setImageDrawMode(gfx.kDrawModeWhiteTransparent)
		splashImage:draw(o, o)
	end
	-- small loading arc under logo (spins while we wait)
	local tms = pd.getCurrentTimeMilliseconds()
	local ang = (tms // 8) % 360
	local cx, cy, r = 200, 188, 10
	gfx.setImageDrawMode(gfx.kDrawModeCopy)
	gfx.setColor(gfx.kColorWhite)
	gfx.fillCircleAtPoint(cx, cy, r + 4) -- clear dither under the ring
	gfx.setColor(gfx.kColorBlack)
	gfx.setStrokeLocation(gfx.kStrokeCentered)
	gfx.setLineWidth(2)
	gfx.drawCircleAtPoint(cx, cy, r)
	gfx.setLineWidth(4)
	gfx.drawArc(cx, cy, r, ang, ang + 110)
	gfx.setLineWidth(2)
	gfx.setImageDrawMode(gfx.kDrawModeFillBlack)
	gfx.setFont(smallFont)
	gfx.drawTextAligned("looking it up...", 200, 208, kTextAlignment.center)
	gfx.drawTextAligned("B cancel", 200, 224, kTextAlignment.center)
end

local function drawLoading()
	if loadMode == "ask" then
		drawAskLoading()
		return
	end
	-- Logo density 0→1 via chunky 8x8 blocks (not single-pixel sparkle)
	gfx.clear(gfx.kColorWhite)
	local img = loadImages[loadAnim]
	if img then
		gfx.setImageDrawMode(gfx.kDrawModeCopy)
		img:draw(0, 0)
	elseif splashImage then
		gfx.setImageDrawMode(gfx.kDrawModeCopy)
		splashImage:draw(0, 0)
	end
	-- one-line live feed status under the logo
	gfx.setImageDrawMode(gfx.kDrawModeFillBlack)
	gfx.setFont(smallFont)
	local line = FeedLive.statusLine(nil)
	local tw = gfx.getTextSize(line)
	gfx.setColor(gfx.kColorWhite)
	gfx.fillRect(200 - tw // 2 - 4, 222, tw + 8, 16)
	gfx.setColor(gfx.kColorBlack)
	gfx.drawTextAligned(line, 200, 224, kTextAlignment.center)
end

local function drawTray()
	-- just the three actions; Path already shows A actions / B Back
	local row = 26
	local top = 128
	local boxH = 12 + #TRAY * row
	gfx.fillRect(8, top, 384, boxH)
	gfx.setImageDrawMode(gfx.kDrawModeFillWhite)
	gfx.setFont(bodyFont)
	for i, name in ipairs(TRAY) do
		local y = top + 8 + (i - 1) * row
		if i == trayIdx then
			gfx.drawText("> " .. name, 20, y)
		else
			gfx.drawText("  " .. name, 20, y)
		end
	end
	gfx.setImageDrawMode(gfx.kDrawModeFillBlack)
end

local function drawPathScreen()
	-- gist: category LEFT, mi/min RIGHT
	-- center: big arrow
	-- left: onto (small), street label (big, never bare ft)
	-- one "in X ft" + fill bar
	local pad = 8
	local footerY = 220
	local mapX = 234
	local mapW = 400 - mapX - pad
	local mapY = pad
	local mapH = footerY - pad - 2
	local leftW = mapX - pad - 8
	local leftCX = pad + leftW // 2

	gfx.clear(gfx.kColorWhite)
	gfx.setImageDrawMode(gfx.kDrawModeFillBlack)
	local stops = pathStops()
	local cur = stops[stepIndex]
	local nTurns = turnCount()
	local turnIdx = stepIndex
	-- map crank steps onto the abstract path so Via == triangle
	local path = routePath()
	local nPts = (path and path.pts and #path.pts) or 2
	local di = routeDetourIndex()
	local viaS = routeViaStep()
	local progress = 0
	if nTurns > 1 and nPts > 1 then
		if viaS >= nTurns or selected == "none" then
			-- straight walk: linear along path
			progress = (turnIdx - 1) / (nTurns - 1)
		else
			local cut
			if turnIdx <= viaS then
				local t = 0
				if viaS > 1 then t = (turnIdx - 1) / (viaS - 1) end
				cut = 1 + t * (di - 1)
			else
				local t = 0
				if nTurns > viaS then t = (turnIdx - viaS) / (nTurns - viaS) end
				cut = di + t * (nPts - di)
			end
			progress = (cut - 1) / (nPts - 1)
		end
	end

	-- Path header: vibe left (no "detour" word); place + gist right
	gfx.setFont(smallFont)
	gfx.drawText(vibeLabel(selected), pad, pad)
	local det = routeDetour()
	local detName = (det and det.name) or ""
	if selected == "none" then detName = "straight home" end
	gfx.drawTextAligned(detName, mapX - pad, pad, kTextAlignment.right)
	gfx.drawTextAligned(pathGist(), mapX - pad, pad + 14, kTextAlignment.right)

	local text = cur and cur.text or ""
	local kind = turnKind(text)
	local prep = turnPrep(text)
	local label = turnLabel(text, stepIndex)
	local dist = turnDist(text) or ""
	if kind == "via" then
		prep = "via"
		local det = routeDetour()
		label = (det and det.name) or label
		dist = ""
	elseif kind == "arrive" then
		prep = "to"
		label = "home"
	end

	local ar = 40
	drawArrow(kind, leftCX - ar, 44, ar)

	local ty = 44 + ar * 2 + 8
	gfx.setFont(smallFont)
	gfx.drawText(prep, pad, ty)
	gfx.setFont(bodyFont)
	gfx.drawTextInRect(label, pad, ty + 16, leftW, 40, nil, "...", kTextAlignment.left)

	if dist ~= "" and dist ~= "arrive" then
		local ftY = footerY - 28
		gfx.setFont(smallFont)
		gfx.drawText("in " .. dist, pad, ftY)
		local barX = pad + 90
		local barW = leftW - 94
		local barY = ftY + 4
		gfx.setLineWidth(2)
		gfx.drawRect(barX, barY, barW, 8)
		local fill = 0
		if nTurns > 0 then
			fill = ((turnIdx - 1) * (barW - 2)) // nTurns
		end
		if fill > 0 then
			gfx.fillRect(barX + 1, barY + 1, fill, 6)
		end
	end

	drawPathMini(mapX, mapY, mapW, mapH, progress)

	gfx.setFont(smallFont)
	gfx.drawText("B Back", pad, footerY)
	gfx.drawTextAligned("A actions", 400 - pad, footerY, kTextAlignment.right)
	-- one-line feed source / error (or brief Ask fail/cancel note)
	local mid = pathNote or FeedLive.statusLine(selected)
	gfx.drawTextInRect(mid, 60, footerY, 270, 16, nil, "...", kTextAlignment.center)

	if trayOpen then
		drawTray()
	end
end


local function drawAsk()
	-- full-height capture LEFT / info RIGHT (designed split)
	gfx.clear(gfx.kColorWhite)
	local pad = 8
	local imgH = 200
	local imgW = 160
	local imgX, imgY = pad, pad

	gfx.setImageDrawMode(gfx.kDrawModeCopy)
	local img = nil
	if AskDemo.snapImage then
		img = NeonSnap.image({ snapImage = AskDemo.snapImage }, AskDemo.image)
	end
	if not img then img = gfx.image.new(AskDemo.image) end
	if img then
		imgW, imgH = img:getSize()
		-- clamp to full-height band above footer
		if imgH > 200 then imgH = 200 end
		img:draw(imgX, imgY)
		gfx.drawRect(imgX, imgY, imgW, imgH)
		-- gaze marker on Ask card only (thumb may already have a dot; this is a fallback ring)
		local g = AskDemo.gaze
		if g and g.x and g.w and g.h and g.w > 0 and g.h > 0 then
			local gx = imgX + (tonumber(g.x) or 0) * imgW / g.w
			local gy = imgY + (tonumber(g.y) or 0) * imgH / g.h
			gfx.setColor(gfx.kColorWhite); gfx.fillCircleAtPoint(gx, gy, 5)
			gfx.setColor(gfx.kColorBlack); gfx.fillCircleAtPoint(gx, gy, 3)
		end
	else
		gfx.drawRect(imgX, imgY, imgW, imgH)
	end

	local rx = imgX + imgW + 10
	local rw = 400 - rx - pad
	gfx.setImageDrawMode(gfx.kDrawModeFillBlack)
	gfx.setFont(bodyFont)
	gfx.drawTextInRect(AskDemo.title, rx, pad, rw, 40)
	local nLayers = #AskDemo.layers
	if nLayers < 1 then nLayers = 1 end
	if askLayerIndex > nLayers then askLayerIndex = 1 end
	local layer = AskDemo.layers[askLayerIndex] or { tag = "about", text = "…" }
	gfx.setFont(smallFont)
	gfx.drawText(askLayerIndex .. "/" .. nLayers .. "  " .. (layer.tag or "?"), rx, 56)
	gfx.setFont(bodyFont)
	gfx.drawTextInRect(layer.text or "", rx, 76, rw, 100)

	gfx.setFont(smallFont)
	gfx.drawText("B Back", pad, 220)
	local hint = AskLive.busy and "looking it up..." or "crank layers"
	gfx.drawTextAligned(hint, 400 - pad, 220, kTextAlignment.right)
end

local function drawTasks()
	gfx.clear(gfx.kColorWhite)
	gfx.setImageDrawMode(gfx.kDrawModeFillBlack)
	gfx.setFont(smallFont)
	gfx.drawText("Tasks", 8, 8)
	gfx.drawTextAligned("at work", 392, 8, kTextAlignment.right)
	for i, tdef in ipairs(DAILY) do
		local y = 30 + (i - 1) * 48
		local n = tasks[tdef.id] or 0
		drawRing(24, y + 18, n, tdef.max)
		if i == taskIdx then
			-- padded highlight so label / x/5 don't kiss the black box
			gfx.fillRect(48, y, 340, 44)
			gfx.setImageDrawMode(gfx.kDrawModeFillWhite)
		end
		gfx.setFont(bodyFont)
		gfx.drawText(tdef.label, 60, y + 6)
		gfx.setFont(smallFont)
		gfx.drawText(n .. "/" .. tdef.max .. " done", 60, y + 28)
		if n >= tdef.max then
			gfx.drawTextAligned("ok", 376, y + 14, kTextAlignment.right)
		end
		gfx.setImageDrawMode(gfx.kDrawModeFillBlack)
	end
	-- level bar: one quiet band, then one action row (no crowding)
	local level, into, name = levelInfo()
	gfx.setFont(smallFont)
	-- leave gap under tasks
	gfx.drawText("Lv " .. level .. " " .. name, 8, 172)
	gfx.drawTextAligned(into .. "/" .. XP_PER_LEVEL, 392, 172, kTextAlignment.right)
	-- thicker bar so progress reads
	gfx.setLineWidth(2)
	gfx.drawRect(8, 190, 384, 14)
	local fill = (into * 380) // XP_PER_LEVEL
	if fill > 0 then gfx.fillRect(10, 192, fill, 10) end
	-- single action line only
	gfx.drawText("B Back", 8, 220)
	gfx.drawTextAligned("A +1", 392, 220, kTextAlignment.right)
end

local function drawScrollbar(x, y, h, scroll, total, visible)
	if total <= visible then return end
	gfx.setLineWidth(1)
	gfx.drawRect(x, y, 6, h)
	local thumbH = math.floor((visible / total) * h)
	if thumbH < 14 then thumbH = 14 end
	local maxScroll = total - visible
	if maxScroll < 1 then maxScroll = 1 end
	local thumbY = y + math.floor((scroll / maxScroll) * (h - thumbH))
	gfx.fillRect(x + 1, thumbY, 4, thumbH)
end

local function drawDetourLog()
	gfx.clear(gfx.kColorWhite)
	gfx.setImageDrawMode(gfx.kDrawModeFillBlack)
	gfx.setFont(smallFont)
	gfx.drawText("Detour log", 8, 8)
	gfx.drawTextAligned("at work", 392, 8, kTextAlignment.right)

	local listTop, listBot, rowH = 28, 210, 36
	local visible = (listBot - listTop) // rowH
	local total = #detourLog
	if logScroll < 0 then logScroll = 0 end
	if total > visible and logScroll > total - visible then
		logScroll = total - visible
	end
	if total <= visible then logScroll = 0 end

	if total == 0 then
		gfx.drawTextAligned("no walks yet", 200, 110, kTextAlignment.center)
	else
		local y = listTop
		for i = logScroll + 1, math.min(total, logScroll + visible) do
			local e = detourLog[i]
			gfx.setFont(bodyFont)
			gfx.drawText(e.place or "?", 8, y)
			gfx.setFont(smallFont)
			local vibe = vibeLabel(e.vibe or "")
			if vibe == "no detour" then vibe = "none" end
			local when = e.when or "Oct 10"
			local tod = e.time or "5:00p"
			gfx.drawText(vibe, 8, y + 18)
			gfx.drawTextAligned(when .. "  " .. tod, 384, y + 18, kTextAlignment.right)
			y += rowH
		end
		drawScrollbar(390, listTop, listBot - listTop, logScroll, total, visible)
	end
	gfx.setFont(smallFont)
	gfx.drawText("B Back", 8, 220)
end

local function drawCapFolders()
	gfx.clear(gfx.kColorWhite)
	gfx.setImageDrawMode(gfx.kDrawModeFillBlack)
	gfx.setFont(smallFont)
	gfx.drawText("Captures", 8, 8)
	gfx.drawTextAligned("at work", 392, 8, kTextAlignment.right)
	local freeN = #capturesOf("free")
	local taskN = #capturesOf("task")
	local leftOn = capFolder == "free"
	gfx.setLineWidth(2)
	if leftOn then gfx.fillRect(8, 32, 188, 160) else gfx.drawRect(8, 32, 188, 160) end
	if not leftOn then gfx.fillRect(204, 32, 188, 160) else gfx.drawRect(204, 32, 188, 160) end
	-- titles
	if leftOn then gfx.setImageDrawMode(gfx.kDrawModeFillWhite) else gfx.setImageDrawMode(gfx.kDrawModeFillBlack) end
	gfx.setFont(bodyFont)
	gfx.drawText("Captures", 20, 44)
	gfx.setFont(smallFont)
	gfx.drawText(freeN .. " items", 20, 172) -- bottom-left of panel
	if not leftOn then gfx.setImageDrawMode(gfx.kDrawModeFillWhite) else gfx.setImageDrawMode(gfx.kDrawModeFillBlack) end
	gfx.setFont(bodyFont)
	gfx.drawText("Daily tasks", 216, 44)
	gfx.setFont(smallFont)
	gfx.drawText(taskN .. " items", 216, 172) -- bottom-left of panel
	gfx.setImageDrawMode(gfx.kDrawModeFillBlack)
	gfx.drawText("B Back", 8, 220)
	gfx.drawTextAligned("A open", 392, 220, kTextAlignment.right)
end

local function drawCapTiles()
	gfx.clear(gfx.kColorWhite)
	gfx.setImageDrawMode(gfx.kDrawModeFillBlack)
	local list = capturesOf(capFolder)
	gfx.setFont(smallFont)
	gfx.drawText(capFolder == "task" and "Task captures" or "Captures", 8, 8)
	gfx.drawTextAligned(#list .. " tiles", 392, 8, kTextAlignment.right)

	-- 4-across tiles, then wrap to next row (image-like squares, not stretched panels)
	local cols = 4
	local gap = 6
	local gridTop = 28
	local gridBot = 210
	local gridLeft = 8
	local gridRight = 384
	local cell = (gridRight - gridLeft - gap * (cols - 1)) // cols
	local originX, originY = gridLeft, gridTop
	local total = #list
	local rowsVisible = (gridBot - gridTop + gap) // (cell + gap)
	if rowsVisible < 1 then rowsVisible = 1 end
	local visible = cols * rowsVisible
	-- keep selected row visible
	local selRow = (capTileIdx - 1) // cols
	local topRow = 0
	if selRow >= rowsVisible then
		topRow = selRow - (rowsVisible - 1)
	end
	local maxTop = math.max(0, math.ceil(total / cols) - rowsVisible)
	if topRow > maxTop then topRow = maxTop end

	for slot = 0, visible - 1 do
		local row = slot // cols
		local col = slot % cols
		local i = topRow * cols + slot + 1
		local x = originX + col * (cell + gap)
		local y = originY + row * (cell + gap)
		if y + cell > gridBot then break end
		gfx.setColor(gfx.kColorBlack)
		gfx.setLineWidth(2)
		gfx.drawRect(x, y, cell, cell)
		if i == capTileIdx and i <= total then
			gfx.drawRect(x + 2, y + 2, cell - 4, cell - 4)
		end
		if i <= total then
			local item = list[i]
			local img = NeonSnap.image(item, "images/entry")
			if img then
				gfx.setClipRect(x + 1, y + 1, cell - 2, cell - 2)
				gfx.setImageDrawMode(gfx.kDrawModeCopy)
				local iw, ih = img:getSize()
				-- fit inside tile (contain) — like photo tiles, not stretched fill
				local scale = math.min((cell - 4) / iw, (cell - 4) / ih)
				local dw = math.floor(iw * scale)
				local dh = math.floor(ih * scale)
				img:drawScaled(x + (cell - dw) // 2, y + (cell - dh) // 2, scale)
				gfx.clearClipRect()
			end
			gfx.setImageDrawMode(gfx.kDrawModeFillBlack)
		end
	end

	local totalRows = math.max(1, math.ceil(math.max(total, 1) / cols))
	drawScrollbar(390, gridTop, gridBot - gridTop, topRow, totalRows, rowsVisible)

	if total == 0 then
		gfx.setFont(smallFont)
		gfx.drawTextAligned("empty", 200, 110, kTextAlignment.center)
	end
	gfx.setFont(smallFont)
	gfx.drawText("B Back", 8, 220)
	gfx.drawTextAligned("A open", 392, 220, kTextAlignment.right)
end

local function drawCapView()
	gfx.clear(gfx.kColorWhite)
	local list = capturesOf(capFolder)
	local n = #list
	local item = list[capViewIdx]
	if not item then
		gfx.setFont(smallFont)
		gfx.drawText("missing", 8, 8)
		return
	end
	-- image fills middle band
	local img = NeonSnap.image(item, "images/entry_vert")
	if img then
		gfx.setClipRect(40, 24, 320, 168)
		gfx.setImageDrawMode(gfx.kDrawModeCopy)
		local iw, ih = img:getSize()
		local scale = math.max(320 / iw, 168 / ih)
		local dw, dh = math.floor(iw * scale), math.floor(ih * scale)
		img:drawScaled(200 - dw // 2, 24 + (168 - dh) // 2, scale)
		gfx.clearClipRect()
	end
	-- mid-side arrows
	gfx.setImageDrawMode(gfx.kDrawModeFillBlack)
	gfx.setFont(bodyFont)
	gfx.drawText("<", 12, 100)
	gfx.drawTextAligned(">", 388, 100, kTextAlignment.right)
	gfx.setFont(smallFont)
	if item.kind == "task" and item.task then
		gfx.drawTextAligned(item.task, 392, 8, kTextAlignment.right)
	end
	gfx.drawTextAligned(capViewIdx .. "/" .. n, 200, 8, kTextAlignment.center)
	if item.gaze and item.gaze.x then
		gfx.drawText(string.format("gaze %d,%d%s", math.floor(item.gaze.x), math.floor(item.gaze.y), item.gaze.worn and "" or " (not worn)"), 8, 8)
	end
	gfx.drawText(item.location or "", 8, 200)
	gfx.drawTextAligned(item.when or "", 392, 200, kTextAlignment.right)
	gfx.drawText("B Back", 8, 220)
	gfx.drawTextAligned("crank / < >", 392, 220, kTextAlignment.right)
end

-- Digit shape as filled rects (used to punch blank holes through the logo)
local function punchDigit(label, cx, cy)
	gfx.setColor(gfx.kColorWhite)
	local s = 7 -- small, sits inside the logo mark
	if label == "1" then
		gfx.fillRect(cx - s // 2, cy - 3 * s, s, 6 * s)
		gfx.fillRect(cx - 2 * s, cy - 3 * s, 2 * s, s)
		gfx.fillRect(cx - 2 * s, cy + 2 * s, 4 * s, s)
	elseif label == "2" then
		gfx.fillRect(cx - 2 * s, cy - 3 * s, 4 * s, s)
		gfx.fillRect(cx + s, cy - 3 * s, s, 3 * s)
		gfx.fillRect(cx - 2 * s, cy - s // 2, 4 * s, s)
		gfx.fillRect(cx - 2 * s, cy - s // 2, s, 3 * s)
		gfx.fillRect(cx - 2 * s, cy + 2 * s, 4 * s, s)
	elseif label == "3" then
		gfx.fillRect(cx - 2 * s, cy - 3 * s, 4 * s, s)
		gfx.fillRect(cx + s, cy - 3 * s, s, 6 * s)
		gfx.fillRect(cx - s, cy - s // 2, 3 * s, s)
		gfx.fillRect(cx - 2 * s, cy + 2 * s, 4 * s, s)
	else
		-- "go": punch a wide bar
		gfx.fillRect(cx - 3 * s, cy - s, 6 * s, 2 * s)
	end
end

local function drawCountdown()
	local label = (countN > 0) and tostring(countN) or "go"
	local catch = (countAction == "Capture" or countAction == "Ask")

	if not catch then
		gfx.clear(gfx.kColorWhite)
		gfx.setImageDrawMode(gfx.kDrawModeFillBlack)
		gfx.setFont(titleFont)
		gfx.drawTextAligned(label, 200, 90, kTextAlignment.center)
		gfx.setFont(smallFont)
		local hint = countAction or ""
		if countAction == "TaskBump" then
			for _, t in ipairs(DAILY) do
				if t.id == countTaskId then hint = t.label break end
			end
		end
		gfx.drawTextAligned(hint, 200, 160, kTextAlignment.center)
		gfx.drawTextAligned("B cancel", 200, 220, kTextAlignment.center)
		return
	end

	-- light dotted field, breathing logo ink, then SOLID blank digits (no dots in the holes)
	gfx.clear(gfx.kColorWhite)
	-- slight checker dither over whole screen
	gfx.setColor(gfx.kColorBlack)
	gfx.setDitherPattern(0.25, gfx.image.kDitherTypeBayer8x8)
	gfx.fillRect(0, 0, 400, 240)
	gfx.setDitherPattern(1.0) -- reset to solid

	if splashImage then
		local tms = pd.getCurrentTimeMilliseconds()
		local o = math.floor(2 * math.sin(tms / 200) + 0.5) -- whole-pixel pulse
		gfx.setImageDrawMode(gfx.kDrawModeWhiteTransparent) -- ink only on dotted field
		splashImage:draw(o, o)
	end
	-- punch solid white digits — clears dots + logo ink inside the number
	punchDigit(label, 200, 118)

	gfx.setImageDrawMode(gfx.kDrawModeFillBlack)
	gfx.setFont(smallFont)
	gfx.drawTextAligned(countAction or "Capture", 200, 210, kTextAlignment.center)
	gfx.drawTextAligned("B cancel", 200, 224, kTextAlignment.center)
end

function pd.update()
	pd.timer.updateTimers()
	FeedLive.update()
	NeonSnap.update()
	AskLive.update()

	if state == S_SPLASH then
		splashFrames -= 1
		drawSplash()
		if splashFrames <= 0 or pd.buttonJustPressed(pd.kButtonA) then
			playSfx(askSfx)
			state = S_HOME
		end
		return
	end

	if state == S_HOME then
		local ticks = pd.getCrankTicks(8)
		if ticks ~= 0 then homeIdx = ((homeIdx - 1 + ticks) % #HOME_ITEMS) + 1 end
		if pd.buttonJustPressed(pd.kButtonUp) then homeIdx = ((homeIdx - 2) % #HOME_ITEMS) + 1
		elseif pd.buttonJustPressed(pd.kButtonDown) then homeIdx = (homeIdx % #HOME_ITEMS) + 1
		elseif pd.buttonJustPressed(pd.kButtonA) then
			local item = HOME_ITEMS[homeIdx]
			if item == "Start" then
				catIndex = 1
				state = S_VIBE
			elseif item == "Detour log" then
				logScroll = 0
				state = S_LOG
			elseif item == "Captures" then
				capFolder = "free"
				state = S_CAP_FOLDERS
			elseif item == "Daily tasks" then
				tasksFromHome = true
				taskIdx = 1
				playSfx(taskSfx)
				state = S_TASKS
			end
		end
		drawHome()
		return
	end

	if state == S_VIBE then
		local ticks = pd.getCrankTicks(8)
		if ticks ~= 0 then catIndex = ((catIndex - 1 + ticks) % #catKeys) + 1 end
		if pd.buttonJustPressed(pd.kButtonUp) then catIndex = ((catIndex - 2) % #catKeys) + 1
		elseif pd.buttonJustPressed(pd.kButtonDown) then catIndex = (catIndex % #catKeys) + 1
		elseif pd.buttonJustPressed(pd.kButtonA) then
			selected = catKeys[catIndex]
			loadMode = "route"
			loadFrames = LOAD_HOLD
			loadAnim = 1
			fetchPending = true
			state = S_LOADING
		elseif pd.buttonJustPressed(pd.kButtonB) then
			state = S_HOME
		end
		drawVibe()
		return
	end

	if state == S_LOADING then
		if loadMode == "ask" then
			if pd.buttonJustPressed(pd.kButtonB) then
				askLoadToken += 1
				AskLive.cancel()
				pathNote = "ask cancelled"
				state = S_PATH
				return
			end
			drawAskLoading()
			return
		end
		if fetchPending then
			-- kick off live GET from update context (permission dialog may yield here)
			fetchPending = false
			drawLoading()
			-- Playdate vibe pick is the trigger: phone builds office -> vibe -> home, then we pull the feed
			FeedLive.start(LIVE_FEED_URL, nil, selected)
		end
		loadFrames -= 1
		local done = LOAD_HOLD - loadFrames
		if done < 0 then done = 0 end
		loadAnim = 1 + (done * (LOAD_FRAMES - 1)) // LOAD_HOLD
		if loadAnim < 1 then loadAnim = 1 end
		if loadAnim > LOAD_FRAMES then loadAnim = LOAD_FRAMES end
		-- hold just short of full logo while the phone fetch is still in flight
		if FeedLive.busy and loadAnim >= LOAD_FRAMES then loadAnim = LOAD_FRAMES - 1 end
		drawLoading()
		if loadFrames <= 0 and not FeedLive.busy then
			stepIndex = 1
			-- log this walk
			local det = routeDetour()
			table.insert(detourLog, 1, {
				vibe = selected,
				place = (selected == "none") and "no detour" or ((det and det.name) or "?"),
				when = "Oct 10",
				time = "now",
			})
			persist()
			state = S_PATH
		end
		return
	end

	if state == S_COUNT then
		if pd.buttonJustPressed(pd.kButtonB) then
			clearCountdown()
			state = S_PATH
		end
		drawCountdown()
		return
	end

	if state == S_LOG then
		local visible = (210 - 28) // 36
		local maxScroll = math.max(0, #detourLog - visible)
		local ticks = pd.getCrankTicks(8)
		if ticks ~= 0 then
			logScroll += ticks
			if logScroll < 0 then logScroll = 0 end
			if logScroll > maxScroll then logScroll = maxScroll end
		end
		if pd.buttonJustPressed(pd.kButtonUp) then
			logScroll = math.max(0, logScroll - 1)
		elseif pd.buttonJustPressed(pd.kButtonDown) then
			logScroll = math.min(maxScroll, logScroll + 1)
		elseif pd.buttonJustPressed(pd.kButtonB) then
			state = S_HOME
		end
		drawDetourLog()
		return
	end

	if state == S_CAP_FOLDERS then
		local ticks = pd.getCrankTicks(8)
		if ticks ~= 0 or pd.buttonJustPressed(pd.kButtonLeft) or pd.buttonJustPressed(pd.kButtonRight) then
			capFolder = (capFolder == "free") and "task" or "free"
		end
		if pd.buttonJustPressed(pd.kButtonA) then
			capTileIdx = 1
			state = S_CAP_TILES
		elseif pd.buttonJustPressed(pd.kButtonB) then
			state = S_HOME
		end
		drawCapFolders()
		return
	end

	if state == S_CAP_TILES then
		local list = capturesOf(capFolder)
		local n = math.max(1, #list)
		local ticks = pd.getCrankTicks(8)
		-- 4 across: L/R ±1, U/D ±4
		if ticks ~= 0 then capTileIdx = ((capTileIdx - 1 + ticks) % n) + 1 end
		if pd.buttonJustPressed(pd.kButtonLeft) then
			capTileIdx = ((capTileIdx - 2) % n) + 1
		elseif pd.buttonJustPressed(pd.kButtonRight) then
			capTileIdx = (capTileIdx % n) + 1
		elseif pd.buttonJustPressed(pd.kButtonUp) then
			capTileIdx = ((capTileIdx - 5) % n) + 1
		elseif pd.buttonJustPressed(pd.kButtonDown) then
			capTileIdx = ((capTileIdx + 3) % n) + 1
		elseif pd.buttonJustPressed(pd.kButtonA) and #list > 0 then
			capViewIdx = capTileIdx
			state = S_CAP_VIEW
		elseif pd.buttonJustPressed(pd.kButtonB) then
			state = S_CAP_FOLDERS
		end
		drawCapTiles()
		return
	end

	if state == S_CAP_VIEW then
		local list = capturesOf(capFolder)
		local n = math.max(1, #list)
		local ticks = pd.getCrankTicks(8)
		if ticks ~= 0 then
			capViewIdx = ((capViewIdx - 1 + ticks) % n) + 1
		end
		if pd.buttonJustPressed(pd.kButtonLeft) then
			capViewIdx = ((capViewIdx - 2) % n) + 1
		elseif pd.buttonJustPressed(pd.kButtonRight) then
			capViewIdx = (capViewIdx % n) + 1
		elseif pd.buttonJustPressed(pd.kButtonB) then
			capTileIdx = capViewIdx
			state = S_CAP_TILES
		end
		drawCapView()
		return
	end

	if state == S_PATH then
		if trayOpen then
			local ticks = pd.getCrankTicks(8)
			if ticks ~= 0 then trayIdx = ((trayIdx - 1 + ticks) % #TRAY) + 1 end
			if pd.buttonJustPressed(pd.kButtonUp) then trayIdx = ((trayIdx - 2) % #TRAY) + 1
			elseif pd.buttonJustPressed(pd.kButtonDown) then trayIdx = (trayIdx % #TRAY) + 1
			elseif pd.buttonJustPressed(pd.kButtonA) then
				local act = TRAY[trayIdx]
				if act == "Tasks" then
					trayOpen = false
					tasksFromHome = false
					taskIdx = 1
					playSfx(taskSfx)
					state = S_TASKS
				else
					startCountdown(act)
				end
			elseif pd.buttonJustPressed(pd.kButtonB) then
				trayOpen = false
			end
		else
			local ticks = pd.getCrankTicks(8)
			if ticks ~= 0 then wrapStep(ticks) end
			if pd.buttonJustPressed(pd.kButtonA) then
				trayIdx = 1
				trayOpen = true
			elseif pd.buttonJustPressed(pd.kButtonB) then
				state = S_HOME
			end
		end
		drawPathScreen()
		return
	end

	if state == S_ASK then
		local ticks = pd.getCrankTicks(8)
		if ticks ~= 0 then
			local nL = #AskDemo.layers; if nL < 1 then nL = 1 end
			askLayerIndex = ((askLayerIndex - 1 + ticks) % nL) + 1
		end
		if pd.buttonJustPressed(pd.kButtonLeft) then
			local nL = #AskDemo.layers; if nL < 1 then nL = 1 end
			askLayerIndex = ((askLayerIndex - 2) % nL) + 1
		elseif pd.buttonJustPressed(pd.kButtonRight) then
			local nL = #AskDemo.layers; if nL < 1 then nL = 1 end
			askLayerIndex = (askLayerIndex % nL) + 1
		elseif pd.buttonJustPressed(pd.kButtonA) then
			-- Ask = read card only; Neon snap is Path -> Capture
		elseif pd.buttonJustPressed(pd.kButtonB) then
			state = S_PATH
		end
		drawAsk()
		return
	end

	if state == S_TASKS then
		local ticks = pd.getCrankTicks(8)
		if ticks ~= 0 then taskIdx = ((taskIdx - 1 + ticks) % #DAILY) + 1 end
		if pd.buttonJustPressed(pd.kButtonUp) then taskIdx = ((taskIdx - 2) % #DAILY) + 1
		elseif pd.buttonJustPressed(pd.kButtonDown) then taskIdx = (taskIdx % #DAILY) + 1
		elseif pd.buttonJustPressed(pd.kButtonA) then
			local tdef = DAILY[taskIdx]
			if (tasks[tdef.id] or 0) < tdef.max then
				startCountdown("TaskBump", tdef.id)
			end
		elseif pd.buttonJustPressed(pd.kButtonB) then
			state = tasksFromHome and S_HOME or S_PATH
		end
		drawTasks()
		return
	end
end
