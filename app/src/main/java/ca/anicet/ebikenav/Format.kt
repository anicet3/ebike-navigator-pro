package ca.anicet.ebikenav

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Mise en forme des distances, durées et flèches (affichage + voix). */
object Format {
    private val FR = Locale.CANADA_FRENCH

    /** Pour l'écran : « 180 m », « 1,2 km ». */
    fun displayDistance(meters: Double): String = when {
        meters < 1000 -> "${roundTo(meters, if (meters < 100) 10 else 50)} m"
        else -> String.format(FR, "%.1f km", meters / 1000.0)
    }

    /** Pour la voix : « 200 mètres », « 1,5 kilomètre ». */
    fun spokenDistance(meters: Double): String = when {
        meters < 1000 -> "${roundTo(meters, if (meters < 100) 10 else 50)} mètres"
        meters < 2000 -> String.format(FR, "%.1f kilomètre", meters / 1000.0)
        else -> String.format(FR, "%.1f kilomètres", meters / 1000.0)
    }

    /** « 12 min » ou « 1 h 05 ». */
    fun duration(seconds: Double): String {
        val totalMin = ceil(seconds / 60.0).toInt().coerceAtLeast(1)
        return if (totalMin < 60) "$totalMin min" else String.format(FR, "%d h %02d", totalMin / 60, totalMin % 60)
    }

    /** Heure d'arrivée estimée, ex. « 08:12 ». */
    fun eta(secondsFromNow: Double): String =
        LocalTime.now().plusSeconds(secondsFromNow.toLong()).format(DateTimeFormatter.ofPattern("HH:mm"))

    /** Flèche selon le type de manœuvre Valhalla. */
    fun arrowFor(type: Int): String = when (type) {
        1, 2, 3 -> "⬆"
        4, 5, 6 -> "🏁"
        9, 18, 20, 23 -> "↗"
        10 -> "➡"
        11 -> "↘"
        12, 13 -> "↩"
        14 -> "↙"
        15 -> "⬅"
        16, 19, 21, 24 -> "↖"
        26, 27 -> "⟳"
        else -> "⬆"
    }

    /** Met la première lettre en minuscule (pour « Dans 200 mètres, tournez… »). */
    fun lowerFirst(text: String): String =
        if (text.isEmpty()) text else text.substring(0, 1).lowercase(FR) + text.substring(1)

    private fun roundTo(value: Double, step: Int): Int =
        ((value / step).roundToInt() * step).coerceAtLeast(step)
}
