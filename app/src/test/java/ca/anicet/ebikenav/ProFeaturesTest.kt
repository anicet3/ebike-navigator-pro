package ca.anicet.ebikenav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests des fonctions ajoutées dans la version Pro. */
class ProFeaturesTest {

    private val start = GeoPoint(45.5000, -73.6000)
    private val turn = GeoPoint(45.5050, -73.6000)
    private val end = GeoPoint(45.5050, -73.5950)

    private fun route(pois: List<RoutePoi> = emptyList()) = Route(
        points = listOf(start, turn, end),
        maneuvers = listOf(
            Maneuver(1, "Roulez vers le nord.", null, "Roulez vers le nord.", null, 0),
            Maneuver(10, "Tournez à droite.", "Tournez à droite.", "Tournez à droite.", null, 1),
            Maneuver(4, "Arrivée.", null, "Vous êtes arrivé.", null, 2),
        ),
        lengthKm = 0.946, timeSec = 160.0, mode = RouteMode.BALANCED,
        destination = end, destinationName = "Test", pois = pois,
    )

    @Test
    fun curseur_recaleSurLaRoute_etCarteOrienteeVersLeNord() {
        val engine = NavigationEngine(route())
        // GPS à ~15 m à l'est de la route (dérive typique en ville)
        val u = engine.onLocation(GeoPoint(45.5010, -73.59981), 10f, 6f)
        assertEquals("le curseur doit être sur la route", -73.6000, u.snapped.lon, 1e-6)
        assertEquals(45.5010, u.snapped.lat, 1e-5)
        assertTrue("écart ${u.distanceFromRouteM}", u.distanceFromRouteM in 10.0..20.0)
        // On roule vers le nord → la carte doit être orientée à ~0°
        assertTrue("cap ${u.aheadBearing}", u.aheadBearing < 2.0 || u.aheadBearing > 358.0)
    }

    @Test
    fun avantLeVirage_laCarteSOrienteDejaVersLEst() {
        val engine = NavigationEngine(route())
        // 10 m avant le virage : la route « devant » (30 m) part déjà vers l'est
        val u = engine.onLocation(GeoPoint(45.50491, -73.6000), 5f, 6f)
        assertTrue("cap ${u.aheadBearing}", u.aheadBearing in 30.0..95.0)
    }

    @Test
    fun feuDeCirculation_annonceUneSeuleFois() {
        // Feu situé à 300 m du départ
        val light = RoutePoi(PoiKind.SIGNALS, GeoPoint(45.5027, -73.6000), 300.0)
        val engine = NavigationEngine(route(listOf(light)))
        val spoken = ArrayList<String>()
        var lat = 45.5000
        while (lat < 45.5030) {
            spoken += engine.onLocation(GeoPoint(lat, -73.6000), 5f, 6f).speech
            lat += 0.00005 // ~5,5 m
        }
        val lightSpeech = spoken.filter { it.startsWith("Feu de circulation") }
        assertEquals("annonces : $spoken", 1, lightSpeech.size)
    }

    @Test
    fun prochainObstacle_afficheDansLeBandeau() {
        val bollard = RoutePoi(PoiKind.BARRIER, GeoPoint(45.5040, -73.6000), 445.0)
        val engine = NavigationEngine(route(listOf(bollard)))
        val u = engine.onLocation(GeoPoint(45.5010, -73.6000), 5f, 6f)
        assertEquals(PoiKind.BARRIER, u.nextPoi?.kind)
        assertEquals(334.0, u.nextPoiDistanceM, 5.0)
    }

    @Test
    fun poisAjoutesEnRoute_neRepetentPasCeuxDejaPasses() {
        val engine = NavigationEngine(route())
        engine.onLocation(GeoPoint(45.5030, -73.6000), 5f, 6f) // déjà à ~334 m
        val passed = RoutePoi(PoiKind.STOP, GeoPoint(45.5010, -73.6000), 111.0)
        engine.setPois(listOf(passed))
        val u = engine.onLocation(GeoPoint(45.5031, -73.6000), 5f, 6f)
        assertTrue(u.speech.none { it.startsWith("Arrêt") })
    }

    @Test
    fun attach_garde_seulementLesElementsSurLeTrajet_etFusionneLesDoublons() {
        val raw = listOf(
            RawPoi(PoiKind.SIGNALS, GeoPoint(45.50270, -73.60005)), // sur la route
            RawPoi(PoiKind.SIGNALS, GeoPoint(45.50275, -73.59995)), // même carrefour → fusionné
            RawPoi(PoiKind.STOP, GeoPoint(45.5020, -73.5970)),       // à 230 m → ignoré
        )
        val list = PoiTools.attach(route(), raw)
        assertEquals(1, list.size)
        assertEquals(PoiKind.SIGNALS, list[0].kind)
        assertEquals(300.0, list[0].alongM, 10.0)
    }

    @Test
    fun batterie_samebike48V15Ah() {
        val p = BikeProfile(volts = 48.0, ampHours = 15.0, chargePct = 80.0, whPerKm = 14.0, reservePct = 15.0, massKg = 110.0)
        assertEquals(720.0, p.capacityWh, 0.01)
        // 10 km à plat, temps doux : 140 Wh → 20 %
        assertEquals(20, p.tripPercent(10.0, 0.0, cold = false))
        // 50 m de montée ajoutent ~20 Wh
        assertEquals(160.0, p.tripWh(10.0, 50.0, cold = false), 1.0)
        // Froid : +30 %
        assertEquals(182.0, p.tripWh(10.0, 0.0, cold = true), 0.5)
        // 40 km avec 80 % de charge → sous la réserve → avertissement
        assertTrue(p.summary(40.0, 0.0, cold = false).contains("Autonomie juste"))
    }

    @Test
    fun saisonFroide_novembreAMars() {
        assertTrue(BikeProfile.isColdSeason(11))
        assertTrue(BikeProfile.isColdSeason(2))
        assertTrue(!BikeProfile.isColdSeason(7))
    }
}
