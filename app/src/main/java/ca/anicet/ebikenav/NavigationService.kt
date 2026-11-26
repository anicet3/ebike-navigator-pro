package ca.anicet.ebikenav

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Service « au premier plan » : il continue de suivre le GPS et de parler
 * même quand l'écran est verrouillé ou que l'on change d'application.
 * Une notification permanente indique qu'il est actif (obligatoire sur Android).
 */
class NavigationService : Service(), TextToSpeech.OnInitListener {

    companion object {
        private const val ACTION_STOP = "ca.anicet.ebikenav.STOP"
        private const val ACTION_REPEAT = "ca.anicet.ebikenav.REPEAT"
        private const val CHANNEL_ID = "navigation"
        private const val NOTIFICATION_ID = 42

        /** Vitesse du trajet simulé : 6 m/s ≈ 22 km/h. */
        private const val SIMULATION_SPEED_MS = 6.0

        @Volatile
        private var pendingRoute: Route? = null

        @Volatile
        private var pendingSimulate = false

        /**
         * Démarre le guidage. Avec simulate = true, un faux GPS « roule » le long du trajet :
         * pratique pour tester la voix et les écrans à la maison.
         */
        fun start(context: Context, route: Route, simulate: Boolean = false) {
            pendingRoute = route
            pendingSimulate = simulate
            ContextCompat.startForegroundService(context, Intent(context, NavigationService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NavigationService::class.java))
        }

        /** Répète l'instruction en cours (bouton « Répéter » / appui sur le bandeau). */
        fun repeat(context: Context) {
            if (!NavState.state.value.active) return
            context.startService(Intent(context, NavigationService::class.java).setAction(ACTION_REPEAT))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var fused: FusedLocationProviderClient
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val pendingSpeech = ArrayList<String>()
    private var engine: NavigationEngine? = null
    private var rerouting = false
    private var lastRerouteAt = 0L
    private var lastNotificationAt = 0L
    private var finishing = false
    private var simulate = false
    private var simulationJob: Job? = null
    private var poiJob: Job? = null
    private var lastUpdate: NavigationEngine.Update? = null
    private var lastGoodBearing = 0.0
    private lateinit var audioManager: AudioManager
    private lateinit var focusRequest: AudioFocusRequest
    private val speechAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            onLocation(loc)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        audioManager = getSystemService(AudioManager::class.java)
        // Pendant une annonce, la musique (si elle joue) baisse puis remonte
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(speechAttributes)
            .build()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Toujours passer au premier plan tout de suite (exigence d'Android)
        goForeground("Démarrage du guidage…")

        if (intent?.action == ACTION_STOP) {
            stopNavigation()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_REPEAT) {
            repeatInstruction(force = true)
            return START_NOT_STICKY
        }
        val route = pendingRoute
        if (route == null) {
            stopNavigation()
            return START_NOT_STICKY
        }
        pendingRoute = null
        finishing = false
        simulate = pendingSimulate

        if (tts == null) tts = TextToSpeech(this, this)
        engine = NavigationEngine(route)
        lastUpdate = null
        loadPois(route)

        val first = route.maneuvers.firstOrNull()
        NavState.state.value = NavSnapshot(
            active = true,
            route = route,
            routeVersion = NavState.state.value.routeVersion + 1,
            instruction = first?.instruction ?: "",
            maneuverType = first?.type ?: 0,
            distanceToNextM = 0.0,
            remainingM = route.lengthKm * 1000.0,
            remainingSec = route.timeSec,
            simulated = simulate,
            voiceOn = Settings.voiceOn(this),
        )
        if (simulate) {
            speak("Mode simulation. " + (first?.pre ?: first?.instruction ?: ""))
            startSimulation(route)
        } else {
            speak("C'est parti. " + (first?.pre ?: first?.instruction ?: ""))
            startLocationUpdates()
        }
        return START_NOT_STICKY
    }

    // ---------- Simulation (test à la maison) ----------

    private fun startSimulation(route: Route) {
        simulationJob?.cancel()
        simulationJob = scope.launch {
            val pts = route.points
            val cum = Geo.cumulative(pts)
            var d = 0.0
            var seg = 0
            delay(1500)
            while (isActive && !finishing) {
                val dist = d.coerceAtMost(cum.last())
                while (seg < cum.size - 2 && cum[seg + 1] < dist) seg++
                val a = pts[seg]
                val b = pts[seg + 1]
                val segLen = cum[seg + 1] - cum[seg]
                val t = if (segLen > 0) (dist - cum[seg]) / segLen else 0.0
                val loc = Location("simulation").apply {
                    latitude = a.lat + t * (b.lat - a.lat)
                    longitude = a.lon + t * (b.lon - a.lon)
                    accuracy = 5f
                    speed = SIMULATION_SPEED_MS.toFloat()
                    bearing = Geo.bearing(a, b).toFloat()
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                }
                onLocation(loc)
                if (dist >= cum.last()) break
                d += SIMULATION_SPEED_MS
                delay(1000)
            }
        }
    }

    // ---------- GPS ----------

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            stopNavigation()
            return
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()
        fused.removeLocationUpdates(locationCallback)
        fused.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    private fun onLocation(loc: Location) {
        val eng = engine ?: return
        if (finishing) return
        val accuracy = if (loc.hasAccuracy()) loc.accuracy else 20f
        // Position trop imprécise (tunnel, immeubles) : on l'ignore, sauf si c'est la seule disponible
        if (accuracy > 60f && lastUpdate != null) return
        val speed = if (loc.hasSpeed()) loc.speed else 0f
        val u = eng.onLocation(GeoPoint(loc.latitude, loc.longitude), accuracy, speed)
        lastUpdate = u
        u.speech.forEach { speak(it) }

        // Curseur et caméra : collés à la route quand on est dessus (comme Google Maps),
        // sinon position GPS brute et cap du GPS.
        val onRoute = u.distanceFromRouteM <= 30.0 && !u.offRoute
        val camBearing = when {
            onRoute -> u.aheadBearing
            loc.hasBearing() && speed > 1.5f -> loc.bearing.toDouble()
            else -> lastGoodBearing
        }
        lastGoodBearing = camBearing
        val display = Location(loc)
        if (onRoute) {
            display.latitude = u.snapped.lat
            display.longitude = u.snapped.lon
        }
        display.bearing = camBearing.toFloat()

        val route = eng.route
        val m = route.maneuvers.getOrNull(u.maneuverIndex)
        val totalM = route.lengthKm * 1000.0
        val ratio = if (totalM > 0) u.remainingM / totalM else 0.0
        NavState.state.value = NavState.state.value.copy(
            active = true,
            distanceToNextM = u.distanceToNextM,
            instruction = m?.instruction ?: "",
            maneuverType = m?.type ?: 0,
            remainingM = u.remainingM,
            remainingSec = route.timeSec * ratio,
            speedKmh = speed * 3.6,
            status = if (rerouting) "Recalcul de l'itinéraire…" else null,
            location = display,
            cameraBearing = camBearing,
            segmentIndex = u.segmentIndex,
            onRoute = onRoute,
            nextPoi = u.nextPoi,
            nextPoiDistanceM = u.nextPoiDistanceM,
        )

        val now = SystemClock.elapsedRealtime()
        if (now - lastNotificationAt > 3000) {
            lastNotificationAt = now
            updateNotification("${Format.displayDistance(u.distanceToNextM)} · ${m?.instruction ?: ""}")
        }

        if (u.arrived) {
            finishArrived()
            return
        }
        if (u.offRoute && !simulate) maybeReroute(loc)
    }

    /** Feux, arrêts, obstacles et signalements : chargés en arrière-plan, ajoutés au guidage. */
    private fun loadPois(route: Route) {
        poiJob?.cancel()
        poiJob = scope.launch {
            val list = PoiLoader.load(this@NavigationService, route)
            val eng = engine ?: return@launch
            if (eng.route.points !== route.points) return@launch // trajet remplacé entre-temps
            eng.setPois(list)
            NavState.state.value = NavState.state.value.copy(
                route = eng.route,
                routeVersion = NavState.state.value.routeVersion + 1,
            )
        }
    }

    /** Répète l'instruction en cours, avec la distance actuelle. */
    private fun repeatInstruction(force: Boolean) {
        val eng = engine ?: return
        val u = lastUpdate
        val m = eng.route.maneuvers.getOrNull(u?.maneuverIndex ?: 0) ?: return
        val base = m.pre ?: m.instruction
        val text = if (u != null && u.distanceToNextM > 40.0) {
            "Dans ${Format.spokenDistance(u.distanceToNextM)}, ${Format.lowerFirst(base)}"
        } else base
        speak(text, force)
    }

    private fun maybeReroute(loc: Location) {
        val eng = engine ?: return
        val now = SystemClock.elapsedRealtime()
        if (rerouting || now - lastRerouteAt < 12_000) return
        rerouting = true
        lastRerouteAt = now
        speak("Vous avez quitté l'itinéraire. Recalcul en cours.")
        val old = eng.route
        val heading = if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 2f) loc.bearing.toDouble() else null
        scope.launch {
            try {
                val r = ValhallaClient.route(
                    GeoPoint(loc.latitude, loc.longitude), old.destination, old.mode, old.destinationName, heading
                )
                eng.setRoute(r)
                lastUpdate = null
                NavState.state.value = NavState.state.value.copy(
                    route = r,
                    routeVersion = NavState.state.value.routeVersion + 1,
                    status = null,
                )
                loadPois(r)
                val first = r.maneuvers.firstOrNull()
                speak("Nouvel itinéraire. " + (first?.pre ?: ""))
            } catch (e: Exception) {
                speak("Recalcul impossible pour le moment. Nouvel essai bientôt.")
                NavState.state.value = NavState.state.value.copy(status = "Pas de réseau ? Nouvel essai bientôt…")
            } finally {
                rerouting = false
            }
        }
    }

    private fun finishArrived() {
        finishing = true
        fused.removeLocationUpdates(locationCallback)
        simulationJob?.cancel()
        NavState.state.value = NavState.state.value.copy(active = false, arrived = true, status = null)
        updateNotification("Vous êtes arrivé.")
        scope.launch {
            delay(6000) // laisser la voix terminer
            stopNavigation()
        }
    }

    private fun stopNavigation() {
        fused.removeLocationUpdates(locationCallback)
        simulationJob?.cancel()
        poiJob?.cancel()
        val s = NavState.state.value
        if (s.active) NavState.state.value = s.copy(active = false, status = null)
        engine = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        fused.removeLocationUpdates(locationCallback)
        simulationJob?.cancel()
        tts?.stop()
        tts?.shutdown()
        tts = null
        audioManager.abandonAudioFocusRequest(focusRequest)
        scope.cancel()
        val s = NavState.state.value
        if (s.active) NavState.state.value = s.copy(active = false, status = null)
        super.onDestroy()
    }

    // ---------- Voix ----------

    override fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) return
        val res = t.setLanguage(Locale.CANADA_FRENCH)
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            t.setLanguage(Locale.FRENCH)
        }
        t.setAudioAttributes(speechAttributes)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                if (tts?.isSpeaking != true) audioManager.abandonAudioFocusRequest(focusRequest)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                audioManager.abandonAudioFocusRequest(focusRequest)
            }
        })
        ttsReady = true
        pendingSpeech.forEach { speakNow(it) }
        pendingSpeech.clear()
    }

    /** force = parler même si la voix est coupée (bouton « Répéter »). */
    private fun speak(text: String, force: Boolean = false) {
        if (text.isBlank()) return
        if (!force && !Settings.voiceOn(this)) return
        if (ttsReady) speakNow(text) else pendingSpeech.add(text)
    }

    private fun speakNow(text: String) {
        audioManager.requestAudioFocus(focusRequest)
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "nav-" + SystemClock.elapsedRealtimeNanos())
    }

    // ---------- Notification ----------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Navigation", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Guidage en cours"
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, NavigationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav_small)
            .setContentTitle("Navigation e-bike")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp)
            .addAction(0, "Arrêter", stopIntent)
            .build()
    }

    private fun goForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }
}
