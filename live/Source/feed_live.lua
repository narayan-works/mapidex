-- feed_live.lua — live feed loading from the Android phone companion (mapidex-companion v0.3)
--
--   GET http://<phone-ip>:8080/feed.json  (see LIVE_FEED_URL in main.lua)
--   Check from the Mac first: curl http://<phone-ip>:8080/feed.json
--
-- Order on every Start -> vibe -> loading:
--   1. HTTP GET LIVE_FEED_URL via playdate.network.http (SDK/OS 2.7+)
--   2. if that fails: local drop file "live_feed.json" in the game's Data folder
--      (Simulator: ~/Developer/PlaydateSDK/Disk/Data/com.narayan.mapidex.live/live_feed.json)
--   3. if that fails: bundled feed.lua (baked)
-- Optional URL override without rebuilding: put a single line URL in
--   Data/com.narayan.mapidex.live/live_url.txt
--
-- Companion feed.json -> Feed mapping (only the phone's selected vibe is replaced;
-- other vibes keep their baked routes):
--   route.vibe / selected      -> Feed.categories[vibe]
--   route.place                -> .place  {name, lat, lng}
--   route.summary              -> .summary
--   route.path {w,h,pts}       -> .path
--   route.instructions         -> .instructions
--   route.detour_index         -> .detourIndex
--   route.via_step             -> .viaStep (999 = no detour)
--   office/home {name,lat,lng} -> Feed.office/home (names) + officeLL/homeLL
--   you_are_here               -> Feed.youAreHere {lat, lng, has_fix, updated_at}

local pd <const> = playdate

FeedLive = {
  enabled = true,
  timeoutMs = 60000, -- covers phone-side OSRM + Overpass build
  -- status for UI
  busy = false,
  source = "baked",   -- "live" | "file" | "baked"
  liveVibe = nil,     -- vibe the phone route was built for
  lastError = nil,
  lastFetchMs = nil,
  -- internals
  _conn = nil,
  _buf = nil,
  _startMs = nil,
  _done = nil,
  _url = nil,
  progress = nil,     -- short line while the phone builds
  _phase = nil,       -- "post" | "poll" | "wait"
  _reqDone = false,
  _wantVibe = nil,
  _buildId = nil,
  _nextPollMs = nil,
}

-- keep pristine baked categories so a failed fetch restores them
local function deepcopy(t)
  if type(t) ~= "table" then return t end
  local o = {}
  for k, v in pairs(t) do o[k] = deepcopy(v) end
  return o
end
local BAKED <const> = deepcopy(Feed)

local function restoreBaked()
  for k in pairs(Feed) do Feed[k] = nil end
  for k, v in pairs(deepcopy(BAKED)) do Feed[k] = v end
end

-- "http://10.0.0.5:8080/feed.json" -> host, port, path, ssl
local function parseUrl(url)
  if type(url) ~= "string" then return nil end
  url = url:gsub("^%s+", ""):gsub("%s+$", "")
  local scheme, rest = url:match("^(https?)://(.+)$")
  if not scheme then scheme, rest = "http", url end
  local hostport, path = rest:match("^([^/]+)(/.*)$")
  if not hostport then hostport, path = rest, "/feed.json" end
  local host, port = hostport:match("^(.-):(%d+)$")
  if not host then host = hostport end
  local ssl = scheme == "https"
  port = tonumber(port) or (ssl and 443 or 80)
  return host, port, path, ssl
end

local function readUrlOverride(url)
  if pd.file.exists("live_url.txt") then
    local f = pd.file.open("live_url.txt", pd.file.kFileRead)
    if f then
      local line = f:readline()
      f:close()
      if line and line:match("%S") then return (line:gsub("%s+$", "")) end
    end
  end
  return url
end

-- Apply a decoded companion feed.json to Feed. Returns true or false, err.
function FeedLive.apply(data)
  if type(data) ~= "table" then return false, "bad json" end
  local r = data.route
  if type(r) ~= "table" then return false, "feed has no route" end
  if type(data.detour) == "table" and data.detour.live == false then
    return false, "phone: no route built yet"
  end
  local instr = r.instructions
  local path = r.path
  if type(instr) ~= "table" or #instr == 0 then return false, "route has no turns" end
  if type(path) ~= "table" or type(path.pts) ~= "table" or #path.pts < 2 then
    return false, "route has no path"
  end

  local vibe = r.vibe or data.selected or "play"
  if not BAKED.categories[vibe] then vibe = "play" end

  local pts = {}
  for i, p in ipairs(path.pts) do
    pts[i] = { tonumber(p[1]) or 0, tonumber(p[2]) or 0 }
  end
  local place = r.place or r.via
  local cat = {
    place = place and { name = place.name or "?", lat = place.lat, lng = place.lng }
      or { name = (vibe == "none") and "no detour" or "?" },
    summary = r.summary or "",
    detourIndex = tonumber(r.detour_index or r.detourIndex) or 1,
    viaStep = tonumber(r.via_step or r.viaStep) or 999,
    path = { w = tonumber(path.w) or 180, h = tonumber(path.h) or 100, pts = pts },
    instructions = instr,
    live = true,
  }

  restoreBaked()
  Feed.categories[vibe] = cat
  Feed.default = vibe
  if type(data.office) == "table" then
    Feed.office = data.office.name or Feed.office
    Feed.officeLL = { lat = data.office.lat, lng = data.office.lng }
  elseif type(data.office) == "string" then
    Feed.office = data.office
  end
  if type(data.home) == "table" then
    Feed.home = data.home.name or Feed.home
    Feed.homeLL = { lat = data.home.lat, lng = data.home.lng }
  elseif type(data.home) == "string" then
    Feed.home = data.home
  end
  Feed.youAreHere = data.you_are_here
  Feed.detourStatus = data.detour
  FeedLive.liveVibe = vibe
  return true
end

local function finish(ok, err, source)
  local conn = FeedLive._conn
  FeedLive._conn = nil
  if conn then pcall(function() conn:close() end) end
  FeedLive.busy = false
  FeedLive._phase = nil
  FeedLive.progress = nil
  FeedLive.lastFetchMs = pd.getCurrentTimeMilliseconds()
  if ok then
    FeedLive.source = source or "live"
    FeedLive.lastError = nil
  else
    -- fallback 2: local drop file
    local fileErr = nil
    if pd.file.exists("live_feed.json") then
      local okDec, data = pcall(json.decodeFile, "live_feed.json")
      if okDec and data then
        local applied, aerr = FeedLive.apply(data)
        if applied then
          FeedLive.source = "file"
          FeedLive.lastError = err
          if FeedLive._done then FeedLive._done(true) end
          return
        end
        fileErr = aerr
      end
    end
    -- fallback 3: baked
    restoreBaked()
    FeedLive.source = "baked"
    FeedLive.liveVibe = nil
    FeedLive.lastError = err or fileErr or "live fetch failed"
  end
  print("[FeedLive] source=" .. FeedLive.source .. (FeedLive.lastError and (" err=" .. FeedLive.lastError) or ""))
  if FeedLive._done then FeedLive._done(FeedLive.source ~= "baked") end
end

-- ---------------------------------------------------------------------------
-- Requests. One http connection per request; callbacks only collect bytes and
-- flag completion — the next step (POST -> poll GET -> ...) runs from update().
--
-- With a vibe (Playdate vibe pick is the trigger):
--   1. POST /route  vibe=<vibe>          -> {ok, status:"building", build_id}
--   2. GET  /feed.json every ~0.8 s until detour.build_id >= ours and
--      detour.status ~= "building"      -> apply
--   Old companion without /route (404): plain GET once.
-- Without a vibe: plain GET /feed.json once (old behaviour).
-- ---------------------------------------------------------------------------

local POLL_MS <const> = 800

local function openRequest(method, path, body)
  local host, port, ssl = FeedLive._host, FeedLive._port, FeedLive._ssl
  local okNew, conn = pcall(pd.network.http.new, host, port, ssl, "Build and load your live detour route from the Mapidex phone companion")
  if not okNew or not conn then
    return false, okNew and "network access denied" or ("http.new: " .. tostring(conn))
  end
  FeedLive._conn = conn
  FeedLive._buf = {}
  FeedLive._reqDone = false
  conn:setConnectTimeout(4)
  conn:setReadTimeout(1)
  conn:setRequestCallback(function()
    if FeedLive._conn ~= conn then return end
    local n = conn:getBytesAvailable()
    if n and n > 0 then
      local chunk = conn:read(n)
      if chunk then FeedLive._buf[#FeedLive._buf + 1] = chunk end
    end
  end)
  conn:setRequestCompleteCallback(function()
    if FeedLive._conn ~= conn then return end
    FeedLive._reqDone = true
  end)
  conn:setConnectionClosedCallback(function()
    if FeedLive._conn ~= conn then return end
    FeedLive._reqDone = true
  end)
  local ok, err
  if method == "POST" then
    ok, err = conn:post(path, { Accept = "application/json", ["Content-Type"] = "application/x-www-form-urlencoded" }, body or "")
  else
    ok, err = conn:get(path, { Accept = "application/json" })
  end
  if not ok then return false, method .. " failed: " .. tostring(err) end
  return true
end

-- drain + close current connection; returns status, body
local function collect()
  local conn = FeedLive._conn
  if not conn then return nil, "" end
  local n = conn:getBytesAvailable()
  while n and n > 0 do
    local chunk = conn:read(n)
    if not chunk or #chunk == 0 then break end
    FeedLive._buf[#FeedLive._buf + 1] = chunk
    n = conn:getBytesAvailable()
  end
  local status = conn:getResponseStatus()
  local cerr = conn:getError()
  FeedLive._conn = nil
  pcall(function() conn:close() end)
  local body = table.concat(FeedLive._buf)
  return status, body, cerr
end

local function feedPath()
  return FeedLive._path or "/feed.json"
end

local function routePathFor(vibe)
  local base = (FeedLive._path or "/feed.json"):gsub("/[^/]*$", "")
  return base .. "/route?vibe=" .. vibe
end

local function startPoll()
  FeedLive._phase = "poll"
  local ok, err = openRequest("GET", feedPath())
  if not ok then finish(false, err) end
end

local function onPostDone()
  local status, body = collect()
  if status == 404 then
    -- companion predates POST /route: just read whatever is there
    FeedLive._wantVibe = nil
    FeedLive._buildId = nil
    FeedLive.progress = "phone app has no /route (update companion)"
  elseif status and status ~= 200 then
    local okDec, data = pcall(json.decode, body or "")
    local msg = okDec and type(data) == "table" and data.error or nil
    finish(false, "phone /route: " .. (msg or ("HTTP " .. tostring(status))))
    return
  else
    local okDec, data = pcall(json.decode, body or "")
    if okDec and type(data) == "table" then
      FeedLive._buildId = tonumber(data.build_id)
    end
    FeedLive.progress = "phone building " .. tostring(FeedLive._wantVibe) .. " route..."
  end
  FeedLive._phase = "wait"
  FeedLive._nextPollMs = pd.getCurrentTimeMilliseconds() -- poll right away
end

local function onPollDone()
  local status, body, cerr = collect()
  if status and status ~= 200 then
    finish(false, "HTTP " .. tostring(status))
    return
  end
  if #body == 0 then
    finish(false, cerr or "empty response")
    return
  end
  local okDec, data = pcall(json.decode, body)
  if not okDec or type(data) ~= "table" then
    finish(false, "bad json from phone")
    return
  end
  local det = type(data.detour) == "table" and data.detour or {}
  if FeedLive._wantVibe then
    local bid = tonumber(det.build_id) or 0
    local mine = FeedLive._buildId
    local pending = det.status == "building" or (mine and bid < mine)
    if pending then
      if type(det.message) == "string" and det.message ~= "" then
        FeedLive.progress = "phone: " .. det.message
      end
      FeedLive._phase = "wait"
      FeedLive._nextPollMs = pd.getCurrentTimeMilliseconds() + POLL_MS
      return
    end
    if det.status == "error" then
      local r = data.route
      local rv = type(r) == "table" and r.vibe or nil
      if rv ~= FeedLive._wantVibe then
        finish(false, "phone: " .. tostring(det.message or "route failed"))
        return
      end
    end
  end
  local applied, aerr = FeedLive.apply(data)
  finish(applied, aerr, "live")
end

-- Start a fetch. Must be called from playdate.update() context (permission dialog yields).
-- vibe (optional): ask the phone to build that detour first (POST /route), then poll the feed.
-- done(ok) is called when finished (ok=false means baked/fallback in use).
function FeedLive.start(url, done, vibe)
  FeedLive._done = done
  if FeedLive.busy then return end
  if not FeedLive.enabled then
    finish(false, "live disabled")
    return
  end
  url = readUrlOverride(url)
  FeedLive._url = url
  local host, port, path, ssl = parseUrl(url)
  if not host or host == "" then
    finish(false, "bad LIVE_FEED_URL")
    return
  end
  if not (pd.network and pd.network.http) then
    finish(false, "no network API (OS < 2.7)")
    return
  end
  FeedLive._host, FeedLive._port, FeedLive._path, FeedLive._ssl = host, port, path, ssl
  FeedLive.busy = true
  FeedLive._startMs = pd.getCurrentTimeMilliseconds()
  FeedLive._wantVibe = vibe
  FeedLive._buildId = nil
  FeedLive.progress = nil

  local ok, err
  if vibe then
    FeedLive._phase = "post"
    FeedLive.progress = "asking phone for " .. vibe .. " route..."
    ok, err = openRequest("POST", routePathFor(vibe), "vibe=" .. vibe)
  else
    FeedLive._phase = "poll"
    ok, err = openRequest("GET", path)
  end
  if not ok then finish(false, err) end
end

-- Call every frame from playdate.update(); drives POST -> poll and enforces the timeout.
function FeedLive.update()
  if not FeedLive.busy then return end
  local conn = FeedLive._conn
  if conn and not FeedLive._reqDone then
    local e = conn:getError()
    if e and e ~= "" then
      finish(false, e)
      return
    end
  end
  if FeedLive._reqDone then
    FeedLive._reqDone = false
    if FeedLive._phase == "post" then onPostDone() else onPollDone() end
  elseif FeedLive._phase == "wait" and pd.getCurrentTimeMilliseconds() >= (FeedLive._nextPollMs or 0) then
    startPoll()
  end
  if FeedLive.busy and pd.getCurrentTimeMilliseconds() - (FeedLive._startMs or 0) > FeedLive.timeoutMs then
    finish(false, FeedLive._wantVibe and "phone route build timed out" or "phone not reachable (timeout)")
  end
end

-- One short line for the Path / loading screens.
function FeedLive.statusLine(selectedVibe)
  if FeedLive.busy then return FeedLive.progress or "fetching phone feed..." end
  if FeedLive.source == "live" or FeedLive.source == "file" then
    if selectedVibe and FeedLive.liveVibe and selectedVibe ~= FeedLive.liveVibe then
      return "phone route is " .. FeedLive.liveVibe .. "; this is baked"
    end
    return FeedLive.source == "live" and "live from phone" or "live_feed.json (file)"
  end
  return "baked: " .. (FeedLive.lastError or "offline")
end

-- Back-compat with the old stub call (now a no-op; fetch happens on loading screen).
function FeedLive.load(url)
  return false
end
