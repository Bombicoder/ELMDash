package fr.elmdash.core

import android.content.Context
import kotlinx.coroutines.*
import java.io.IOException

/** Boucle de lecture commune au téléphone et à la montre autonome. */
class ObdEngine(
    private val ctx: Context,
    private val settings: () -> Settings,
    private val pids: () -> Map<String, PidDef>,
    private val visible: () -> Set<String>,
    private val mac: () -> String,
    private val paused: () -> Boolean,
    private val onLink: () -> Unit,
    private val onValues: (Map<String, Double>) -> Unit
) {
    private fun set(l: Link, msg: String) { Telemetry.link.value = l; Telemetry.status.value = msg; onLink() }

    private fun scan(link: ObdLink) {
        val sup = link.supportedPids()
        val ids = Pids.standard.filter { it.cmd.length == 4 && it.cmd.startsWith("01") && sup.contains(it.cmd.substring(2).toInt(16)) }
            .map { it.id }.toMutableSet()
        ids += "BATT"
        if ("MAF" in ids) ids += listOf("FUEL_LH", "FUEL_L100", "FUEL_AVG", "FUEL_TRIPL", "TRIP_KM", "TRIP_MIN")
        Telemetry.supported.value = ids
    }

    private fun handleDtc(link: ObdLink, req: String) {
        Telemetry.dtcBusy.value = true
        try {
            if (req == "CLEAR") link.clearDtcs()
            Telemetry.dtcList.value = link.readDtcs()
            link.milStatus()?.let { Telemetry.mil.value = it.first; Telemetry.dtcCount.value = it.second }
        } finally { Telemetry.dtcRequest.value = null; Telemetry.dtcBusy.value = false }
    }

    suspend fun run() = coroutineScope {
        val link = ObdLink(); val fuel = FuelEngine(); val vals = HashMap<String, Double>()
        try {
            while (isActive) {
                val st = settings()
                if (paused()) { link.close(); set(Link.OFF, "Boîtier utilisé par l'autre appareil"); delay(1500); continue }
                link.emu = if (st.simulate) ElmEmulator() else null
                if (st.simulate && Sim.state.value.adapterAbsent) { set(Link.OFF, "Boîtier absent (simulation)"); delay(1000); continue }
                val dev = if (st.simulate) null else findBonded(ctx, mac())
                if (dev == null && !st.simulate) { set(Link.OFF, "Aucun boîtier : choisis-le dans Réglages"); delay(3000); continue }
                set(Link.CONNECTING, "Connexion au boîtier…")
                if (!(st.simulate || link.open(dev!!))) { set(Link.OFF, "Boîtier injoignable (branché ? à portée ?)"); delay(3000); continue }
                set(Link.ADAPTER_OK, "Boîtier connecté · recherche du calculateur…")
                if (!link.init(st.proto)) { link.close(); set(Link.ADAPTER_OK, "Boîtier OK mais calculateur muet (contact mis ?)"); delay(3000); continue }
                Telemetry.adapterInfo.value = link.info
                runCatching { scan(link) }      // liste des données lisibles, refaite à chaque connexion
                val okLink = if (st.simulate) Link.SIM else Link.LIVE
                val okMsg = if (st.simulate) "Simulation" else "Boîtier et calculateur OK"
                set(okLink, okMsg)
                Telemetry.reconnect.value = false
                var last = System.nanoTime(); var lastPush = 0L; var lastMil = 0L; var silent = 0
                try {
                    while (isActive && !paused() && !Telemetry.reconnect.value) {
                        val s = settings(); val pm = pids()
                        if (Telemetry.tripReset.value) { fuel.resetTrip(); Telemetry.tripReset.value = false }
                        if (Telemetry.scanRequest.value) { scan(link); Telemetry.scanRequest.value = false }
                        Telemetry.dtcRequest.value?.let { handleDtc(link, it) }
                        Telemetry.rawIn.value?.let { cmd ->
                            val resp = link.send(cmd, 4000).trim().replace("\r", "\n")
                            Telemetry.rawOut.value = (Telemetry.rawOut.value + "> $cmd\n$resp\n").takeLast(1500)
                            Telemetry.rawIn.value = null
                        }
                        val now = System.currentTimeMillis()
                        if (now - lastMil > 5000) {
                            lastMil = now
                            link.milStatus()?.let { Telemetry.mil.value = it.first; Telemetry.dtcCount.value = it.second }
                        }
                        val sup = Telemetry.supported.value
                        val want = visible().flatMap { Pids.deps(it, s) }.filter { id -> sup.isEmpty() || id in sup || pm[id]?.group == "Perso" }.toSet()
                        var got = 0
                        for (id in want) {
                            val p = pm[id] ?: continue
                            val v = link.read(p)
                            if (v != null) { vals[id] = v; got++ } else vals.remove(id)
                        }
                        val t = System.nanoTime(); val dt = (t - last) / 1e9; last = t
                        vals.putAll(fuel.compute(vals, s, dt))
                        if (st.simulate) Sim.state.value.overrides.forEach { (k, ov) -> if (Pids.virtual.any { it.id == k }) vals[k] = ov }
                        if (got > 0) { if (silent > 3 || Telemetry.link.value != okLink) set(okLink, okMsg); silent = 0 }
                        else if (want.isNotEmpty() && ++silent == 4) set(Link.ADAPTER_OK, "Boîtier OK · calculateur muet (contact ?)")
                        if (got == 0) delay(500)
                        if (now - lastPush > 250) { lastPush = now; onValues(HashMap(vals)) }
                    }
                } catch (e: IOException) { set(Link.OFF, "Connexion perdue · reconnexion…") } finally { link.close() }
                delay(1500)
            }
        } finally { link.close() }
    }
}
