package ca.anicet.ebikenav

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Un point GPS (latitude, longitude en degrés). */
data class GeoPoint(val lat: Double, val lon: Double)

/** Résultat de la projection d'une position sur un segment du trajet. */
data class SegmentProjection(val t: Double, val distanceM: Double)

/** Petits calculs géographiques, sans dépendance externe. */
object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0
    private const val DEG = PI / 180.0

    /** Distance « à vol d'oiseau » entre deux points, en mètres (formule de haversine). */
    fun distance(a: GeoPoint, b: GeoPoint): Double {
        val dLat = (b.lat - a.lat) * DEG
        val dLon = (b.lon - a.lon) * DEG
        val h = sin(dLat / 2).pow(2) + cos(a.lat * DEG) * cos(b.lat * DEG) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /**
     * Projette la position p sur le segment [a, b].
     * t = 0 au point a, t = 1 au point b ; distanceM = écart entre p et le trajet.
     * Approximation plane locale : très précise à l'échelle d'une rue.
     */
    fun project(p: GeoPoint, a: GeoPoint, b: GeoPoint): SegmentProjection {
        val kx = cos(p.lat * DEG) * EARTH_RADIUS_M * DEG
        val ky = EARTH_RADIUS_M * DEG
        val ax = (a.lon - p.lon) * kx
        val ay = (a.lat - p.lat) * ky
        val bx = (b.lon - p.lon) * kx
        val by = (b.lat - p.lat) * ky
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        var t = if (len2 == 0.0) 0.0 else (-ax * dx - ay * dy) / len2
        if (t < 0.0) t = 0.0
        if (t > 1.0) t = 1.0
        val cx = ax + t * dx
        val cy = ay + t * dy
        return SegmentProjection(t, sqrt(cx * cx + cy * cy))
    }

    /** Décode une « polyline » encodée (Valhalla utilise une précision de 6 décimales). */
    fun decodePolyline(encoded: String, precision: Int = 6): List<GeoPoint> {
        val factor = 10.0.pow(precision)
        val out = ArrayList<GeoPoint>()
        var index = 0
        var lat = 0L
        var lon = 0L
        while (index < encoded.length) {
            var result = 0L
            var shift = 0
            var b: Int
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20 && index < encoded.length)
            lat += if ((result and 1L) != 0L) (result shr 1).inv() else (result shr 1)

            result = 0L
            shift = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20 && index < encoded.length)
            lon += if ((result and 1L) != 0L) (result shr 1).inv() else (result shr 1)

            out.add(GeoPoint(lat / factor, lon / factor))
        }
        return out
    }

    /** Cap (en degrés, 0 = nord, 90 = est) pour aller de a vers b. */
    fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val phi1 = a.lat * DEG
        val phi2 = b.lat * DEG
        val dLon = (b.lon - a.lon) * DEG
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        return (atan2(y, x) / DEG + 360.0) % 360.0
    }

    /** Distances cumulées (en mètres) depuis le début du trajet, pour chaque point. */
    fun cumulative(points: List<GeoPoint>): DoubleArray {
        val cum = DoubleArray(points.size)
        for (i in 1 until points.size) {
            cum[i] = cum[i - 1] + distance(points[i - 1], points[i])
        }
        return cum
    }
}
