package ca.anicet.ebikenav

import kotlin.math.max
import kotlin.math.min

/**
 * Le « cerveau » du guidage. À chaque position GPS il calcule :
 * - la position RECALÉE sur la route (le curseur colle au trajet, comme Google Maps),
 * - la direction de la route devant soi (pour orienter la carte),
 * - la distance jusqu'au prochain virage et jusqu'au prochain feu/obstacle,
 * - ce qu'il faut dire à voix haute (et quand),
 * - si l'on a quitté le trajet (→ recalcul).
 */
class NavigationEngine(route: Route) {

    data class Update(
        val distanceToNextM: Double,
        val maneuverIndex: Int,
        val remainingM: Double,
        val offRoute: Boolean,
        val speech: List<String>,
        val arrived: Boolean,
        /** Position recalée sur le trajet. */
        val snapped: GeoPoint,
        /** Écart entre le GPS et le trajet (mètres). */
        val distanceFromRouteM: Double,
        /** Index du segment courant (pour griser la partie parcourue). */
        val segmentIndex: Int,
        /** Distance parcourue depuis le départ (mètres). */
        val alongM: Double,
        /** Direction de la route ~30 m devant (degrés), pour orienter la carte. */
        val aheadBearing: Double,
        val nextPoi: RoutePoi?,
        val nextPoiDistanceM: Double,
    )

    var route: Route = route
        private set

    private var cum = Geo.cumulative(route.points)
    private var pois: List<RoutePoi> = route.pois.sortedBy { it.alongM }
    private var lastSegment = 0
    private var lastAlong = 0.0
    private var offRouteCount = 0
    private var currentManeuver = 1
    private val alertDone = HashSet<Int>()
    private val preDone = HashSet<Int>()
    private val postDone = HashSet<Int>()
    private val poiDone = HashSet<Int>()

    init {
        preDone.add(0) // la 1re instruction est dite au démarrage
    }

    /** Remplace le trajet (après un recalcul). */
    fun setRoute(newRoute: Route) {
        route = newRoute
        cum = Geo.cumulative(newRoute.points)
        pois = newRoute.pois.sortedBy { it.alongM }
        lastSegment = 0
        lastAlong = 0.0
        offRouteCount = 0
        currentManeuver = 1
        alertDone.clear()
        preDone.clear()
        postDone.clear()
        poiDone.clear()
        preDone.add(0)
    }

    /** Ajoute les feux/obstacles quand ils arrivent (chargés en arrière-plan). */
    fun setPois(list: List<RoutePoi>) {
        pois = list.sortedBy { it.alongM }
        route = route.copy(pois = pois)
        poiDone.clear()
        // Ne pas annoncer ceux déjà dépassés
        pois.forEachIndexed { i, p -> if (p.alongM < lastAlong + 10.0) poiDone.add(i) }
    }

    private fun cumAt(shapeIndex: Int): Double = cum[shapeIndex.coerceIn(0, cum.size - 1)]

    /** Point situé à `distance` mètres du départ, sur le trajet. */
    private fun pointAt(distance: Double): GeoPoint {
        val pts = route.points
        val d = distance.coerceIn(0.0, cum.last())
        var lo = 0
        var hi = cum.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (cum[mid] <= d) lo = mid else hi = mid
        }
        val len = cum[hi] - cum[lo]
        val t = if (len > 0) (d - cum[lo]) / len else 0.0
        val a = pts[lo]
        val b = pts[hi]
        return GeoPoint(a.lat + t * (b.lat - a.lat), a.lon + t * (b.lon - a.lon))
    }

    fun onLocation(p: GeoPoint, accuracyM: Float, speedMs: Float): Update {
        val pts = route.points
        val mans = route.maneuvers
        val total = cum.last()

        // 1) Trouver le segment du trajet le plus proche (d'abord autour de la dernière position)
        var best = lastSegment.coerceIn(0, pts.size - 2)
        var bestDist = Double.MAX_VALUE
        var bestT = 0.0
        val from = max(0, lastSegment - 3)
        val to = min(pts.size - 2, lastSegment + 80)
        for (i in from..to) {
            val pr = Geo.project(p, pts[i], pts[i + 1])
            if (pr.distanceM < bestDist) {
                bestDist = pr.distanceM; best = i; bestT = pr.t
            }
        }
        if (bestDist > 40.0) { // recherche complète si on s'est éloigné
            for (i in 0 until pts.size - 1) {
                val pr = Geo.project(p, pts[i], pts[i + 1])
                if (pr.distanceM < bestDist) {
                    bestDist = pr.distanceM; best = i; bestT = pr.t
                }
            }
        }
        lastSegment = best
        val along = cum[best] + bestT * (cum[best + 1] - cum[best])
        lastAlong = along
        val remaining = max(0.0, total - along)
        val a = pts[best]
        val b = pts[best + 1]
        val snapped = GeoPoint(a.lat + bestT * (b.lat - a.lat), a.lon + bestT * (b.lon - a.lon))
        val ahead = pointAt(along + 30.0)
        val aheadBearing = if (Geo.distance(snapped, ahead) > 3.0) Geo.bearing(snapped, ahead) else Geo.bearing(a, b)

        // 2) Hors trajet ? (3 positions de suite trop loin, en tenant compte de la précision GPS)
        val threshold = max(30.0, min(60.0, accuracyM * 1.5))
        offRouteCount = if (bestDist > threshold) offRouteCount + 1 else 0
        val offRoute = offRouteCount >= 3

        val speech = ArrayList<String>()

        // 3) Prochaine manœuvre = la première située devant nous
        var idx = max(0, mans.size - 1)
        var distToNext = remaining
        var arrived = remaining < 15.0
        if (mans.size >= 2) {
            idx = mans.size - 1
            for (i in 1 until mans.size) {
                if (cumAt(mans[i].beginShapeIndex) > along + 3.0) { idx = i; break }
            }

            // 4) On vient de passer un virage → « Continuez sur … » si le prochain est loin
            if (idx > currentManeuver) {
                val passed = mans[idx - 1]
                val distNext = cumAt(mans[idx].beginShapeIndex) - along
                val post = passed.post
                if (post != null && distNext > 250.0 && postDone.add(idx - 1)) speech.add(post)
                currentManeuver = idx
            }

            val m = mans[idx]
            distToNext = max(0.0, cumAt(m.beginShapeIndex) - along)
            val isArrival = m.type in 4..6
            val v = max(speedMs.toDouble(), 4.0) // ≥ 4 m/s (≈ 15 km/h) pour anticiper
            val preDistance = max(35.0, v * 7.0) // ≈ 7 secondes avant le virage
            val alertDistance = max(150.0, v * 25.0) // ≈ 25 secondes avant

            // 5) Annonces vocales des virages
            if (!offRoute) {
                if (distToNext <= preDistance) {
                    if (preDone.add(idx)) {
                        speech.add(m.pre ?: m.instruction)
                        alertDone.add(idx)
                    }
                } else if (distToNext <= alertDistance && distToNext > preDistance + 40.0) {
                    if (alertDone.add(idx)) {
                        val distText = Format.spokenDistance(distToNext)
                        if (isArrival) {
                            speech.add("Dans $distText, vous arriverez à destination.")
                        } else {
                            val base = m.alert ?: m.pre ?: m.instruction
                            speech.add("Dans $distText, ${Format.lowerFirst(base)}")
                        }
                    }
                }
            }
            arrived = (isArrival && distToNext < 20.0) || remaining < 15.0
            if (arrived && preDone.add(mans.size - 1)) {
                speech.add(mans.last().pre ?: "Vous êtes arrivé à destination.")
            }
        }

        // 6) Feux, arrêts, obstacles, signalements : prochain élément devant soi
        var nextPoi: RoutePoi? = null
        var nextPoiDist = 0.0
        val poiWindow = max(70.0, max(speedMs.toDouble(), 4.0) * 10.0) // ≈ 10 s avant
        for ((i, poi) in pois.withIndex()) {
            val d = poi.alongM - along
            if (d < -3.0) continue
            if (nextPoi == null) { nextPoi = poi; nextPoiDist = max(0.0, d) }
            if (d > poiWindow) break
            // Une seule annonce par seconde : si un virage vient d'être annoncé, on attend
            if (!offRoute && !arrived && speech.isEmpty() && d > 12.0 && poiDone.add(i)) {
                speech.add("${poi.kind.spoken} dans ${Format.spokenDistance(d)}.")
            }
            break
        }

        return Update(
            distanceToNextM = distToNext,
            maneuverIndex = idx,
            remainingM = remaining,
            offRoute = offRoute,
            speech = speech,
            arrived = arrived,
            snapped = snapped,
            distanceFromRouteM = bestDist,
            segmentIndex = best,
            alongM = along,
            aheadBearing = aheadBearing,
            nextPoi = nextPoi,
            nextPoiDistanceM = nextPoiDist,
        )
    }
}
