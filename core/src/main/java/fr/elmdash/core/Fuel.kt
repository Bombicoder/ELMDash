package fr.elmdash.core

import kotlin.math.max

/**
 * Maths carburant / éthanol. e = fraction volumique d'éthanol (0..1).
 * AFR stœchio massiques : essence ≈ 14,7 ; éthanol ≈ 9,0. Masses volumiques : 0,745 / 0,789 kg/L.
 */
object FuelMath {
    const val AFR_GAS = 14.7; const val AFR_ETH = 9.0
    const val RHO_GAS = 0.745; const val RHO_ETH = 0.789

    fun rho(e: Double) = (1 - e) * RHO_GAS + e * RHO_ETH
    fun afr(e: Double): Double {
        val mg = (1 - e) * RHO_GAS; val me = e * RHO_ETH
        return (mg * AFR_GAS + me * AFR_ETH) / (mg + me)
    }
    private fun litresPerAir(e: Double) = 1.0 / (afr(e) * rho(e))

    /** Enrichissement volumique du mélange [e] par rapport au carburant de référence [ref] (celui que l'ECU croit brûler). */
    fun enrichment(e: Double, ref: Double) = litresPerAir(e) / litresPerAir(ref)
}

/** Consommation instantanée ET cumulée depuis le début du trajet (remise à zéro à la demande). */
class FuelEngine {
    private var tripL = 0.0
    private var tripKm = 0.0
    private var tripSec = 0.0

    fun resetTrip() { tripL = 0.0; tripKm = 0.0; tripSec = 0.0 }

    fun compute(v: Map<String, Double>, s: Settings, dtSec: Double): Map<String, Double> {
        val out = HashMap<String, Double>()
        val e = s.ethanolPct / 100.0
        val rpm = v["RPM"]; val speed = v["SPEED"] ?: 0.0
        val dt = dtSec.coerceIn(0.0, 2.0)          // un trou de connexion ne fausse pas le trajet
        var lh: Double? = null

        if (s.fuelMode == FuelMode.INJECTOR) {
            val pw = v["INJ_PW"]
            if (pw != null && rpm != null) {
                val k = if (s.boxEnabled) FuelMath.enrichment(e, s.ecuRefEthanol / 100.0) else 1.0
                val ccPerSec = s.cylinders * (rpm / 120.0) * (max(0.0, pw - s.deadTimeMs) / 1000.0) * (s.injectorCcMin / 60.0)
                lh = ccPerSec * k * 3.6
            }
        } else {
            val maf = v["MAF"]
            if (maf != null) {
                val lam = (v["LAMBDA"] ?: 1.0).coerceIn(0.6, 1.5)
                lh = maf / (FuelMath.afr(e) * lam) / FuelMath.rho(e) * 3.6   // g/s ÷ g/cm³ = cm³/s → ×3,6 = L/h
            }
        }
        if (lh != null) {
            out["FUEL_RAW_LH"] = lh                        // débit brut, sert à l'étalonnage
            lh *= s.fuelCal.coerceIn(0.3, 3.0)              // débit corrigé par l'étalonnage plein à plein
            out["FUEL_LH"] = lh
            out["FUEL_L100"] = if (speed > 3) (lh / speed * 100.0).coerceAtMost(99.0) else 0.0
            tripL += lh * dt / 3600.0; tripKm += speed * dt / 3600.0; tripSec += dt
            out["FUEL_TRIPL"] = tripL
            out["FUEL_AVG"] = if (tripKm > 0.05) tripL / tripKm * 100.0 else 0.0
            out["TRIP_KM"] = tripKm
            out["TRIP_MIN"] = tripSec / 60.0
        }
        return out
    }
}
