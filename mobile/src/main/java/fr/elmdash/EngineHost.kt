package fr.elmdash

import android.content.Context
import fr.elmdash.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

/**
 * Héberge le moteur de lecture à l'échelle de l'application (et non du service) :
 * la simulation et l'envoi vers la montre fonctionnent même sans permission Bluetooth ni service de premier plan.
 */
object EngineHost {
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
        Telemetry.link.value = Link.OFF; Telemetry.status.value = "Erreur interne : ${e.javaClass.simpleName} ${e.message ?: ""}".take(120)
    })

    @Synchronized
    fun ensureStarted(ctx: Context) {
        if (job?.isActive == true) return
        val app = ctx.applicationContext
        job = scope.launch {
            val store = ConfigStore(app)
            var st = store.settings; var pm = store.allPids().associateBy { it.id }; var lastCfg = 0L
            fun refresh() { val n = System.currentTimeMillis(); if (n - lastCfg > 1500) { st = store.settings; pm = store.allPids().associateBy { it.id }; lastCfg = n } }
            fun meta() = mapOf(
                "_link" to Telemetry.link.value.ordinal.toDouble(),
                "_mil" to (if (Telemetry.mil.value) 1.0 else 0.0),
                "_dtc" to Telemetry.dtcCount.value.toDouble()
            )
            fun push(v: Map<String, Double>) {
                if (Telemetry.watchVisible.value.isNotEmpty()) runCatching { WearSync.pushValues(app, v + meta()) }
            }
            launch {
                combine(Telemetry.mil, Telemetry.dtcList) { m, l -> m to l }.collect { (m, l) -> runCatching { WearSync.pushDtc(app, m, l) } }
            }
            ObdEngine(app, { refresh(); st }, { refresh(); pm },
                { Telemetry.phoneVisible.value + Telemetry.watchVisible.value + Telemetry.editVisible.value },
                { st.deviceMac }, { st.watchDirect },
                { push(emptyMap()) }
            ) { v -> Telemetry.values.value = v; push(v) }.run()
        }
    }
}
