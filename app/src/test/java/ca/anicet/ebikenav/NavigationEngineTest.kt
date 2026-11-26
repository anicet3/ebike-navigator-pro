package ca.anicet.ebikenav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Trajet d'essai : ~556 m vers le nord, virage à droite, ~390 m vers l'est.
 * On « roule » virtuellement dessus et on vérifie ce que l'application dirait.
 */
class NavigationEngineTest {

    private val start = GeoPoint(45.5000, -73.6000)
    private val turn = GeoPoint(45.5050, -73.6000)
    private val end = GeoPoint(45.5050, -73.5950)

    private fun testRoute() = Route(
        points = listOf(start, turn, end),
        maneuvers = listOf(
            Maneuver(1, "Roulez vers le nord.", null, "Roulez vers le nord.", "Continuez pendant 550 mètres.", 0),
            Maneuver(10, "Tournez à droite sur Rue Test.", "Tournez à droite sur Rue Test.", "Tournez à droite sur Rue Test.", "Continuez pendant 400 mètres.", 1),
            Maneuver(4, "Vous êtes arrivé.", null, "Vous êtes arrivé à votre destination.", null, 2),
        ),
        lengthKm = 0.946,
        timeSec = 160.0,
        mode = RouteMode.BALANCED,
        destination = end,
        destinationName = "Test",
    )

    /** Positions tous les `step` mètres le long du trajet. */
    private fun ride(route: Route, step: Double): List<GeoPoint> {
        val pts = route.points
        val cum = Geo.cumulative(pts)
        val out = ArrayList<GeoPoint>()
        var d = 0.0
        var seg = 0
        while (d <= cum.last()) {
            while (seg < cum.size - 2 && cum[seg + 1] < d) seg++
            val t = (d - cum[seg]) / (cum[seg + 1] - cum[seg])
            val a = pts[seg]
            val b = pts[seg + 1]
            out.add(GeoPoint(a.lat + t * (b.lat - a.lat), a.lon + t * (b.lon - a.lon)))
            d += step
        }
        out.add(pts.last())
        return out
    }

    @Test
    fun trajetComplet_annoncesDansLeBonOrdre_puisArrivee() {
        val route = testRoute()
        val engine = NavigationEngine(route)
        val spoken = ArrayList<String>()
        var arrived = false
        for (p in ride(route, 6.0)) {
            val u = engine.onLocation(p, 5f, 6f)
            spoken.addAll(u.speech)
            assertFalse("ne doit jamais se croire hors trajet", u.offRoute)
            if (u.arrived) { arrived = true; break }
        }
        val alert = spoken.indexOfFirst { it.startsWith("Dans ") && it.contains("tournez à droite") }
        val pre = spoken.indexOfFirst { it == "Tournez à droite sur Rue Test." }
        val post = spoken.indexOfFirst { it.startsWith("Continuez pendant 400") }
        val arrival = spoken.indexOfFirst { it.contains("arrivé") }
        assertTrue("phrase d'arrivée incohérente : $spoken", spoken.none { it.startsWith("Dans") && it.contains("arrivé") })
        assertTrue("annonce anticipée manquante : $spoken", alert >= 0)
        assertTrue("annonce du virage manquante : $spoken", pre > alert)
        assertTrue("« continuez » manquant : $spoken", post > pre)
        assertTrue("arrivée non annoncée : $spoken", arrival > post)
        assertTrue("arrivée non détectée", arrived)
        // Chaque phrase n'est dite qu'une fois
        assertTrue("répétitions : $spoken", spoken.size == spoken.distinct().size)
    }

    @Test
    fun sortieDeTrajet_detecteeApres3Positions() {
        val engine = NavigationEngine(testRoute())
        engine.onLocation(GeoPoint(45.5010, -73.6000), 5f, 6f) // sur le trajet
        val farAway = GeoPoint(45.5010, -73.5970) // ~230 m à l'est
        val u1 = engine.onLocation(farAway, 5f, 6f)
        val u2 = engine.onLocation(farAway, 5f, 6f)
        val u3 = engine.onLocation(farAway, 5f, 6f)
        assertFalse(u1.offRoute)
        assertFalse(u2.offRoute)
        assertTrue(u3.offRoute)
    }

    @Test
    fun gpsImprecis_neDeclenchePasDeFauxRecalcul() {
        val engine = NavigationEngine(testRoute())
        val slightlyOff = GeoPoint(45.5010, -73.59955) // ~35 m à côté
        repeat(5) {
            val u = engine.onLocation(slightlyOff, 40f, 6f) // précision GPS médiocre : 40 m
            assertFalse(u.offRoute)
        }
    }
}
