package ca.anicet.ebikenav

import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow

/** Ce que l'écran doit afficher pendant la navigation. */
data class NavSnapshot(
    val active: Boolean = false,
    val route: Route? = null,
    val routeVersion: Int = 0,
    val distanceToNextM: Double = 0.0,
    val instruction: String = "",
    val maneuverType: Int = 0,
    val remainingM: Double = 0.0,
    val remainingSec: Double = 0.0,
    val speedKmh: Double = 0.0,
    val status: String? = null,
    val arrived: Boolean = false,
    /** Position du curseur (recalée sur la route quand on est dessus), avec son cap. */
    val location: Location? = null,
    val simulated: Boolean = false,
    /** Cap de la carte (direction de la route devant soi). */
    val cameraBearing: Double = 0.0,
    /** Pour griser la partie déjà parcourue. */
    val segmentIndex: Int = 0,
    val onRoute: Boolean = true,
    /** Prochain feu / obstacle / signalement. */
    val nextPoi: RoutePoi? = null,
    val nextPoiDistanceM: Double = 0.0,
    val voiceOn: Boolean = true,
)

/** Lien partagé entre le service de navigation (qui tourne écran éteint) et l'écran. */
object NavState {
    val state = MutableStateFlow(NavSnapshot())
}
