-- AskDemo + AskLive: poll companion GET /ask.json after Capture
-- Schema (companion v0.5): status idle|thinking|ok|error, title, blurb, layers[{name,text}], gaze{x,y,w,h}

AskDemo = {
	title = "Golden Gate Bridge",
	kind = "place",
	question = "whats that?",
	image = "images/entry_vert",
	snapImage = nil, -- Data/snaps/... when Capture lands
	gaze = nil,      -- {x,y[,w,h]} scene px; drawn on Ask card only
	layers = {
		{ tag = "location", text = "SF-Marin / Golden Gate Strait" },
		{ tag = "fact", text = "1937 Art Deco suspension bridge" },
		{ tag = "fact", text = "Designed by Joseph Strauss" },
		{ tag = "hours", text = "Open 24h / sidewalk dawn-dusk" },
	},
}

local pd <const> = playdate

AskLive = {
	timeoutMs = 20000,
	pollEveryMs = 1500,
	maxPolls = 12,
	busy = false,
	lastStatus = nil,
	_conn = nil, _buf = nil, _startMs = nil, _polls = nil, _nextPollMs = nil, _done = nil,
}

local function finish(ok, data, err)
	local conn = AskLive._conn
	AskLive._conn = nil
	if conn then pcall(function() conn:close() end) end
	AskLive.busy = false
	AskLive.lastStatus = ok and ("ask " .. tostring(data and data.status) .. " " .. tostring(data and data.title))
		or ("ask failed: " .. tostring(err))
	print("[AskLive] " .. AskLive.lastStatus)
	if AskLive._done then AskLive._done(ok, data, err) end
end

-- Apply companion /ask.json into AskDemo. Returns true if UI-ready (ok/error with title).
function AskDemo.apply(data)
	if type(data) ~= "table" then return false end
	local st = data.status
	if st == "thinking" then
		if type(data.title) == "string" and data.title ~= "" then
			AskDemo.title = data.title
		else
			AskDemo.title = "looking…"
		end
		AskDemo.kind = "ask"
		return false
	end
	if st ~= "ok" and st ~= "error" then return false end
	AskDemo.title = (type(data.title) == "string" and data.title ~= "" and data.title) or "Can't tell yet"
	AskDemo.kind = data.kind or "ask"
	AskDemo.question = data.question
	if type(data.gaze) == "table" then
		AskDemo.gaze = { x = data.gaze.x, y = data.gaze.y, w = data.gaze.w, h = data.gaze.h }
	end
	local layers = {}
	if type(data.blurb) == "string" and data.blurb ~= "" then
		layers[#layers + 1] = { tag = "about", text = data.blurb }
	end
	if type(data.layers) == "table" then
		for _, L in ipairs(data.layers) do
			if type(L) == "table" and type(L.text) == "string" and L.text ~= "" then
				local tag = L.name or L.tag or "fact"
				-- skip duplicate "about"/"what" if we already pushed blurb/title
				if not (tag == "about" and #layers > 0 and layers[1].tag == "about" and layers[1].text == L.text) then
					if not (tag == "what" and L.text == AskDemo.title) then
						layers[#layers + 1] = { tag = tag, text = L.text }
					end
				end
			end
		end
	end
	if #layers == 0 then
		layers[1] = { tag = "about", text = (type(data.error) == "string" and data.error) or "…" }
	end
	AskDemo.layers = layers
	return true
end

local function onComplete()
	local conn = AskLive._conn
	if not conn then return end
	local n = conn:getBytesAvailable()
	while n and n > 0 do
		local chunk = conn:read(n)
		if not chunk or #chunk == 0 then break end
		AskLive._buf[#AskLive._buf + 1] = chunk
		n = conn:getBytesAvailable()
	end
	local body = table.concat(AskLive._buf)
	AskLive._conn = nil
	pcall(function() conn:close() end)
	local okDec, data = pcall(json.decode, body)
	if not okDec or type(data) ~= "table" then
		-- retry on empty/bad while still polling
		AskLive._polls = (AskLive._polls or 0) + 1
		if AskLive._polls < AskLive.maxPolls then
			AskLive._nextPollMs = pd.getCurrentTimeMilliseconds() + AskLive.pollEveryMs
			AskLive.lastStatus = "ask retry (bad json)"
			return
		end
		finish(false, nil, (#body == 0) and "empty ask.json" or "bad json")
		return
	end
	local ready = AskDemo.apply(data)
	if ready then
		finish(true, data)
		return
	end
	-- thinking / idle — keep polling
	AskLive._polls = (AskLive._polls or 0) + 1
	if AskLive._polls >= AskLive.maxPolls then
		finish(true, data) -- last apply already set "looking…" or idle
		return
	end
	AskLive._nextPollMs = pd.getCurrentTimeMilliseconds() + AskLive.pollEveryMs
	AskLive.lastStatus = "ask " .. tostring(data.status) .. " (poll " .. AskLive._polls .. ")"
end

local function doGet()
	local ip = PHONE_IP
	if type(ip) ~= "string" or ip == "" then finish(false, nil, "no PHONE_IP") return end
	if not (pd.network and pd.network.http) then finish(false, nil, "no network API") return end
	AskLive._buf = {}
	local okNew, conn = pcall(pd.network.http.new, ip, 8080, false, "Load Ask answer from the Mapidex phone companion")
	if not okNew or not conn then
		finish(false, nil, okNew and "network access denied" or ("http.new: " .. tostring(conn)))
		return
	end
	AskLive._conn = conn
	conn:setConnectTimeout(3)
	conn:setReadTimeout(5)
	conn:setRequestCallback(function()
		if AskLive._conn ~= conn then return end
		local n = conn:getBytesAvailable()
		if n and n > 0 then
			local chunk = conn:read(n)
			if chunk then AskLive._buf[#AskLive._buf + 1] = chunk end
		end
	end)
	conn:setRequestCompleteCallback(function() if AskLive._conn == conn then onComplete() end end)
	conn:setConnectionClosedCallback(function() if AskLive._conn == conn then onComplete() end end)
	local okGet, err = conn:get("/ask.json", { Accept = "application/json" })
	if not okGet then
		AskLive._polls = (AskLive._polls or 0) + 1
		if AskLive._polls < AskLive.maxPolls then
			pcall(function() conn:close() end)
			AskLive._conn = nil
			AskLive._nextPollMs = pd.getCurrentTimeMilliseconds() + AskLive.pollEveryMs
			AskLive.lastStatus = "ask GET retry: " .. tostring(err)
			return
		end
		finish(false, nil, "GET failed: " .. tostring(err))
	end
end

-- Start polling companion /ask.json. done(ok, data, err) optional.
function AskLive.start(done)
	AskLive._done = done
	if AskLive.busy then return end
	AskLive.busy = true
	AskLive._polls = 0
	AskLive._startMs = pd.getCurrentTimeMilliseconds()
	AskLive._nextPollMs = nil
	AskDemo.title = "looking…"
	AskDemo.layers = { { tag = "about", text = "waiting on phone Ask…" } }
	AskLive.lastStatus = "ask polling…"
	-- short delay so Mac→phone upload can land before first poll
	AskLive._nextPollMs = pd.getCurrentTimeMilliseconds() + 800
end

-- Stop polling (B cancel on Ask loading). Does not invoke _done.
function AskLive.cancel()
	local conn = AskLive._conn
	AskLive._conn = nil
	AskLive._done = nil
	AskLive._nextPollMs = nil
	if conn then pcall(function() conn:close() end) end
	AskLive.busy = false
	AskLive.lastStatus = "ask cancelled"
	print("[AskLive] " .. AskLive.lastStatus)
end

function AskLive.update()
	if not AskLive.busy then return end
	if pd.getCurrentTimeMilliseconds() - (AskLive._startMs or 0) > AskLive.timeoutMs then
		if AskLive._conn then pcall(function() AskLive._conn:close() end); AskLive._conn = nil end
		finish(false, nil, "ask.json timeout")
		return
	end
	if AskLive._conn then return end -- in-flight
	if AskLive._nextPollMs and pd.getCurrentTimeMilliseconds() >= AskLive._nextPollMs then
		AskLive._nextPollMs = nil
		doGet()
	end
end
