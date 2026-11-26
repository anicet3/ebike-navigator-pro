package ca.anicet.ebikenav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {

    @Test
    fun decodePolyline_exempleOfficiel() {
        // Exemple de référence de l'algorithme (précision 5)
        val pts = Geo.decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5)
        assertEquals(3, pts.size)
        assertEquals(38.5, pts[0].lat, 1e-6)
        assertEquals(-120.2, pts[0].lon, 1e-6)
        assertEquals(43.252, pts[2].lat, 1e-6)
        assertEquals(-126.453, pts[2].lon, 1e-6)
    }

    @Test
    fun distance_unMillemeDeDegreDeLatitude_vaut111m() {
        val d = Geo.distance(GeoPoint(45.500, -73.600), GeoPoint(45.501, -73.600))
        assertEquals(111.2, d, 0.5)
    }

    @Test
    fun projection_pointACoteDuSegment() {
        val a = GeoPoint(45.500, -73.600)
        val b = GeoPoint(45.501, -73.600)
        // ~39 m à l'est du milieu du segment
        val p = GeoPoint(45.5005, -73.5995)
        val pr = Geo.project(p, a, b)
        assertEquals(0.5, pr.t, 0.02)
        assertTrue("écart ${pr.distanceM}", pr.distanceM in 35.0..43.0)
    }

    @Test
    fun cap_versLEst_vaut90() {
        val b = Geo.bearing(GeoPoint(45.5, -73.6), GeoPoint(45.5, -73.59))
        assertEquals(90.0, b, 0.5)
    }
}
