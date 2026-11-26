package ca.anicet.ebikenav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/**
 * Types d'éléments signalés sur le trajet.
 * - icon : symbole affiché sur la carte et dans le bandeau
 * - spoken : ce que dit la voix (« Feu de circulation dans 80 mètres »)
 * - userReport : true si c'est un signalement fait par l'utilisateur
 */
enum class PoiKind(val icon: String, val label: String, val spoken: String, val userReport: Boolean = false) {
    SIGNALS("🚦", "Feu", "Feu de circulation"),
    STOP("🛑", "Arrêt", "Arrêt obligatoire"),
    GIVE_WAY("🔻", "Cédez", "Cédez le passage"),
    RAILWAY("🚆", "Passage à niveau", "Attention, passage à niveau"),
    BUMP("〰️", "Dos d'âne", "Dos d'âne"),
    BARRIER("🚧", "Obstacle", "Attention, obstacle sur la piste"),

    // Signalements faits par l'utilisateur (bouton ⚠ pendant le trajet)
    REPORT_POTHOLE("🕳️", "Nid-de-poule", "Nid-de-poule signalé", true),
    REPORT_WORKS("👷", "Travaux", "Travaux signalés", true),
    REPORT_OBSTACLE("⛔", "Obstacle", "Obstacle signalé", true),
    REPORT_ICE("❄️", "Neige ou glace", "Neige ou glace signalée", true),
    REPORT_DANGER("⚠️", "Danger", "Danger signalé", true);

    companion object {
        val userKinds: List<PoiKind> get() = entries.filter { it.userReport }
    }
}

/** Un élément situé SUR le trajet, à alongM mètres du départ. */
data class RoutePoi(val kind: PoiKind, val point: GeoPoint, val alongM: Double)

/** Un élément trouvé autour du trajet (avant de le placer sur le trajet). */
data class RawPoi(val kind: PoiKind, val point: GeoPoint)

object PoiTools {

    /**
     * Garde uniquement les éléments vraiment SUR le trajet, calcule leur position
     * le long du trajet, et fusionne les doublons (ex. 4 feux d'un même carrefour).
     */
    fun attach(route: Route, raw: List<RawPoi>, maxDistanceM: Double = 22.0): List<RoutePoi> {
        val pts = route.points
        if (pts.size < 2) return emptyList()
        val cum = Geo.cumulative(pts)
        val placed = ArrayList<RoutePoi>()
        for (r in raw) {
            var bestD = Double.MAX_VALUE
            var bestAlong = 0.0
            for (i in 0 until pts.size - 1) {
                val pr = Geo.project(r.point, pts[i], pts[i + 1])
                if (pr.distanceM < bestD) {
                    bestD = pr.distanceM
                    bestAlong = cum[i] + pr.t * (cum[i + 1] - cum[i])
                }
            }
            val limit = if (r.kind.userReport) 25.0 else maxDistanceM
            if (bestD <= limit) placed.add(RoutePoi(r.kind, r.point, bestAlong))
        }
        placed.sortBy { it.alongM }
        val out = ArrayList<RoutePoi>()
        for (p in placed) {
            val dup = out.lastOrNull { it.kind == p.kind && p.alongM - it.alongM < 40.0 }
            if (dup == null) out.add(p)
        }
        return out
    }

    /** Points régulièrement espacés le long du trajet (pour interroger Overpass). */
    fun sample(points: List<GeoPoint>, maxSamples: Int = 220): List<GeoPoint> {
        if (points.size < 2) return points
        val cum = Geo.cumulative(points)
        val spacing = maxOf(25.0, cum.last() / maxSamples)
        val out = ArrayList<GeoPoint>()
        var next = 0.0
        var seg = 0
        while (next <= cum.last()) {
            while (seg < cum.size - 2 && cum[seg + 1] < next) seg++
            val len = cum[seg + 1] - cum[seg]
            val t = if (len > 0) (next - cum[seg]) / len else 0.0
            val a = points[seg]
            val b = points[seg + 1]
            out.add(GeoPoint(a.lat + t * (b.lat - a.lat), a.lon + t * (b.lon - a.lon)))
            next += spacing
        }
        out.add(points.last())
        return out
    }

    /** Traduit les étiquettes OpenStreetMap d'un élément en type connu (ou null). */
    fun kindFromTags(tags: JSONObject): PoiKind? {
        val highway = tags.optString("highway", "")
        val barrier = tags.optString("barrier", "")
        return when {
            highway == "traffic_signals" -> PoiKind.SIGNALS
            highway == "stop" -> PoiKind.STOP
            highway == "give_way" -> PoiKind.GIVE_WAY
            tags.optString("railway", "") == "level_crossing" -> PoiKind.RAILWAY
            tags.has("traffic_calming") && tags.optString("traffic_calming") != "no" -> PoiKind.BUMP
            barrier in setOf("bollard", "cycle_barrier", "gate", "lift_gate", "block", "chain", "swing_gate") -> PoiKind.BARRIER
            else -> null
        }
    }
}

/**
 * Feux, arrêts, passages à niveau, dos d'âne et obstacles, lus dans OpenStreetMap
 * (serveur public Overpass, gratuit) le long du trajet.
 */
object OverpassClient {
    /**
     * Serveurs essayés dans l'ordre : si le premier est surchargé (fréquent),
     * on passe au suivant. Vérifié le 26/11/2026 : le principal renvoyait une erreur 502,
     * le serveur de secours maps.mail.ru répondait en ~14 s.
     */
    val servers = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )

    suspend fun fetchAlong(route: Route): List<RawPoi> = withContext(Dispatchers.IO) {
        val coords = PoiTools.sample(route.points)
            .joinToString(",") { String.format(Locale.US, "%.5f,%.5f", it.lat, it.lon) }
        val query = "[out:json][timeout:25];(" +
            "node(around:20,$coords)[\"highway\"~\"^(traffic_signals|stop|give_way)$\"];" +
            "node(around:20,$coords)[\"railway\"=\"level_crossing\"];" +
            "node(around:12,$coords)[\"traffic_calming\"];" +
            "node(around:10,$coords)[\"barrier\"~\"^(bollard|cycle_barrier|gate|lift_gate|block|chain|swing_gate)$\"];" +
            ");out body;"
        val body = "data=" + URLEncoder.encode(query, "UTF-8")
        var json: JSONObject? = null
        var lastError: Exception? = null
        for (server in servers) {
            try {
                val candidate = JSONObject(httpRequest(server, body, formPost = true))
                if (candidate.has("elements")) {
                    json = candidate
                    break
                }
            } catch (e: Exception) {
                lastError = e // serveur suivant
            }
        }
        if (json == null) throw lastError ?: java.io.IOException("Aucun serveur Overpass disponible")
        val elements = json.optJSONArray("elements") ?: return@withContext emptyList<RawPoi>()
        val out = ArrayList<RawPoi>()
        for (i in 0 until elements.length()) {
            val e = elements.getJSONObject(i)
            val tags = e.optJSONObject("tags") ?: continue
            val kind = PoiTools.kindFromTags(tags) ?: continue
            if (!e.has("lat") || !e.has("lon")) continue
            out.add(RawPoi(kind, GeoPoint(e.getDouble("lat"), e.getDouble("lon"))))
        }
        out
    }
}
