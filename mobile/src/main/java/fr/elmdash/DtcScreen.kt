@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package fr.elmdash

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

/** Codes défaut : témoin moteur, lecture, effacement. */
@Composable
fun DtcScreen(onBack: () -> Unit) {
    val mil by Telemetry.mil.collectAsState(); val count by Telemetry.dtcCount.collectAsState()
    val list by Telemetry.dtcList.collectAsState(); val busy by Telemetry.dtcBusy.collectAsState()
    val req by Telemetry.dtcRequest.collectAsState(); val link by Telemetry.link.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    val live = link == Link.LIVE || link == Link.SIM

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onBack) { Text("← Retour") }
            Spacer(Modifier.width(12.dp)); Text("Codes défaut", fontSize = 20.sp)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Section("Témoin moteur") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MilBadge(true, count)
                    Spacer(Modifier.width(8.dp))
                    Text(if (mil) "ALLUMÉ : $count code(s) mémorisé(s)" else if (count > 0) "Éteint, mais $count code(s) en mémoire" else "Éteint, aucun code", fontSize = 15.sp)
                }
                LinkLeds(link, 12.dp, labels = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button({ Telemetry.dtcRequest.value = "READ" }, enabled = live && !busy && req == null) { Text(if (busy) "Lecture…" else "Lire les codes") }
                OutlinedButton({ confirm = true }, enabled = live && !busy && req == null) { Text("Effacer les codes") }
            }
            if (!live) Text("Connecte-toi au boîtier pour lire les codes.", fontSize = 12.sp, color = GaugeGrey)
            if (list.isEmpty()) Text("Aucun code lu pour l'instant.", color = GaugeGrey, fontSize = 13.sp)
            list.forEach { code ->
                Section(code) { Text(Dtc.describe(code), fontSize = 14.sp) }
            }
            Text("Effacer les codes éteint le témoin et remet les moniteurs d'émissions à zéro (le contrôle technique peut alors être refusé tant que la voiture n'a pas roulé).", fontSize = 11.sp, color = GaugeGrey)
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Effacer les codes défaut ?") },
        text = { Text("Le témoin moteur sera éteint. S'il y a toujours un problème, il se rallumera.") },
        confirmButton = { TextButton({ confirm = false; Telemetry.dtcRequest.value = "CLEAR" }) { Text("Effacer") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Annuler") } }
    )
}
