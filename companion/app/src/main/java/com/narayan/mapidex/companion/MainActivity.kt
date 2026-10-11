package com.narayan.mapidex.companion

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * v0.5.2 companion — Playdate-vibe (1-bit + Playdate yellow accent) pocket engine.
 * Tabs: Home · Captures · Ask log · Detours · Daily. Server auto-starts on open (no Start tap);
 * MapidexService keeps it alive in the background. Endpoints (FeedServer) unchanged.
 */
class MainActivity : ComponentActivity() {

    private enum class Tab(val label: String) { HOME("Home"), CAPTURES("Captures"), ASKS("Ask log"), DETOURS("Detours"), DAILY("Daily") }

    private lateinit var locationManager: LocationManager

    private val running get() = MapidexService.running
    private var ip by mutableStateOf("…")
    private var lat by mutableStateOf(0.0)
    private var lng by mutableStateOf(0.0)
    private var updatedAt by mutableStateOf("no fix yet")
    private var hasLocation by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var work by mutableStateOf(Places.get(Places.WORK))
    private var home by mutableStateOf(Places.get(Places.HOME))
    /** True once the first-run permission prompts are done and the server has been started once. */
    private var bootDone = false
    // Route debug (hidden — Playdate should own route building; kept until POST /route exists)
    private var showRouteDebug by mutableStateOf(false)
    private var showConnDebug by mutableStateOf(false)
    private var vibe by mutableStateOf(FeedState.selected)
    private var building by mutableStateOf(false)
    private var routeStatus by mutableStateOf<String?>(null)
    private var routeError by mutableStateOf<String?>(null)
    private var routeSummary by mutableStateOf<String?>(null)
    private var routeVia by mutableStateOf<String?>(null)
    private var routeSteps by mutableStateOf<List<String>>(emptyList())

    private val locationListener = LocationListener { onLocation(it) }

    /** Location first (so the service can run as a location FGS), then notifications, then the server. */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        hasLocation = checkLocationPermission()
        if (hasLocation) startLocationUpdates()
        if (!bootDone) startServer()
    }

    /** Android 13+: ask for notification permission, then start the service either way. */
    private val notifThenStart = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> startServerNow() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        History.init(this)
        Places.load(this)
        work = Places.get(Places.WORK)
        home = Places.get(Places.HOME)
        ip = lanIp() ?: "no Wi‑Fi IP"
        hasLocation = checkLocationPermission()

        // Auto-start: no Start tap. Permission prompts (first run only) chain into the server start.
        if (hasLocation) { startLocationUpdates(); startServer() } else requestLocation()

        setContent { PdTheme { Surface(Modifier.fillMaxSize(), color = Pd.Paper) { App() } } }
    }

    // ================================================================== shell: tabs + overlays

    @Composable
    private fun App() {
        var tabName by rememberSaveable { mutableStateOf(Tab.HOME.name) }
        val tab = Tab.valueOf(tabName)
        var openCapture by remember { mutableStateOf<CapItem?>(null) }
        var openAsk by remember { mutableStateOf<JSONObject?>(null) }

        LaunchedEffect(Unit) { while (true) { lanIp()?.let { if (it != ip) ip = it }; delay(5000) } }

        BackHandler(enabled = openCapture != null || openAsk != null || tab != Tab.HOME) {
            when {
                openCapture != null -> openCapture = null
                openAsk != null -> openAsk = null
                else -> tabName = Tab.HOME.name
            }
        }

        Box(Modifier.fillMaxSize().systemBarsPadding()) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (tab) {
                        Tab.HOME -> Home(onOpenAsks = { tabName = Tab.ASKS.name })
                        Tab.CAPTURES -> CapturesScreen(onOpen = { openCapture = it })
                        Tab.ASKS -> AskLogScreen(onOpen = { openAsk = it })
                        Tab.DETOURS -> DetourLogScreen()
                        Tab.DAILY -> DailyDetoursScreen()
                    }
                }
                NavBar(tab) { tabName = it.name }
            }
            openCapture?.let { c -> CaptureDetail(c) { openCapture = null } }
            openAsk?.let { a -> AskDetail(a) { openAsk = null } }
        }
    }

    /** Bottom bar: 5 pixel tabs, selected = Playdate-yellow fill (menu highlight). */
    @Composable
    private fun NavBar(tab: Tab, onSelect: (Tab) -> Unit) {
        Row(
            Modifier.fillMaxWidth().background(Pd.Paper)
                .drawBehind { drawRect(Pd.Ink, Offset.Zero, androidx.compose.ui.geometry.Size(size.width, 2.dp.toPx())) }
                .padding(top = 2.dp)
        ) {
            Tab.entries.forEach { t ->
                val sel = t == tab
                Box(
                    Modifier.weight(1f).height(52.dp).background(if (sel) Pd.Accent else Pd.Paper).clickable { onSelect(t) },
                    contentAlignment = Alignment.Center
                ) { PxText(t.label, 10.sp, if (sel) Pd.Ink else Pd.Quiet, bold = sel) }
            }
        }
    }

    // ================================================================== Home

    @Composable
    private fun Home(onOpenAsks: () -> Unit) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 28.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Playdate "Menu"-size title
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(10.dp).background(Pd.Accent).border(2.dp, Pd.Ink))
                PxText("Mapidex", 16.sp, bold = true)
                PxText("companion", 11.sp, Pd.Quiet, modifier = Modifier.weight(1f))
                PxText("v${BuildConfig.VERSION_NAME}", 9.sp, Pd.Quiet)
            }
            Spacer(Modifier.height(10.dp))

            ServerCard()
            LastAskCard(onOpenLog = onOpenAsks)
            NeonCard()
            PlacesCard()

            // Quiet footer: Nano note + debug toggles on one line
            Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                QuietText("Nano runs only while this app is on screen.", 10.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    PdLink(if (showConnDebug) "− connection" else "+ connection") { showConnDebug = !showConnDebug }
                    PdLink(if (showRouteDebug) "− route debug" else "+ route debug") { showRouteDebug = !showRouteDebug }
                }
            }
            if (showConnDebug) ConnectionDebug()
            if (showRouteDebug) PdCard("Route debug") { RouteSection() }
        }
    }

    @Composable
    private fun ServerCard() {
        PdCard("Server", trailing = { PxText(if (running) "■ on" else "□ off", 10.sp) }) {
            MonoText("$ip:${FeedServer.PORT}", 24.sp, bold = true, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.weight(1f)) { QuietText(if (running) "serving feed" else "starting…", 11.sp) }
                PdLink("refresh ip") { ip = lanIp() ?: "no Wi‑Fi IP" }
                PdLink(if (running) "stop" else "start") { if (running) stopServer() else startServerNow() }
            }
            (error ?: MapidexService.error)?.let { MonoText("! $it", 12.sp, bold = true) }
        }
    }

    @Composable
    private fun PlacesCard() {
        PdCard("Places") {
            PlaceRow("Work", work) { work = it }
            Hairline()
            PlaceRow("Home", home) { home = it }
            QuietText(if (hasLocation) "you are here %.5f, %.5f".format(lat, lng) else "location off", 10.sp)
            if (!hasLocation) PdButton("Grant location", { requestLocation() }, Modifier.fillMaxWidth())
        }
    }

    /** Shown only when toggled from the quiet footer: IP · self /health check · Nano · Gemini. */
    @Composable
    private fun ConnectionDebug() {
        var health by remember { mutableStateOf("—") }
        LaunchedEffect(Unit) {
            while (true) {
                health = withContext(Dispatchers.IO) {
                    runCatching {
                        val c = java.net.URL("http://127.0.0.1:${FeedServer.PORT}/health").openConnection() as java.net.HttpURLConnection
                        c.connectTimeout = 1500; c.readTimeout = 1500
                        c.inputStream.bufferedReader().use { it.readText() }.let { if (JSONObject(it).optBoolean("ok")) "ok · ${c.responseCode}" else "bad" }
                    }.getOrElse { "down (${it.javaClass.simpleName})" }
                }
                delay(3000)
            }
        }
        PdCard("Connection") {
            KV("ip", "$ip:${FeedServer.PORT}")
            KV("server", if (running) "on" else "off")
            KV("/health", health)
            KV("nano", AskEngine.nanoStatus)
            KV("gemini", if (GeminiCloud.hasKey) "key set · ${GeminiCloud.model}" else "no key")
            KV("gps", if (FeedState.hasFix) "%.5f, %.5f".format(FeedState.lat, FeedState.lng) else "no fix")
            QuietText("Playdate PHONE_IP must equal this ip · same Wi‑Fi", 10.sp)
        }
    }

    /** Neon glasses status from the last capture's meta (worn / battery). — until data exists. */
    @Composable
    private fun NeonCard() {
        var worn by remember { mutableStateOf<Boolean?>(null) }
        var battery by remember { mutableStateOf<String?>(null) }
        var lastAt by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) {
            while (true) {
                val c = CaptureStore.last
                worn = c?.worn
                lastAt = c?.receivedAt
                battery = withContext(Dispatchers.IO) { neonBattery() }
                delay(2000)
            }
        }
        PdCard("Neon") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    PxText("worn", 9.sp, Pd.Quiet)
                    MonoText(when (worn) { true -> "yes"; false -> "no"; null -> "—" }, 16.sp, bold = true)
                }
                Column(Modifier.weight(1f)) {
                    PxText("battery", 9.sp, Pd.Quiet)
                    MonoText(battery ?: "—", 16.sp, bold = true)
                }
                Column(Modifier.weight(1f)) {
                    PxText("last snap", 9.sp, Pd.Quiet)
                    MonoText(lastAt?.let { History.time(it) } ?: "—", 16.sp, bold = true, maxLines = 1)
                }
            }
        }
    }

    /** Battery if the Mac bridge ever includes it in capture meta (battery / battery_level / phone.battery). */
    private fun neonBattery(): String? = runCatching {
        val dir = CaptureStore.dirOrNull ?: File(filesDir, "captures")
        val meta = JSONObject(File(dir, "last_gaze.json").readText()).optJSONObject("meta") ?: return null
        val v = listOf("battery", "battery_level", "neon_battery").firstNotNullOfOrNull { k -> meta.opt(k)?.takeIf { it != JSONObject.NULL } }
            ?: meta.optJSONObject("phone")?.opt("battery")?.takeIf { it != JSONObject.NULL }
            ?: return null
        val d = v.toString().toDoubleOrNull() ?: return v.toString()
        "${(if (d <= 1.0) d * 100 else d).toInt()}%"
    }.getOrNull()

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun RouteSection() {
        MonoText("${work.name} → ${home.name}", 13.sp)
        PxText("vibe", 10.sp, Pd.Quiet)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Detour.VIBES.forEach { v ->
                PdButton(if (v == Detour.NONE) "no detour" else v, { vibe = v }, enabled = !building, inverted = vibe == v)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PdButton(if (building) "Building…" else "Build route", { buildRoute() }, Modifier.weight(1f), enabled = !building, inverted = true)
            if (building) PdSpinner(20.dp)
        }
        routeStatus?.let { QuietText(it, 12.sp) }
        routeError?.let { MonoText("! $it", 12.sp, bold = true) }
        routeSummary?.let { summary ->
            MonoText(summary, 13.sp, bold = true)
            routeVia?.let { QuietText(it, 12.sp) }
            routeSteps.forEachIndexed { i, s -> MonoText("${i + 1}. $s", 11.sp) }
        }
    }

    private fun buildRoute() {
        val v = vibe
        val w = Places.get(Places.WORK)
        val h = Places.get(Places.HOME)
        building = true
        routeError = null
        routeStatus = "Starting…"
        FeedState.setBuilding(v, "starting")
        Thread {
            try {
                val r = Detour.build(w, h, v) { msg ->
                    FeedState.setBuilding(v, msg)
                    runOnUiThread { routeStatus = msg }
                }
                FeedState.setRoute(v, r)
                val ins = r.getJSONArray("instructions")
                val steps = (0 until ins.length()).map { ins.getString(it) }
                val via = r.optJSONObject("via")
                runOnUiThread {
                    routeSummary = r.getString("summary")
                    routeVia = if (via != null) "via ${via.getString("name")} (${via.getString("tag")})\n%.6f, %.6f".format(via.getDouble("lat"), via.getDouble("lng"))
                        else "no detour"
                    routeSteps = steps
                    routeStatus = "Done · in /feed.json" + (if (r.isNull("note")) "" else " · ${r.getString("note")}")
                    building = false
                }
            } catch (e: Exception) {
                val msg = when (e) {
                    is Detour.DetourException -> e.message ?: "Route failed"
                    is java.net.UnknownHostException -> "No internet: ${e.message}"
                    else -> "Route failed: ${e.javaClass.simpleName}: ${e.message}"
                }
                FeedState.setError(msg)
                runOnUiThread {
                    routeError = msg + if (routeSummary != null) "\n(feed keeps last good route)" else ""
                    routeStatus = null
                    building = false
                }
            }
        }.start()
    }

    /** Work/Home row: Set here (current GPS) / Edit (rename · reset). Same Places persistence as before. */
    @Composable
    private fun PlaceRow(label: String, place: Place, onChange: (Place) -> Unit) {
        var editing by remember(place.key) { mutableStateOf(false) }
        var name by remember(place.key, place.name) { mutableStateOf(place.name) }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PxText(label, 11.sp, bold = true)
                        if (place.isDefault) PxText("default", 9.sp, Pd.Quiet)
                    }
                    MonoText(place.name, 15.sp, bold = true, maxLines = 1)
                    QuietText("%.5f, %.5f".format(place.lat, place.lng), 10.sp)
                }
                PdButton("Set", {
                    if (!FeedState.hasFix) error = "No GPS fix yet — wait for a location before setting $label."
                    else { error = null; onChange(Places.save(this@MainActivity, place.key, name, FeedState.lat, FeedState.lng)) }
                }, enabled = hasLocation, inverted = true)
                PdLink(if (editing) "done" else "edit") { editing = !editing }
            }
            if (editing) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { PxText("$label name", 10.sp) },
                    textStyle = TextStyle(fontFamily = Pd.Mono, fontSize = 15.sp, color = Pd.Ink),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PdButton("Save name", { onChange(Places.rename(this@MainActivity, place.key, name)) },
                        enabled = name.isNotBlank() && name != place.name, inverted = true)
                    PdButton("Reset", { onChange(Places.reset(this@MainActivity, place.key)) }, enabled = !place.isDefault)
                }
            }
        }
    }

    private fun startServer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifThenStart.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else startServerNow()
    }

    private fun startServerNow() {
        bootDone = true
        try {
            error = null
            MapidexService.error = null
            MapidexService.start(this)
            ip = lanIp() ?: "no Wi‑Fi IP"
        } catch (e: Exception) {
            error = "Could not start server: ${e.message}"
        }
    }

    private fun stopServer() {
        MapidexService.stop(this)
    }

    private fun checkLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestLocation() {
        permissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!checkLocationPermission()) return
        locationManager.removeUpdates(locationListener)
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) }
        providers.mapNotNull { locationManager.getLastKnownLocation(it) }
            .maxByOrNull { it.time }?.let { onLocation(it) }
        providers.forEach {
            locationManager.requestLocationUpdates(it, 2000L, 1f, locationListener, Looper.getMainLooper())
        }
    }

    private fun onLocation(loc: Location) {
        FeedState.update(loc.latitude, loc.longitude)
        lat = FeedState.lat
        lng = FeedState.lng
        updatedAt = FeedState.updatedAt
    }

    private fun lanIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()

    override fun onStart() {
        super.onStart()
        // Reopened after the notification's Stop (or the OS killed the service): bring the server back.
        if (bootDone && !MapidexService.running) startServerNow()
    }

    override fun onDestroy() {
        // Server keeps running in MapidexService; only stop the UI's own location listener.
        locationManager.removeUpdates(locationListener)
        super.onDestroy()
    }
}
