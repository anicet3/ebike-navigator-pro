package ca.anicet.ebikenav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Les trois façons de rouler proposées à l'écran.
 * Les chiffres sont des réglages du moteur Valhalla (0 = évite, 1 = accepte volontiers).
 * - useRoads : accepter les rues partagées avec les voitures
 * - useHills : accepter les côtes (un e-bike les grimpe facilement)
 * - speedKmh : vitesse moyenne réelle d'un vélo électrique en ville
 */
enum class RouteMode(val label: String, val useRoads: Double, val useHills: Double, val speedKmh: Double, val avoidBadSurfaces: Double) {
    FAST("Rapide", 0.65, 0.9, 25.0, 0.3),
    BALANCED("Équilibré", 0.3, 0.6, 23.0, 0.5),
    SAFE("Sécurisé", 0.05, 0.5, 21.0, 0.7)
}

/** Une instruction de guidage (« Tournez à droite sur … »). */
data class Maneuver(
    val type: Int,
    val instruction: String,
    val alert: String?,
    val pre: String?,
    val post: String?,
    val beginShapeIndex: Int,
)

/** Un itinéraire complet calculé. */
data class Route(
    val points: List<GeoPoint>,
    val maneuvers: List<Maneuver>,
    val lengthKm: Double,
    val timeSec: Double,
    val mode: RouteMode,
    val destination: GeoPoint,
    val destinationName: String,
    /** Total des montées en mètres (null si le serveur ne l'a pas fourni). */
    val ascentM: Double? = null,
    /** Feux, arrêts, obstacles et signalements situés sur le trajet. */
    val pois: List<RoutePoi> = emptyList(),
)

/** Un lieu trouvé par la recherche d'adresse. */
data class Place(val name: String, val detail: String, val point: GeoPoint)

private const val USER_AGENT = "EBikeNavigatorPro/2.0 (application personnelle, Montréal)"

/** Requête HTTP simple (sans bibliothèque externe). formPost = envoi de formulaire (Overpass). */
internal fun httpRequest(url: String, postBody: String? = null, formPost: Boolean = false): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", "application/json")
        if (postBody != null) {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty(
                "Content-Type",
                if (formPost) "application/x-www-form-urlencoded; charset=utf-8" else "application/json; charset=utf-8"
            )
            conn.outputStream.use { it.write(postBody.toByteArray(Charsets.UTF_8)) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        if (code !in 200..299) {
            val msg = try { JSONObject(text).optString("error", "") } catch (e: Exception) { "" }
            throw IOException(if (msg.isNotBlank()) msg else "Erreur serveur ($code)")
        }
        return text
    } finally {
        conn.disconnect()
    }
}

internal fun JSONObject.optTextOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val v = optString(key, "").trim()
    return v.ifEmpty { null }
}

/**
 * Calcul d'itinéraire avec Valhalla (logiciel libre, données OpenStreetMap).
 * Serveur public gratuit de FOSSGIS : parfait pour un usage personnel léger.
 * Pour une utilisation intensive ou publique, il faudra un serveur payant ou le nôtre.
 */
object ValhallaClient {
    var baseUrl = "https://valhalla1.openstreetmap.de"

    suspend fun route(
        from: GeoPoint,
        to: GeoPoint,
        mode: RouteMode,
        destinationName: String,
        headingDeg: Double? = null,
    ): Route = withContext(Dispatchers.IO) {
        val start = JSONObject().put("lat", from.lat).put("lon", from.lon)
        if (headingDeg != null) {
            // Évite de proposer un demi-tour brutal lors d'un recalcul en roulant
            start.put("heading", headingDeg.toInt()).put("heading_tolerance", 60)
        }
        val body = JSONObject()
            .put("locations", JSONArray().put(start).put(JSONObject().put("lat", to.lat).put("lon", to.lon)))
            .put("costing", "bicycle")
            .put(
                "costing_options", JSONObject().put(
                    "bicycle", JSONObject()
                        .put("bicycle_type", "Hybrid")
                        .put("cycling_speed", mode.speedKmh)
                        .put("use_roads", mode.useRoads)
                        .put("use_hills", mode.useHills)
                        .put("avoid_bad_surfaces", mode.avoidBadSurfaces)
                )
            )
            .put("directions_options", JSONObject().put("units", "kilometers").put("language", "fr-FR"))
            .put("elevation_interval", 30) // altitude tous les 30 m → calcul des montées
            .put("id", "ebike-navigator")

        val json = JSONObject(httpRequest("$baseUrl/route", body.toString()))
        val trip = json.getJSONObject("trip")
        val leg = trip.getJSONArray("legs").getJSONObject(0)
        val points = Geo.decodePolyline(leg.getString("shape"), 6)
        val mansJson = leg.getJSONArray("maneuvers")
        val maneuvers = ArrayList<Maneuver>(mansJson.length())
        for (i in 0 until mansJson.length()) {
            val m = mansJson.getJSONObject(i)
            maneuvers.add(
                Maneuver(
                    type = m.optInt("type", 0),
                    instruction = m.optTextOrNull("instruction") ?: "",
                    alert = m.optTextOrNull("verbal_transition_alert_instruction"),
                    pre = m.optTextOrNull("verbal_pre_transition_instruction"),
                    post = m.optTextOrNull("verbal_post_transition_instruction"),
                    beginShapeIndex = m.optInt("begin_shape_index", 0).coerceIn(0, (points.size - 1).coerceAtLeast(0)),
                )
            )
        }
        val summary = trip.getJSONObject("summary")
        if (points.size < 2) throw IOException("Itinéraire vide")

        // Montées cumulées (si le serveur fournit les altitudes)
        val elevation = leg.optJSONArray("elevation")
        var ascent: Double? = null
        if (elevation != null && elevation.length() > 1) {
            var up = 0.0
            for (i in 1 until elevation.length()) {
                val diff = elevation.optDouble(i, 0.0) - elevation.optDouble(i - 1, 0.0)
                if (diff > 0.5) up += diff // ignore le « bruit » de moins de 50 cm
            }
            ascent = up
        }

        Route(
            points = points,
            maneuvers = maneuvers,
            lengthKm = summary.optDouble("length", 0.0),
            timeSec = summary.optDouble("time", 0.0),
            mode = mode,
            destination = to,
            destinationName = destinationName,
            ascentM = ascent,
        )
    }
}

/**
 * Recherche d'adresses avec Photon (logiciel libre, données OpenStreetMap).
 * Les résultats sont favorisés autour de la position actuelle.
 */
object PhotonClient {
    suspend fun search(query: String, near: GeoPoint): List<Place> = withContext(Dispatchers.IO) {
        val url = "https://photon.komoot.io/api/?q=" + URLEncoder.encode(query, "UTF-8") +
            "&limit=8&lang=fr&lat=${near.lat}&lon=${near.lon}"
        val features = JSONObject(httpRequest(url)).optJSONArray("features") ?: JSONArray()
        val out = ArrayList<Place>()
        for (i in 0 until features.length()) {
            val f = features.getJSONObject(i)
            val coords = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            val p = f.optJSONObject("properties") ?: JSONObject()
            val number = p.optTextOrNull("housenumber")
            val street = p.optTextOrNull("street")
            val streetLine = listOfNotNull(number, street).joinToString(" ").ifEmpty { null }
            val name = p.optTextOrNull("name") ?: streetLine ?: query
            val detail = listOfNotNull(
                if (name != streetLine) streetLine else null,
                p.optTextOrNull("city") ?: p.optTextOrNull("district"),
                p.optTextOrNull("state"),
            ).joinToString(", ")
            out.add(Place(name, detail, GeoPoint(coords.getDouble(1), coords.getDouble(0))))
        }
        out
    }
}
