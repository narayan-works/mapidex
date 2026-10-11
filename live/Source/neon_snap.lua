-- neon_snap.lua — Capture -> Neon glasses snapshot (SIM ONLY for now)
--
--   GET http://127.0.0.1:8787/snap   (Neon monitor: /tmp/neon-web/server.py, start with /tmp/neon-web/start.sh)
--   Server grabs current Neon scene frame + gaze xy + worn, saves on the Mac to
--     mapidex/live/captures_local/snap-<ts>.png / _1bit.png / .json
--   and returns JSON {ok, path, gaze_x, gaze_y, worn, ts, when, thumb_w, thumb_h, bits(hex 1-bit thumb)}.
--   We rebuild the 1-bit thumb as a Playdate image, save it with datastore.writeImage
--   (Data/com.narayan.mapidex.live/snaps/<id>.pdi) and hand it back to main.lua.
-- Later: swap SNAP_URL for the phone companion's POST /capture.

local pd <const> = playdate
local gfx <const> = pd.graphics

NeonSnap = {
  url = { host = "127.0.0.1", port = 8787, path = "/snap" },
  timeoutMs = 8000,
  busy = false,
  lastStatus = nil, -- one-line status for UI / log
  _conn = nil, _buf = nil, _startMs = nil, _done = nil,
}

local function bitsToImage(hex, w, h)
  local img = gfx.image.new(w, h, gfx.kColorWhite)
  if not img then return nil end
  local rowBytes = w // 8
  gfx.pushContext(img)
  gfx.setColor(gfx.kColorBlack)
  for y = 0, h - 1 do
    local runStart = nil
    for bx = 0, rowBytes - 1 do
      local i = (y * rowBytes + bx) * 2 + 1
      local byte = tonumber(hex:sub(i, i + 1), 16) or 255
      for b = 7, 0, -1 do
        local x = bx * 8 + (7 - b)
        local white = (byte >> b) & 1 == 1
        if not white then
          if not runStart then runStart = x end
        elseif runStart then
          gfx.fillRect(runStart, y, x - runStart, 1); runStart = nil
        end
      end
    end
    if runStart then gfx.fillRect(runStart, y, w - runStart, 1) end
  end
  gfx.popContext()
  return img
end

local function finish(ok, data, err)
  local conn = NeonSnap._conn
  NeonSnap._conn = nil
  if conn then pcall(function() conn:close() end) end
  NeonSnap.busy = false
  NeonSnap.lastStatus = ok and ("snap saved " .. tostring(data and data.path)) or ("snap failed: " .. tostring(err))
  print("[NeonSnap] " .. NeonSnap.lastStatus)
  if NeonSnap._done then NeonSnap._done(ok, data, err) end
end

local function onComplete()
  local conn = NeonSnap._conn
  if not conn then return end
  local n = conn:getBytesAvailable()
  while n and n > 0 do
    local chunk = conn:read(n)
    if not chunk or #chunk == 0 then break end
    NeonSnap._buf[#NeonSnap._buf + 1] = chunk
    n = conn:getBytesAvailable()
  end
  local body = table.concat(NeonSnap._buf)
  local okDec, data = pcall(json.decode, body)
  if not okDec or type(data) ~= "table" then
    finish(false, nil, (#body == 0) and (conn:getError() or "empty response") or "bad json")
    return
  end
  if not data.ok then finish(false, data, data.error or ("HTTP " .. tostring(conn:getResponseStatus()))) return end
  -- 1-bit thumb -> Playdate image saved in Data/snaps/
  if type(data.bits) == "string" and data.thumb_w and data.thumb_h then
    local img = bitsToImage(data.bits, data.thumb_w, data.thumb_h)
    if img then
      local id = "snaps/snap-" .. string.format("%.0f", (tonumber(data.ts) or 0) * 1000)
      local okW = pcall(pd.datastore.writeImage, img, id)
      if okW then data.imagePath = id end
      data.img = img
    end
  end
  data.bits = nil
  finish(true, data)
end

-- done(ok, data, err). Call from update/timer context.
function NeonSnap.take(done)
  NeonSnap._done = done
  if NeonSnap.busy then return end
  if not (pd.network and pd.network.http) then finish(false, nil, "no network API") return end
  NeonSnap.busy = true
  NeonSnap._buf = {}
  NeonSnap._startMs = pd.getCurrentTimeMilliseconds()
  local u = NeonSnap.url
  local okNew, conn = pcall(pd.network.http.new, u.host, u.port, false, "Grab a snapshot from your Neon glasses")
  if not okNew or not conn then
    finish(false, nil, okNew and "network access denied" or ("http.new: " .. tostring(conn)))
    return
  end
  NeonSnap._conn = conn
  conn:setConnectTimeout(3)
  conn:setReadTimeout(5)
  conn:setRequestCallback(function()
    if NeonSnap._conn ~= conn then return end
    local n = conn:getBytesAvailable()
    if n and n > 0 then
      local chunk = conn:read(n)
      if chunk then NeonSnap._buf[#NeonSnap._buf + 1] = chunk end
    end
  end)
  conn:setRequestCompleteCallback(function() if NeonSnap._conn == conn then onComplete() end end)
  conn:setConnectionClosedCallback(function() if NeonSnap._conn == conn then onComplete() end end)
  local okGet, err = conn:get(u.path, { Accept = "application/json" })
  if not okGet then finish(false, nil, "GET failed: " .. tostring(err)) end
  NeonSnap.lastStatus = "snapping..."
end

function NeonSnap.update()
  if not NeonSnap.busy then return end
  if pd.getCurrentTimeMilliseconds() - (NeonSnap._startMs or 0) > NeonSnap.timeoutMs then
    finish(false, nil, "neon monitor not reachable (timeout)")
  end
end

-- cached image loader for capture entries (bundled path or saved snap)
local cache = {}
function NeonSnap.image(item, fallback)
  if item.snapImage then
    local c = cache[item.snapImage]
    if c == nil then
      c = pd.datastore.readImage(item.snapImage) or false
      cache[item.snapImage] = c
    end
    if c then return c end
  end
  local p = item.image or fallback
  local c = cache[p]
  if c == nil then c = gfx.image.new(p) or false; cache[p] = c end
  return c or nil
end
function NeonSnap.remember(path, img) cache[path] = img end
