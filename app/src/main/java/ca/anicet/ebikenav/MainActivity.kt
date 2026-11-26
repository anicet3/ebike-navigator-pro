package ca.anicet.ebikenav

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    companion object {
        /** Carte « plan » gratuite, sans clé (OpenFreeMap, données OpenStreetMap). */
        private const val STREET_STYLE = "https://tiles.openfreemap.org/styles/liberty"

        /** Vue satellite (imagerie Esri) + routes et noms par-dessus. */
        private const val SATELLITE_STYLE = """
{
  "version": 8,
  "sources": {
    "imagery": {
      "type": "raster",
      "tiles": ["https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"],
      "tileSize": 256,
      "maxzoom": 19,
      "attribution": "Imagerie © Esri, Maxar, Earthstar Geographics"
    },
    "roads": {
      "type": "raster",
      "tiles": ["https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Transportation/MapServer/tile/{z}/{y}/{x}"],
      "tileSize": 256,
      "maxzoom": 19
    },
    "labels": {
      "type": "raster",
      "tiles": ["https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/{z}/{y}/{x}"],
      "tileSize": 256,
      "maxzoom": 19
    }
  },
  "layers": [
    { "id": "imagery", "type": "raster", "source": "imagery" },
    { "id": "roads", "type": "raster", "source": "roads", "paint": { "raster-opacity": 0.8 } },
    { "id": "labels", "type": "raster", "source": "labels" }
  ]
}
"""

        private const val SRC_TODO = "route-todo"
        private const val SRC_DONE = "route-done"
        private const val SRC_DEST = "dest-src"
        private const val SRC_POI = "poi-src"
        private const val SRC_REPORTS = "reports-src"
        private const val LYR_DONE = "route-done-line"
        private const val LYR_CASING = "route-casing"
        private const val LYR_TODO = "route-line"
        private const val LYR_DEST = "dest-point"
        private const val LYR_POI = "poi-icons"
        private const val LYR_REPORTS = "report-icons"

        /** Après un geste sur la carte, retour automatique au suivi au bout de 8 s. */
        private const val AUTO_RECENTER_MS = 8_000L

        private val MONTREAL = GeoPoint(45.5017, -73.5673)
    }

    // --- Vues ---
    private lateinit var mapView: MapView
    private lateinit var searchCard: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var navBanner: LinearLayout
    private lateinit var navArrow: TextView
    private lateinit var navDistance: TextView
    private lateinit var navInstruction: TextView
    private lateinit var navPoi: TextView
    private lateinit var btnLayers: MaterialButton
    private lateinit var btnRecenter: MaterialButton
    private lateinit var btnReport: MaterialButton
    private lateinit var btnVoice: MaterialButton
    private lateinit var previewPanel: LinearLayout
    private lateinit var routeTitle: TextView
    private lateinit var routeSummary: TextView
    private lateinit var routeBattery: TextView
    private lateinit var modeGroup: MaterialButtonToggleGroup
    private lateinit var navBottom: LinearLayout
    private lateinit var navSpeed: TextView
    private lateinit var navRemaining: TextView
    private lateinit var loading: ProgressBar

    // --- État ---
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var satellite = false
    private lateinit var fused: FusedLocationProviderClient
    private var lastLocation: Location? = null
    private var firstFix = true
    private var destination: Place? = null
    private var previewRoute: Route? = null
    private var previewPoiJob: Job? = null
    private var mode = RouteMode.BALANCED
    private var navActive = false
    private var drawnRouteVersion = -1

    /** true = la carte suit le vélo ; false = l'utilisateur regarde ailleurs. */
    private var following = true
    private var lastGestureAt = 0L

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasLocationPermission()) {
                style?.let { enableLocationComponent(it) }
                startActivityLocation()
            } else {
                toast("La localisation est nécessaire pour vous guider.")
            }
        }

    /** GPS de l'écran (hors guidage). Pendant le guidage, c'est le service qui fournit la position. */
    private val activityLocationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            lastLocation = loc
            if (navActive) return
            val lc = map?.locationComponent
            if (lc != null && lc.isLocationComponentActivated) lc.forceLocationUpdate(loc)
            if (previewRoute != null) return
            if (firstFix) {
                firstFix = false
                following = true
            }
            maybeAutoRecenter()
            if (following) browseCamera(loc, animate = true)
        }
    }

    // =====================================================================
    // Cycle de vie
    // =====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.mapView)
        searchCard = findViewById(R.id.searchCard)
        searchInput = findViewById(R.id.searchInput)
        navBanner = findViewById(R.id.navBanner)
        navArrow = findViewById(R.id.navArrow)
        navDistance = findViewById(R.id.navDistance)
        navInstruction = findViewById(R.id.navInstruction)
        navPoi = findViewById(R.id.navPoi)
        btnLayers = findViewById(R.id.btnLayers)
        btnRecenter = findViewById(R.id.btnRecenter)
        btnReport = findViewById(R.id.btnReport)
        btnVoice = findViewById(R.id.btnVoice)
        previewPanel = findViewById(R.id.previewPanel)
        routeTitle = findViewById(R.id.routeTitle)
        routeSummary = findViewById(R.id.routeSummary)
        routeBattery = findViewById(R.id.routeBattery)
        modeGroup = findViewById(R.id.modeGroup)
        navBottom = findViewById(R.id.navBottom)
        navSpeed = findViewById(R.id.navSpeed)
        navRemaining = findViewById(R.id.navRemaining)
        loading = findViewById(R.id.loading)

        fused = LocationServices.getFusedLocationProviderClient(this)

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { m ->
            map = m
            setupMap(m)
        }

        setupButtons()
        updateVoiceButton()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                NavState.state.collect { render(it) }
            }
        }

        if (!hasLocationPermission()) requestPermissions()
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        startActivityLocation()
    }

    override fun onPause() {
        fused.removeLocationUpdates(activityLocationCallback)
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        mapView.onStop()
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        mapView.onDestroy()
        super.onDestroy()
    }

    // =====================================================================
    // Carte et couches
    // =====================================================================

    private fun setupMap(m: MapLibreMap) {
        m.uiSettings.setCompassEnabled(true)
        val d = resources.displayMetrics.density
        m.uiSettings.setCompassMargins(0, (200 * d).toInt(), (16 * d).toInt(), 0)
        m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(MONTREAL.lat, MONTREAL.lon), 12.0))

        // Appui long sur la carte = choisir ce point comme destination
        m.addOnMapLongClickListener { p ->
            if (!navActive) {
                setDestination(
                    Place(
                        "Point choisi sur la carte",
                        String.format(java.util.Locale.CANADA_FRENCH, "%.5f, %.5f", p.latitude, p.longitude),
                        GeoPoint(p.latitude, p.longitude)
                    )
                )
            }
            true
        }
        // Le doigt déplace la carte → on arrête de suivre (retour auto après 8 s en guidage)
        m.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                following = false
                lastGestureAt = SystemClock.elapsedRealtime()
                btnRecenter.visibility = View.VISIBLE
            }
        }
        loadStyle()
    }

    private fun loadStyle() {
        val m = map ?: return
        val builder = if (satellite) Style.Builder().fromJson(SATELLITE_STYLE) else Style.Builder().fromUri(STREET_STYLE)
        style = null
        m.setStyle(builder) { s ->
            style = s
            addIcons(s)
            addLayers(s)
            redrawCurrentRoute()
            drawReports()
            enableLocationComponent(s)
        }
    }

    /** Dessine chaque symbole (🚦, 🛑, 🚧…) dans une pastille blanche, pour la carte. */
    private fun addIcons(s: Style) {
        val size = (40 * resources.displayMetrics.density).toInt()
        for (kind in PoiKind.entries) {
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = size * 0.07f
                color = if (kind.userReport) Color.parseColor("#DB2777") else Color.parseColor("#374151")
            }
            val r = size / 2f
            c.drawCircle(r, r, r * 0.9f, bg)
            c.drawCircle(r, r, r * 0.9f, border)
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size * 0.5f
                textAlign = Paint.Align.CENTER
            }
            val y = r - (text.descent() + text.ascent()) / 2f
            c.drawText(kind.icon, r, y, text)
            s.addImage(kind.name, bmp)
        }
    }

    private fun addLayers(s: Style) {
        for (id in listOf(SRC_DONE, SRC_TODO, SRC_DEST, SRC_POI, SRC_REPORTS)) {
            if (s.getSource(id) == null) s.addSource(GeoJsonSource(id))
        }
        // Partie déjà parcourue : grise
        s.addLayer(
            LineLayer(LYR_DONE, SRC_DONE).withProperties(
                PropertyFactory.lineColor("#9CA3AF"),
                PropertyFactory.lineWidth(7f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            )
        )
        // Partie restante : vert vif avec contour
        s.addLayer(
            LineLayer(LYR_CASING, SRC_TODO).withProperties(
                PropertyFactory.lineColor("#0B3D2E"),
                PropertyFactory.lineWidth(12f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            )
        )
        s.addLayer(
            LineLayer(LYR_TODO, SRC_TODO).withProperties(
                PropertyFactory.lineColor("#22C55E"),
                PropertyFactory.lineWidth(7f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            )
        )
        s.addLayer(
            CircleLayer(LYR_DEST, SRC_DEST).withProperties(
                PropertyFactory.circleRadius(10f),
                PropertyFactory.circleColor("#E11D48"),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(3f),
            )
        )
        for ((layerId, srcId) in listOf(LYR_REPORTS to SRC_REPORTS, LYR_POI to SRC_POI)) {
            s.addLayer(
                SymbolLayer(layerId, srcId).withProperties(
                    PropertyFactory.iconImage(Expression.get("icon")),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                    PropertyFactory.iconSize(0.85f),
                )
            )
        }
    }

    private fun emptyCollection() = FeatureCollection.fromFeatures(ArrayList<Feature>())

    private fun lineOf(points: List<GeoPoint>): Feature =
        Feature.fromGeometry(LineString.fromLngLats(points.map { Point.fromLngLat(it.lon, it.lat) }))

    private fun poiFeature(kind: PoiKind, p: GeoPoint): Feature {
        val f = Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat))
        f.addStringProperty("icon", kind.name)
        return f
    }

    /** Dessine le trajet complet (aperçu ou début de guidage) + ses feux/obstacles. */
    private fun drawRoute(route: Route?) {
        val s = style ?: return
        val todo = s.getSourceAs<GeoJsonSource>(SRC_TODO) ?: return
        val done = s.getSourceAs<GeoJsonSource>(SRC_DONE) ?: return
        val dest = s.getSourceAs<GeoJsonSource>(SRC_DEST) ?: return
        val poi = s.getSourceAs<GeoJsonSource>(SRC_POI) ?: return
        done.setGeoJson(emptyCollection())
        if (route == null) {
            todo.setGeoJson(emptyCollection())
            dest.setGeoJson(emptyCollection())
            poi.setGeoJson(emptyCollection())
            return
        }
        todo.setGeoJson(lineOf(route.points))
        dest.setGeoJson(Feature.fromGeometry(Point.fromLngLat(route.destination.lon, route.destination.lat)))
        poi.setGeoJson(FeatureCollection.fromFeatures(route.pois.map { poiFeature(it.kind, it.point) }))
    }

    /** En guidage : partie parcourue en gris, partie restante en vert, coupées au curseur. */
    private fun drawProgress(route: Route, segIndex: Int, cursor: GeoPoint) {
        val s = style ?: return
        val todo = s.getSourceAs<GeoJsonSource>(SRC_TODO) ?: return
        val done = s.getSourceAs<GeoJsonSource>(SRC_DONE) ?: return
        val pts = route.points
        val i = segIndex.coerceIn(0, pts.size - 2)
        val donePts = ArrayList<GeoPoint>(i + 2).apply { addAll(pts.subList(0, i + 1)); add(cursor) }
        val todoPts = ArrayList<GeoPoint>(pts.size - i).apply { add(cursor); addAll(pts.subList(i + 1, pts.size)) }
        done.setGeoJson(lineOf(donePts))
        todo.setGeoJson(lineOf(todoPts))
    }

    private fun drawReports() {
        val src = style?.getSourceAs<GeoJsonSource>(SRC_REPORTS) ?: return
        src.setGeoJson(FeatureCollection.fromFeatures(ReportStore.all(this).map { poiFeature(it.kind, it.point) }))
    }

    private fun redrawCurrentRoute() {
        val s = NavState.state.value
        if (s.active) {
            drawRoute(s.route)
            drawnRouteVersion = s.routeVersion
        } else {
            drawRoute(previewRoute)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableLocationComponent(s: Style) {
        if (!hasLocationPermission()) return
        val m = map ?: return
        val lc = m.locationComponent
        lc.activateLocationComponent(
            LocationComponentActivationOptions.builder(this, s)
                .useDefaultLocationEngine(false)
                .build()
        )
        lc.setLocationComponentEnabled(true)
        // La caméra est pilotée par l'application (voir navCamera) : le composant n'affiche que le curseur
        lc.setCameraMode(CameraMode.NONE)
        lc.setRenderMode(if (navActive) RenderMode.GPS else RenderMode.NORMAL)
        (NavState.state.value.location.takeIf { navActive } ?: lastLocation)?.let { lc.forceLocationUpdate(it) }
    }

    // =====================================================================
    // CAMÉRA — suivi façon Google Maps
    // =====================================================================

    /**
     * En guidage : la carte suit le cycliste, tournée dans le sens de la route,
     * inclinée en 3D, curseur dans le tiers bas de l'écran pour voir loin devant.
     * Le zoom se resserre à l'approche d'un virage et s'élargit en ligne droite.
     */
    private fun navCamera(s: NavSnapshot, animate: Boolean = true) {
        val m = map ?: return
        val loc = s.location ?: return
        val zoom = when {
            s.distanceToNextM in 0.0..120.0 -> 18.2
            s.speedKmh > 24.0 -> 16.8
            s.distanceToNextM > 600.0 -> 17.0
            else -> 17.5
        }
        val topPadding = mapView.height * 0.42
        val position = CameraPosition.Builder()
            .target(LatLng(loc.latitude, loc.longitude))
            .zoom(zoom)
            .bearing(s.cameraBearing)
            .tilt(50.0)
            .padding(0.0, topPadding, 0.0, 0.0)
            .build()
        val update = CameraUpdateFactory.newCameraPosition(position)
        if (animate) m.animateCamera(update, 950) else m.moveCamera(update)
    }

    /** Hors guidage : carte centrée sur soi, nord en haut. */
    private fun browseCamera(loc: Location, animate: Boolean) {
        val m = map ?: return
        val currentZoom = m.cameraPosition.zoom
        val position = CameraPosition.Builder()
            .target(LatLng(loc.latitude, loc.longitude))
            .zoom(if (currentZoom < 14.5) 16.0 else currentZoom)
            .bearing(0.0)
            .tilt(0.0)
            .padding(0.0, 0.0, 0.0, 0.0)
            .build()
        val update = CameraUpdateFactory.newCameraPosition(position)
        if (animate) m.animateCamera(update, 800) else m.moveCamera(update)
    }

    /** Bouton « Recentrer » ou retour automatique. */
    private fun recenter() {
        following = true
        btnRecenter.visibility = View.GONE
        val s = NavState.state.value
        if (s.active) navCamera(s, animate = true) else lastLocation?.let { browseCamera(it, animate = true) }
    }

    private fun maybeAutoRecenter() {
        if (!following && navActive && SystemClock.elapsedRealtime() - lastGestureAt > AUTO_RECENTER_MS) {
            following = true
            btnRecenter.visibility = View.GONE
        }
    }

    private fun frameRoute(route: Route) {
        val m = map ?: return
        following = false
        val bounds = LatLngBounds.Builder()
            .includes(route.points.map { LatLng(it.lat, it.lon) })
            .build()
        val d = resources.displayMetrics.density
        m.animateCamera(
            CameraUpdateFactory.newLatLngBounds(bounds, (40 * d).toInt(), (110 * d).toInt(), (40 * d).toInt(), (320 * d).toInt())
        )
        btnRecenter.visibility = View.VISIBLE
    }

    // =====================================================================
    // Boutons, recherche, itinéraire
    // =====================================================================

    private fun setupButtons() {
        findViewById<MaterialButton>(R.id.btnSearch).setOnClickListener { doSearch() }
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch(); true
            } else false
        }
        findViewById<MaterialButton>(R.id.btnProfile).setOnClickListener { showProfileDialog() }

        btnLayers.setOnClickListener {
            satellite = !satellite
            btnLayers.text = getString(if (satellite) R.string.plan else R.string.satellite)
            loadStyle()
        }

        btnRecenter.setOnClickListener { recenter() }
        btnReport.setOnClickListener { showReportDialog() }
        btnVoice.setOnClickListener {
            Settings.setVoice(this, !Settings.voiceOn(this))
            updateVoiceButton()
            toast(if (Settings.voiceOn(this)) "Voix activée" else "Voix coupée (appuyez sur le bandeau pour entendre)")
        }
        // Appui sur le bandeau vert = répéter l'instruction
        navBanner.setOnClickListener { NavigationService.repeat(this) }

        modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            mode = when (checkedId) {
                R.id.modeFast -> RouteMode.FAST
                R.id.modeSafe -> RouteMode.SAFE
                else -> RouteMode.BALANCED
            }
            if (destination != null && !navActive) computeRoute()
        }

        findViewById<MaterialButton>(R.id.btnCancel).setOnClickListener { clearPreview() }

        findViewById<MaterialButton>(R.id.btnStart).setOnClickListener {
            val r = previewRoute ?: return@setOnClickListener
            if (!hasLocationPermission()) {
                requestPermissions(); return@setOnClickListener
            }
            NavigationService.start(this, r)
            askBatteryOptimizationOnce()
        }

        // Simulation : le trajet est parcouru virtuellement à ~22 km/h, avec la voix
        findViewById<MaterialButton>(R.id.btnSimulate).setOnClickListener {
            val r = previewRoute ?: return@setOnClickListener
            toast("Simulation : le trajet va être parcouru à environ 22 km/h.")
            NavigationService.start(this, r, simulate = true)
        }

        findViewById<MaterialButton>(R.id.btnStop).setOnClickListener {
            NavigationService.stop(this)
        }
    }

    private fun updateVoiceButton() {
        btnVoice.text = getString(if (Settings.voiceOn(this)) R.string.voice_on else R.string.voice_off)
    }

    private fun doSearch() {
        val q = searchInput.text.toString().trim()
        if (q.length < 3) {
            toast("Tapez au moins 3 lettres (adresse, école, lieu…)"); return
        }
        hideKeyboard()
        val near = lastLocation?.let { GeoPoint(it.latitude, it.longitude) } ?: MONTREAL
        loading.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val places = PhotonClient.search(q, near)
                if (places.isEmpty()) {
                    toast("Aucun résultat pour « $q »")
                } else {
                    val items = Array<CharSequence>(places.size) { i ->
                        val p = places[i]
                        if (p.detail.isBlank()) p.name else p.name + "\n" + p.detail
                    }
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("Choisir la destination")
                        .setItems(items) { _, which -> setDestination(places[which]) }
                        .setNegativeButton("Annuler", null)
                        .show()
                }
            } catch (e: Exception) {
                toast("Recherche impossible. Vérifiez la connexion Internet.")
            } finally {
                loading.visibility = View.GONE
            }
        }
    }

    private fun setDestination(place: Place) {
        destination = place
        computeRoute()
    }

    private fun computeRoute() {
        val place = destination ?: return
        val loc = lastLocation
        if (loc == null) {
            toast("Position GPS pas encore trouvée… réessayez dans quelques secondes.")
            return
        }
        loading.visibility = View.VISIBLE
        previewPoiJob?.cancel()
        lifecycleScope.launch {
            try {
                val r = ValhallaClient.route(GeoPoint(loc.latitude, loc.longitude), place.point, mode, place.name)
                previewRoute = r
                drawRoute(r)
                showPreview(place, r)
                frameRoute(r)
                loadPreviewPois(r)
            } catch (e: Exception) {
                toast("Itinéraire impossible : ${e.message ?: "erreur réseau"}")
            } finally {
                loading.visibility = View.GONE
            }
        }
    }

    /** Feux/obstacles de l'aperçu, chargés en arrière-plan. */
    private fun loadPreviewPois(r: Route) {
        previewPoiJob = lifecycleScope.launch {
            val pois = PoiLoader.load(this@MainActivity, r)
            if (previewRoute !== r) return@launch
            val withPois = r.copy(pois = pois)
            previewRoute = withPois
            drawRoute(withPois)
            destination?.let { showPreview(it, withPois) }
        }
    }

    private fun showPreview(place: Place, r: Route) {
        val profile = Settings.profile(this)
        routeTitle.text = place.name
        routeSummary.text = "${Format.displayDistance(r.lengthKm * 1000)} · ${Format.duration(r.timeSec)} · ${r.mode.label}"
        val counts = r.pois.groupingBy { it.kind }.eachCount()
        val poiLine = if (counts.isEmpty()) "" else "\n" + counts.entries.joinToString("  ") { "${it.key.icon} ${it.value}" }
        routeBattery.text = profile.summary(r.lengthKm, r.ascentM, BikeProfile.isColdSeason()) + poiLine
        previewPanel.visibility = View.VISIBLE
    }

    private fun clearPreview() {
        previewPoiJob?.cancel()
        previewRoute = null
        destination = null
        previewPanel.visibility = View.GONE
        drawRoute(null)
        recenter()
    }

    // =====================================================================
    // Signalements et profil
    // =====================================================================

    private fun showReportDialog() {
        val loc = NavState.state.value.location ?: lastLocation
        if (loc == null) {
            toast("Position inconnue pour le moment."); return
        }
        val kinds = PoiKind.userKinds
        val items = Array<CharSequence>(kinds.size) { i -> "${kinds[i].icon}  ${kinds[i].label}" }
        MaterialAlertDialogBuilder(this)
            .setTitle("Signaler ici")
            .setItems(items) { _, which ->
                ReportStore.add(this, kinds[which], GeoPoint(loc.latitude, loc.longitude))
                drawReports()
                toast("${kinds[which].icon} Signalement enregistré : il vous sera annoncé aux prochains passages.")
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun showProfileDialog() {
        val p = Settings.profile(this)
        val d = resources.displayMetrics.density
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * d).toInt(), (8 * d).toInt(), (24 * d).toInt(), 0)
        }
        fun field(label: String, value: Double): EditText {
            panel.addView(TextView(this).apply { text = label; textSize = 14f })
            val e = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(if (value % 1.0 == 0.0) value.toInt().toString() else value.toString())
            }
            panel.addView(e)
            return e
        }
        val volts = field("Tension de la batterie (V) — voir l'étiquette", p.volts)
        val ah = field("Capacité (Ah)", p.ampHours)
        val charge = field("Charge actuelle (%)", p.chargePct)
        val whkm = field("Consommation moyenne (Wh/km)", p.whPerKm)
        val reserve = field("Réserve à garder (%)", p.reservePct)
        val mass = field("Poids vélo + cycliste + sac (kg)", p.massKg)
        val reportCount = ReportStore.all(this).size

        MaterialAlertDialogBuilder(this)
            .setTitle("🚲 Profil du vélo")
            .setView(android.widget.ScrollView(this).apply { addView(panel) })
            .setPositiveButton("Enregistrer") { _, _ ->
                fun num(e: EditText, def: Double, range: ClosedFloatingPointRange<Double>) =
                    (e.text.toString().replace(',', '.').toDoubleOrNull() ?: def).coerceIn(range)
                val updated = BikeProfile(
                    volts = num(volts, p.volts, 12.0..96.0),
                    ampHours = num(ah, p.ampHours, 1.0..60.0),
                    chargePct = num(charge, p.chargePct, 0.0..100.0),
                    whPerKm = num(whkm, p.whPerKm, 4.0..60.0),
                    reservePct = num(reserve, p.reservePct, 0.0..60.0),
                    massKg = num(mass, p.massKg, 30.0..250.0),
                )
                Settings.saveProfile(this, updated)
                val r = previewRoute
                val dest = destination
                if (r != null && dest != null) showPreview(dest, r)
                toast("Profil enregistré.")
            }
            .setNeutralButton("Effacer mes signalements ($reportCount)") { _, _ ->
                ReportStore.clear(this)
                drawReports()
                toast("Signalements effacés.")
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // =====================================================================
    // Affichage de la navigation (données venant du service)
    // =====================================================================

    private fun render(s: NavSnapshot) {
        val wasActive = navActive
        navActive = s.active
        if (s.active) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            searchCard.visibility = View.GONE
            previewPanel.visibility = View.GONE
            navBanner.visibility = View.VISIBLE
            navBottom.visibility = View.VISIBLE
            btnReport.visibility = View.VISIBLE
            btnVoice.visibility = View.VISIBLE

            navArrow.text = Format.arrowFor(s.maneuverType)
            navDistance.text = if (s.distanceToNextM > 0) Format.displayDistance(s.distanceToNextM) else ""
            navInstruction.text = s.status ?: s.instruction
            val poi = s.nextPoi
            if (poi != null && s.nextPoiDistanceM <= 300.0) {
                navPoi.text = "${poi.kind.icon} ${poi.kind.label} · ${Format.displayDistance(s.nextPoiDistanceM)}"
                navPoi.visibility = View.VISIBLE
            } else {
                navPoi.visibility = View.GONE
            }
            navSpeed.text = s.speedKmh.roundToInt().toString() + if (s.simulated) "*" else ""
            navRemaining.text = "${Format.displayDistance(s.remainingM)} · ${Format.duration(s.remainingSec)}\nArrivée ${Format.eta(s.remainingSec)}"

            val route = s.route
            if (route != null && s.routeVersion != drawnRouteVersion) {
                drawRoute(route)
                drawnRouteVersion = s.routeVersion
            }

            if (!wasActive) {
                // Début du guidage : on passe en mode suivi 3D
                previewRoute = null
                following = true
                btnRecenter.visibility = View.GONE
                map?.locationComponent?.let { if (it.isLocationComponentActivated) it.setRenderMode(RenderMode.GPS) }
            }

            val loc = s.location
            if (loc != null) {
                val lc = map?.locationComponent
                if (lc != null && lc.isLocationComponentActivated) lc.forceLocationUpdate(loc)
                if (route != null) drawProgress(route, s.segmentIndex, GeoPoint(loc.latitude, loc.longitude))
                maybeAutoRecenter()
                if (following) navCamera(s)
            }
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            navBanner.visibility = View.GONE
            navBottom.visibility = View.GONE
            btnReport.visibility = View.GONE
            btnVoice.visibility = View.GONE
            searchCard.visibility = View.VISIBLE
            if (wasActive) {
                drawnRouteVersion = -1
                destination = null
                previewRoute = null
                drawRoute(null)
                map?.locationComponent?.let { if (it.isLocationComponentActivated) it.setRenderMode(RenderMode.NORMAL) }
                recenter()
                if (s.arrived) toast("🏁 Vous êtes arrivé !")
            }
        }
    }

    // =====================================================================
    // GPS, permissions, batterie
    // =====================================================================

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        permissionLauncher.launch(perms.toTypedArray())
    }

    @SuppressLint("MissingPermission")
    private fun startActivityLocation() {
        if (!hasLocationPermission()) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()
        fused.removeLocationUpdates(activityLocationCallback)
        fused.requestLocationUpdates(request, activityLocationCallback, Looper.getMainLooper())
    }

    /** Samsung met en veille les applis en arrière-plan : on demande une exception, une seule fois. */
    private fun askBatteryOptimizationOnce() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        if (prefs.getBoolean("asked_battery", false)) return
        prefs.edit().putBoolean("asked_battery", true).apply()
        MaterialAlertDialogBuilder(this)
            .setTitle("Guidage écran verrouillé")
            .setMessage("Pour que la voix continue quand l'écran est éteint, autorisez l'application à ignorer l'optimisation de la batterie.")
            .setPositiveButton("Autoriser") { _, _ ->
                try {
                    @SuppressLint("BatteryLife")
                    val i = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                    startActivity(i)
                } catch (e: Exception) {
                    startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
            .setNegativeButton("Plus tard", null)
            .show()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(searchInput.windowToken, 0)
        searchInput.clearFocus()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
