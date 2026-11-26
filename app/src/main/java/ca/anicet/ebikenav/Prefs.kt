package ca.anicet.ebikenav

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Profil du vélo (idée reprise de la V0.3, rendue utilisable sans clé payante).
 * Valeurs par défaut : SAMEBIKE 48 V – 15 Ah.
 */
data class BikeProfile(
    val volts: Double = 48.0,
    val ampHours: Double = 15.0,
    val chargePct: Double = 100.0,
    val whPerKm: Double = 14.0,
    val reservePct: Double = 15.0,
    val massKg: Double = 110.0, // vélo (~28 kg) + cycliste + sac
) {
    val capacityWh: Double get() = volts * ampHours

    /**
     * Énergie d'un trajet en Wh :
     * consommation au km + énergie pour monter les côtes (rendement moteur ~75 %),
     * majorée de 30 % par temps froid (la batterie lithium perd de l'autonomie).
     */
    fun tripWh(km: Double, ascentM: Double?, cold: Boolean): Double {
        val flat = max(0.0, km) * whPerKm
        val climb = max(0.0, ascentM ?: 0.0) * massKg * 9.81 / 3600.0 / 0.75
        return (flat + climb) * (if (cold) 1.3 else 1.0)
    }

    fun tripPercent(km: Double, ascentM: Double?, cold: Boolean): Int =
        ceil(tripWh(km, ascentM, cold) / capacityWh * 100.0).toInt().coerceAtLeast(1)

    /** Texte pour l'aperçu du trajet. */
    fun summary(km: Double, ascentM: Double?, cold: Boolean): String {
        val used = tripPercent(km, ascentM, cold)
        val left = (chargePct - used).roundToInt()
        val climb = ascentM?.let { " · ↗ +${it.roundToInt()} m" } ?: ""
        val coldNote = if (cold) " · froid +30 %" else ""
        val base = "🔋 ≈ $used % utilisés → il restera ≈ ${left.coerceAtLeast(0)} %$climb$coldNote"
        return if (left < reservePct) "$base\n⚠ Autonomie juste (réserve ${reservePct.roundToInt()} %)" else base
    }

    companion object {
        /** Novembre à mars : saison froide à Montréal. */
        fun isColdSeason(month: Int = java.time.LocalDate.now().monthValue): Boolean = month >= 11 || month <= 3
    }
}

/** Sauvegarde du profil et des réglages sur le téléphone. */
object Settings {
    private const val FILE = "app"

    fun profile(ctx: Context): BikeProfile {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return BikeProfile(
            volts = p.getFloat("volts", 48f).toDouble(),
            ampHours = p.getFloat("ah", 15f).toDouble(),
            chargePct = p.getFloat("charge", 100f).toDouble(),
            whPerKm = p.getFloat("whkm", 14f).toDouble(),
            reservePct = p.getFloat("reserve", 15f).toDouble(),
            massKg = p.getFloat("mass", 110f).toDouble(),
        )
    }

    fun saveProfile(ctx: Context, b: BikeProfile) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putFloat("volts", b.volts.toFloat())
            .putFloat("ah", b.ampHours.toFloat())
            .putFloat("charge", b.chargePct.toFloat())
            .putFloat("whkm", b.whPerKm.toFloat())
            .putFloat("reserve", b.reservePct.toFloat())
            .putFloat("mass", b.massKg.toFloat())
            .apply()
    }

    fun voiceOn(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("voice", true)

    fun setVoice(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("voice", on).apply()
    }
}

/**
 * Signalements personnels (nid-de-poule, travaux, glace…), gardés sur le téléphone
 * et annoncés à chaque passage. Ils ne sont envoyés nulle part.
 */
object ReportStore {
    private const val FILE = "reports"
    private const val KEY = "list"

    fun all(ctx: Context): List<RawPoi> {
        val text = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val arr = try { JSONArray(text) } catch (e: Exception) { JSONArray() }
        val out = ArrayList<RawPoi>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val kind = try { PoiKind.valueOf(o.optString("kind")) } catch (e: Exception) { continue }
            out.add(RawPoi(kind, GeoPoint(o.optDouble("lat"), o.optDouble("lon"))))
        }
        return out
    }

    fun add(ctx: Context, kind: PoiKind, point: GeoPoint) {
        val prefs = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val arr = try { JSONArray(prefs.getString(KEY, "[]")) } catch (e: Exception) { JSONArray() }
        arr.put(
            JSONObject().put("kind", kind.name).put("lat", point.lat).put("lon", point.lon)
                .put("date", System.currentTimeMillis())
        )
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}

/** Charge les feux/arrêts/obstacles (Internet) + les signalements personnels pour un trajet. */
object PoiLoader {
    suspend fun load(ctx: Context, route: Route): List<RoutePoi> {
        val osm = try { OverpassClient.fetchAlong(route) } catch (e: Exception) { emptyList() }
        return PoiTools.attach(route, osm + ReportStore.all(ctx))
    }
}
