@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package fr.elmdash

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.elmdash.core.*
import kotlin.math.roundToInt

@Composable
private fun SimSlider(label: String, value: Double, range: ClosedFloatingPointRange<Float>, enabled: Boolean, fmt: String, onChange: (Double) -> Unit) {
    Column {
        Text("$label : " + fmt.format(value) + if (!enabled) "  (piloté par le scénario)" else "", fontSize = 13.sp, color = if (enabled) androidx.compose.ui.graphics.Color.White else GaugeGrey)
        Slider(value.toFloat().coerceIn(range.start, range.endInclusive), { onChange(it.toDouble()) }, valueRange = range, enabled = enabled)
    }
}

/** Simulateur complet : tout ce que fait la voiture est piloté ici, et la montre réagit en direct. */
@Composable
fun SimScreen(app: AppState, onBack: () -> Unit) {
    val sim by Sim.state.collectAsState()
    val link by Telemetry.link.collectAsState(); val status by Telemetry.status.collectAsState()
    val s = app.settings
    val manual = sim.scenario == Scenario.MANUAL
    val tempAuto = sim.scenario == Scenario.COLD || sim.scenario == Scenario.OVERHEAT
    val presetCodes = listOf("P0133", "P0171", "P0300", "P0420", "P0505", "P0118")

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onBack) { Text("← Retour") }
            Spacer(Modifier.width(12.dp)); Text("Simulateur", fontSize = 20.sp)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Section("Activation") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(s.simulate, { app.setSettings(s.copy(simulate = it)); Telemetry.reconnect.value = true }); Spacer(Modifier.width(8.dp))
                    Text(if (s.simulate) "Simulation active : les données viennent d'ici" else "Simulation désactivée (voiture réelle)", fontSize = 13.sp)
                }
                LinkLeds(link, 14.dp, labels = true)
                Text(status, fontSize = 12.sp, color = GaugeGrey)
                Text("La montre reçoit exactement les mêmes valeurs : ouvre son appli pour voir comment elle réagit.", fontSize = 12.sp, color = GaugeGrey)
            }
            Section("Scénario") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Scenario.values().forEach { sc -> FilterChip(selected = sim.scenario == sc, onClick = { Sim.update { it.copy(scenario = sc) } }, label = { Text(sc.label) }) }
                }
                if (tempAuto) Text(if (sim.scenario == Scenario.COLD) "Moteur à 14 °C qui monte d'environ 1 °C/s : tu verras la jauge de chauffe, le clignotement vert à 'prêt' et la vibration."
                    else "L'eau monte jusqu'à 120 °C : clignotement rouge + vibration longue.", fontSize = 12.sp, color = GaugeGrey)
                OutlinedButton({ Sim.t0 = System.nanoTime(); Telemetry.tripReset.value = true }) { Text("Redémarrer le scénario + trajet à zéro") }
            }
            Section("Véhicule") {
                SimSlider("Régime", sim.rpm, 600f..7000f, manual, "%.0f tr/min") { v -> Sim.update { it.copy(rpm = v) } }
                SimSlider("Vitesse", sim.speed, 0f..220f, manual, "%.0f km/h") { v -> Sim.update { it.copy(speed = v) } }
                SimSlider("Charge moteur", sim.load, 0f..100f, manual, "%.0f %%") { v -> Sim.update { it.copy(load = v) } }
                SimSlider("Température d'eau", sim.coolant, 10f..130f, !tempAuto, "%.0f °C") { v -> Sim.update { it.copy(coolant = v) } }
                SimSlider("Température d'huile", sim.oil, 10f..150f, !tempAuto, "%.0f °C") { v -> Sim.update { it.copy(oil = v) } }
                SimSlider("Tension batterie", sim.volt, 9f..15.5f, true, "%.1f V") { v -> Sim.update { it.copy(volt = v) } }
                SimSlider("Écart de lambda (mélange riche / pauvre)", sim.lambdaOffset, -0.2f..0.2f, true, "%+.2f") { v -> Sim.update { it.copy(lambdaOffset = v) } }
            }
            Section("Codes défaut") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(sim.mil, { on -> Sim.update { it.copy(mil = on) } }); Spacer(Modifier.width(8.dp)); Text("Témoin moteur allumé", fontSize = 13.sp)
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presetCodes.forEach { code ->
                        FilterChip(selected = code in sim.dtcs, label = { Text(code) }, onClick = {
                            Sim.update { st ->
                                val n = if (code in st.dtcs) st.dtcs - code else st.dtcs + code
                                st.copy(dtcs = n, mil = if (n.isNotEmpty()) true else st.mil)
                            }
                        })
                    }
                }
                Text("Le téléphone et la montre peuvent lire puis effacer ces codes.", fontSize = 12.sp, color = GaugeGrey)
            }
            Section("Pannes de liaison") {
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(sim.adapterAbsent, { v -> Sim.update { it.copy(adapterAbsent = v) } }); Spacer(Modifier.width(8.dp)); Text("Boîtier absent (voyant 1 rouge)", fontSize = 13.sp) }
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(sim.ecuMute, { v -> Sim.update { it.copy(ecuMute = v) } }); Spacer(Modifier.width(8.dp)); Text("Calculateur muet (voyant 2 orange)", fontSize = 13.sp) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(sim.oldEcu, { v -> Sim.update { it.copy(oldEcu = v) }; Telemetry.supported.value = emptySet(); Telemetry.scanRequest.value = true }); Spacer(Modifier.width(8.dp))
                    Text("Vieux calculateur (1997) : peu de données déclarées", fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
