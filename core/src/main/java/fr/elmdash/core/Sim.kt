package fr.elmdash.core

import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin

enum class Scenario(val label: String) {
    IDLE("Ralenti"), CITY("Ville"), ROAD("Route"), HIGHWAY("Autoroute"),
    COLD("Démarrage à froid"), OVERHEAT("Surchauffe"), MANUAL("Manuel")
}

/** Tout ce que le simulateur peut faire varier. En scénario MANUEL, ce sont les curseurs qui pilotent le véhicule. */
data class SimState(
    val scenario: Scenario = Scenario.CITY,
    val rpm: Double = 2500.0, val speed: Double = 60.0, val load: Double = 50.0,
    val coolant: Double = 88.0, val oil: Double = 94.0, val volt: Double = 13.8, val lambdaOffset: Double = 0.0,
    val mil: Boolean = false, val dtcs: List<String> = emptyList(),
    val adapterAbsent: Boolean = false, val ecuMute: Boolean = false,
    val oldEcu: Boolean = false            // vieux calculateur : très peu de PID déclarés (comme une voiture de 1997)
)

object Sim {
    val state = MutableStateFlow(SimState())
    @Volatile var t0 = System.nanoTime()
    fun update(f: (SimState) -> SimState) {
        val old = state.value; val n = f(old)
        if (n.scenario != old.scenario) t0 = System.nanoTime()
        state.value = n
    }
    fun elapsed() = (System.nanoTime() - t0) / 1e9
}

class SimVehicle(val rpm: Double, val speed: Double, val load: Double, val coolant: Double, val oil: Double)

fun simVehicle(s: SimState, tScn: Double, t: Double): SimVehicle {
    var spd = 0.0; var load = 0.10
    when (s.scenario) {
        Scenario.IDLE, Scenario.COLD -> { spd = 0.0; load = 0.10 }
        Scenario.CITY -> { spd = max(0.0, 28 + 26 * sin(t * 0.35)); load = 0.22 + 0.18 * cos(t * 0.35) }
        Scenario.ROAD, Scenario.OVERHEAT -> { spd = 85 + 6 * sin(t * 0.2); load = 0.35 + 0.05 * sin(t * 0.2) }
        Scenario.HIGHWAY -> { spd = 130.0; load = 0.42 }
        Scenario.MANUAL -> { spd = s.speed; load = s.load / 100 }
    }
    val rpm = if (s.scenario == Scenario.MANUAL) s.rpm
        else if (spd < 1) (if (s.scenario == Scenario.COLD) 1100.0 else 820.0) else 900 + spd * 30 + load * 900
    val cool = when (s.scenario) {
        Scenario.COLD -> min(92.0, 14 + tScn * 0.9)
        Scenario.OVERHEAT -> min(120.0, 95 + tScn * 0.5)
        else -> s.coolant
    }
    val oil = when (s.scenario) {
        Scenario.COLD -> min(95.0, 12 + tScn * 0.45)
        Scenario.OVERHEAT -> cool + 8
        else -> s.oil
    }
    return SimVehicle(rpm, spd, load, cool, oil)
}

/** Simule un ELM327 + calculateur : mêmes réponses hexadécimales qu'un vrai adaptateur, pilotées par [Sim]. */
class ElmEmulator {
    private val oldSet = setOf("RPM", "SPEED", "LOAD", "THROTTLE", "COOLANT", "IAT", "MAP", "MAF", "STFT1", "LTFT1", "O2_B1S1", "O2_B1S2", "TIMING")

    private fun supported(s: SimState) =
        Pids.standard.filter { it.cmd.length == 4 && it.cmd.startsWith("01") && (!s.oldEcu || it.id in oldSet) }

    fun respond(raw: String): String {
        val s = Sim.state.value
        val c = raw.trim().uppercase().replace(" ", "")
        if (c.startsWith("AT")) return when (c) {
            "ATZ" -> "ELM327 v1.5"; "ATRV" -> String.format(Locale.US, "%.1fV", s.volt)
            "ATDP" -> "AUTO, ISO 9141-2"; else -> "OK"
        }
        if (s.ecuMute) return "NO DATA"
        if (c.length < 4 && c != "03" && c != "04") return "?"
        val sup = supported(s).associateBy { it.cmd.substring(2).toInt(16) }
        if (Regex("01(00|20|40|60|80|A0|C0)").matches(c)) {
            val base = c.substring(2).toInt(16); var m = 0L
            sup.keys.forEach { n -> if (n > base && n <= base + 32) m = m or (1L shl (32 - (n - base))) }
            if (sup.keys.any { it > base + 32 }) m = m or 1L
            return "41" + c.substring(2) + "%08X".format(m)
        }
        if (c == "0101") return "4101" + "%02X".format((if (s.mil) 0x80 else 0) + s.dtcs.size) + "076500"
        if (c == "03") {
            if (s.dtcs.isEmpty()) return "430000000000"
            return s.dtcs.map { Dtc.encode(it) }.chunked(3).joinToString("\r") { grp -> "43" + grp.joinToString("") + "0000".repeat(3 - grp.size) }
        }
        if (c == "04") { Sim.update { it.copy(mil = false, dtcs = emptyList()) }; return "44" }

        val t = System.nanoTime() / 1e9
        val v = simVehicle(s, Sim.elapsed(), t)
        val mp = min(100.0, 30 + 75 * v.load)
        val maf = 1.6 * v.rpm / 120 * 1.184 * 0.9 * mp / 101.3
        val lamCmd = (if (v.load > 0.85) 0.86 else 1.0) + s.lambdaOffset
        val lamMeas = lamCmd + 0.02 * sin(t * 2)
        val known = mapOf(
            "RPM" to v.rpm, "SPEED" to v.speed, "LOAD" to v.load * 100, "THROTTLE" to v.load * 90, "COOLANT" to v.coolant, "OIL" to v.oil,
            "IAT" to 30 + v.load * 18, "MAP" to mp, "BARO" to 101.0, "MAF" to maf, "LAMBDA" to lamCmd, "LAMBDA_B1" to lamMeas,
            "O2_B1S1" to 0.45 + 0.4 * sin(t * 3), "O2_B1S2" to 0.65 + 0.05 * sin(t * 1.3), "STFT1" to 3 * sin(t * 1.1), "LTFT1" to 2.0,
            "TIMING" to 10 + v.load * 25, "VOLT" to s.volt, "FUELLEVEL" to 62.0, "AMBIENT" to 14.0
        )
        val p = sup[c.substring(2).toIntOrNull(16) ?: -1]?.takeIf { it.cmd == c }
        val value: Double; val bytes: Int; val scale: Double; val offset: Double
        if (p != null) {
            value = known[p.id] ?: (p.min + (p.max - p.min) * (0.5 + 0.3 * sin(t * 0.3 + p.id.length)))
            bytes = p.bytes; scale = p.scale; offset = p.offset
        } else if (c == "221234") {
            val r = 0.05
            val cc = maf / (FuelMath.afr(r) * lamCmd * FuelMath.rho(r))
            value = cc / (4 * v.rpm / 120) / (200.0 / 60) * 1000 + 0.8; bytes = 2; scale = 0.001; offset = 0.0
        } else return "NO DATA"
        val raw2 = ((value - offset) / scale).roundToLong().coerceAtLeast(0)
        val prefix = "%02X".format(c.substring(0, 2).toInt(16) + 0x40) + c.substring(2)
        return prefix + "%0${bytes * 2}X".format(raw2)
    }
}
