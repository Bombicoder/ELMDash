@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package fr.elmdash

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.elmdash.core.*
import org.json.JSONArray
import kotlin.math.roundToInt

enum class SettingsSection(val label: String) { BOX("Boîtier"), DATA("Données"), FUEL("Carburant"), WATCH("Montre"), PAGES("Pages"), ADV("Avancé") }

/** Pages proposées automatiquement d'après ce que TON calculateur sait fournir. */
fun autoPages(sup: Set<String>, custom: List<PidDef>): List<PageCfg> {
    fun g(id: String) = Pids.meta(id, custom).let { GaugeCfg(it.id, it.min, it.max, it.warn, it.decimals) }
    val out = mutableListOf<PageCfg>()
    listOf("RPM", "COOLANT", "OIL").filter { it in sup }.let { if (it.isNotEmpty()) out += PageCfg("Moteur", it.map(::g)) }
    if ("MAF" in sup) out += PageCfg("Conso", listOf(
        GaugeCfg("FUEL_L100", 0.0, 30.0, 20.0, 1, null, Style.DIAL, listOf("FUEL_AVG")), g("FUEL_LH")))
    if ("LAMBDA" in sup && "LAMBDA_B1" in sup) out += PageCfg("Mélange", listOf(comboCfg(Pids.combos[0], custom)))
    else if ("O2_B1S1" in sup) out += PageCfg("Mélange", listOf(comboCfg(Pids.combos[2], custom)))
    listOf("COOLANT", "IAT", "LOAD", "THROTTLE", "TIMING", "STFT1", "LTFT1", "VOLT", "BATT").filter { it in sup }.take(6)
        .let { if (it.isNotEmpty()) out += PageCfg("Tableau", it.map(::g)) }
    return out.ifEmpty { Defaults.pages }
}

@Composable
fun SettingsScreen(app: AppState, onBack: () -> Unit, onSim: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()                       // hors de tout « key » : la position ne saute plus quand on change une option
    var section by remember { mutableStateOf(SettingsSection.BOX) }
    LaunchedEffect(section) { scroll.scrollTo(0) }
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?" }
    val s = app.settings

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onBack) { Text("← Retour") }
            Spacer(Modifier.width(12.dp))
            Column { Text("Réglages", fontSize = 20.sp); Text("ELMDash v$version", fontSize = 11.sp, color = GaugeGrey) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsSection.values().forEach { sec -> FilterChip(selected = section == sec, onClick = { section = sec }, label = { Text(sec.label) }) }
        }
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (section) {
                SettingsSection.BOX -> BoxSection(app, onSim)
                SettingsSection.DATA -> DataSection(app)
                SettingsSection.FUEL -> FuelSection(app)
                SettingsSection.WATCH -> WatchSection(app)
                SettingsSection.PAGES -> PagesSection(app)
                SettingsSection.ADV -> AdvSection(app, version)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun BoxSection(app: AppState, onSim: () -> Unit) {
    val ctx = LocalContext.current
    val s = app.settings
    val link by Telemetry.link.collectAsState(); val status by Telemetry.status.collectAsState(); val info by Telemetry.adapterInfo.collectAsState()
    val devices = remember { bondedDevices(ctx).map { (runCatching { it.name }.getOrNull() ?: "?") to it.address } }

    Section("État de la liaison") {
        LinkLeds(link, 14.dp, labels = true)
        Text(status, fontSize = 13.sp)
        if (info.isNotEmpty()) Text(info, fontSize = 11.sp, color = GaugeGrey)
        OutlinedButton({ Telemetry.reconnect.value = true }) { Text("Reconnecter maintenant") }
    }
    Section("Protocole") {
        ProtoMode.values().forEach { m -> OptionCard(m.label, m.desc, s.proto == m) { app.updateSettings(s.copy(proto = m)); Telemetry.reconnect.value = true } }
        Text("Ford Probe 1997 : garde « OBD-II ancien ». Le changement est appliqué tout de suite (reconnexion).", fontSize = 12.sp, color = GaugeGrey)
    }
    Section("Boîtier ELM327") {
        Text("1) Appaire le boîtier dans les réglages Bluetooth d'Android (code 1234 ou 0000). 2) Choisis-le ici.", fontSize = 12.sp, color = GaugeGrey)
        OutlinedButton({ ctx.startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) }) { Text("Ouvrir les réglages Bluetooth") }
        OptionCard("Détection automatique", "Premier appareil nommé OBD, ELM, V-LINK, VGATE…", s.deviceMac.isEmpty()) { app.updateSettings(s.copy(deviceMac = "")) }
        devices.forEach { (n, a) -> OptionCard(n, a, s.deviceMac == a) { app.updateSettings(s.copy(deviceMac = a)) } }
        if (devices.isEmpty()) Text("Aucun appareil appairé (ou permission Bluetooth refusée).", fontSize = 12.sp, color = GaugeAmber)
    }
    Section("Montre autonome") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(s.watchDirect, { app.updateSettings(s.copy(watchDirect = it)) }); Spacer(Modifier.width(8.dp))
            Text("Le boîtier est utilisé par la montre (le téléphone ne s'y connecte pas)", fontSize = 13.sp)
        }
    }
    Section("Simulation") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(s.simulate, { app.updateSettings(s.copy(simulate = it)); Telemetry.reconnect.value = true }); Spacer(Modifier.width(8.dp))
            Text("Mode simulation (sans voiture ni boîtier)", fontSize = 13.sp)
        }
        Button(onSim) { Text("Ouvrir le simulateur") }
    }
}

@Composable
private fun DataSection(app: AppState) {
    val sup by Telemetry.supported.collectAsState(); val scanning by Telemetry.scanRequest.collectAsState()
    val link by Telemetry.link.collectAsState(); val raw by Telemetry.rawOut.collectAsState()
    var cmd by remember { mutableStateOf("") }
    var add by remember { mutableStateOf<GaugeCfg?>(null) }
    val live = link == Link.LIVE || link == Link.SIM
    val found = Pids.allMeta(app.custom).filter { it.id in sup }.groupBy { it.group }

    Section("Ce que ton calculateur sait lire") {
        Button({ Telemetry.scanRequest.value = true }, enabled = live && !scanning) { Text(if (scanning) "Scan en cours…" else "Scanner le calculateur") }
        if (!live) Text("Connecte-toi au boîtier (deux voyants verts ou violets) pour lister les données.", fontSize = 12.sp, color = GaugeGrey)
        if (sup.isNotEmpty()) {
            Text("${found.values.sumOf { it.size }} données disponibles. Seul ce que ta voiture déclare apparaît (pas de turbo = pas de pression de suralimentation).", fontSize = 12.sp, color = GaugeGrey)
            Button({ app.updatePages(autoPages(sup, app.custom)) }) { Text("Créer des pages adaptées à mon calculateur") }
        }
    }
    found.forEach { (group, list) ->
        Section(group) {
            list.forEach { m ->
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.label + if (m.unit.isNotEmpty()) " (${m.unit})" else "", fontSize = 14.sp)
                        Text("Affichage conseillé : ${m.style.fr()}", fontSize = 11.sp, color = GaugeGrey)
                    }
                    OutlinedButton({ add = defaultCfg(m) }) { Text("＋ Cadran") }
                }
            }
        }
    }
    Section("Terminal brut") {
        Text("Tape une requête (ex. 010C, 0100, 22F40C). Réponse en hexadécimal. Utile pour trouver des PID Mode 22 propres à ta voiture.", fontSize = 12.sp, color = GaugeGrey)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(cmd, { cmd = it.uppercase().filter { c -> c.isLetterOrDigit() } }, Modifier.weight(1f), singleLine = true, label = { Text("Requête") })
            Button({ if (cmd.isNotEmpty() && live) Telemetry.rawIn.value = cmd }, enabled = live) { Text("Envoyer") }
        }
        Text(raw.ifEmpty { "—" }, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
    add?.let { preset ->
        GaugeEditorDialog(null, preset, app.pages, 0, app.custom,
            onSave = { cfg, target -> app.saveGauge(EditorTarget(target, -1), cfg, target); add = null }, onDelete = null, onDismiss = { add = null })
    }
}

@Composable
private fun FuelSection(app: AppState) {
    val s = app.settings
    Section("Carburant et éthanol") {
        Text("Éthanol dans le réservoir : ${s.ethanolPct.roundToInt()} %")
        Slider(s.ethanolPct.toFloat(), { app.updateSettings(s.copy(ethanolPct = it.roundToInt().toDouble())) }, valueRange = 0f..100f)
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(s.boxEnabled, { app.updateSettings(s.copy(boxEnabled = it)) }); Spacer(Modifier.width(8.dp)); Text("Boîtier éthanol (l'ECU croit brûler du SP95)", fontSize = 13.sp) }
        Text("Éthanol du carburant de référence ECU : ${s.ecuRefEthanol.roundToInt()} %")
        Slider(s.ecuRefEthanol.toFloat(), { app.updateSettings(s.copy(ecuRefEthanol = it.roundToInt().toDouble())) }, valueRange = 0f..10f)
        Text("Enrichissement estimé : ×%.2f".format(FuelMath.enrichment(s.ethanolPct / 100, s.ecuRefEthanol / 100)), color = GaugeCyan)
    }
    Section("Calcul de la consommation") {
        OptionCard("Débit d'air (MAF + lambda)", "Marche sur toute voiture qui lit le débit d'air.", s.fuelMode == FuelMode.MAF) { app.updateSettings(s.copy(fuelMode = FuelMode.MAF)) }
        OptionCard("Injecteurs", "Nécessite un PID perso « INJ_PW » (largeur d'impulsion, ms).", s.fuelMode == FuelMode.INJECTOR) { app.updateSettings(s.copy(fuelMode = FuelMode.INJECTOR)) }
        if (s.fuelMode == FuelMode.INJECTOR) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumField("Cylindres", s.cylinders.toDouble(), Modifier.weight(1f)) { v -> v?.let { app.updateSettings(s.copy(cylinders = it.toInt().coerceIn(1, 12))) } }
            NumField("Injecteur cc/min", s.injectorCcMin, Modifier.weight(1f)) { v -> v?.let { app.updateSettings(s.copy(injectorCcMin = it)) } }
            NumField("Temps mort ms", s.deadTimeMs, Modifier.weight(1f)) { v -> v?.let { app.updateSettings(s.copy(deadTimeMs = it)) } }
        }
        Text("Conso instantanée (L/100 et L/h) et depuis le début du trajet (⟲ en haut du tableau de bord pour remettre à zéro).", fontSize = 12.sp, color = GaugeGrey)
        OutlinedButton({ Telemetry.tripReset.value = true }) { Text("Remettre le trajet à zéro") }
    }
}

@Composable
private fun WatchSection(app: AppState) {
    val s = app.settings; val w = app.warm
    Section("Taille du cadran sur la montre") {
        Text("${(s.watchScale * 100).roundToInt()} %")
        Slider(s.watchScale.toFloat(), { app.updateSettings(s.copy(watchScale = (it * 20).roundToInt() / 20.0)) }, valueRange = 0.6f..1.6f)
        Text("100 % = cadran ajusté à l'écran. Au-delà, il déborde un peu pour remplir l'écran rond. Réglable aussi sur la montre.", fontSize = 12.sp, color = GaugeGrey)
    }
    Section("Montée en température") {
        Text("Source et seuils de la jauge de chauffe. Elle s’affiche sur les cadrans où tu as coché « Afficher la montée en température » (touche un cadran pour le régler) : bleu → rouge en chauffant, clignote vert quand c’est prêt (vibration) puis disparaît ; clignote rouge en cas de surchauffe.", fontSize = 12.sp, color = GaugeGrey)
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(w.enabled, { app.updateWarm(w.copy(enabled = it)) }); Spacer(Modifier.width(8.dp)); Text("Activer") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = w.pid == "COOLANT", onClick = { app.updateWarm(w.copy(pid = "COOLANT")) }, label = { Text("Eau") })
            FilterChip(selected = w.pid == "OIL", onClick = { app.updateWarm(w.copy(pid = "OIL")) }, label = { Text("Huile") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumField("Prêt à (°C)", w.ready, Modifier.weight(1f)) { v -> v?.let { app.updateWarm(w.copy(ready = it)) } }
            NumField("Surchauffe (°C)", w.hot, Modifier.weight(1f)) { v -> v?.let { app.updateWarm(w.copy(hot = it)) } }
        }
    }
    Section("Codes défaut sur la montre") {
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(s.watchDtc, { app.updateSettings(s.copy(watchDtc = it)) }); Spacer(Modifier.width(8.dp)); Text("Page « codes défaut » + témoin moteur", fontSize = 13.sp) }
    }
}

@Composable
private fun PagesSection(app: AppState) {
    val pages = app.pages
    val sup by Telemetry.supported.collectAsState()
    Section("Pages") {
        Text("Touche un cadran dans le tableau de bord pour le modifier ; le bouton ＋ en ajoute un. Ici : gérer les pages.", fontSize = 12.sp, color = GaugeGrey)
        pages.forEachIndexed { i, p ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(p.name, { n -> app.updatePages(pages.mapIndexed { j, q -> if (j == i) q.copy(name = n) else q }) },
                    Modifier.weight(1f), singleLine = true, label = { Text("${p.gauges.size} cadran(s)") })
                TextButton({ if (i > 0) app.updatePages(pages.toMutableList().also { l -> l.add(i - 1, l.removeAt(i)) }) }) { Text("↑") }
                TextButton({ if (i < pages.size - 1) app.updatePages(pages.toMutableList().also { l -> l.add(i + 1, l.removeAt(i)) }) }) { Text("↓") }
                TextButton({ app.updatePages(pages.filterIndexed { j, _ -> j != i }) }) { Text("✕", color = GaugeRed) }
            }
        }
        OutlinedButton({ app.updatePages(pages + PageCfg("Page ${pages.size + 1}", emptyList())) }) { Text("＋ Ajouter une page") }
    }
    Section("Modèles") {
        Button({ app.updatePages(autoPages(sup, app.custom)) }, enabled = sup.isNotEmpty()) { Text("Pages adaptées à mon calculateur") }
        if (sup.isEmpty()) Text("Disponible après le scan du calculateur (voir « Données »).", fontSize = 12.sp, color = GaugeGrey)
        OutlinedButton({ app.updatePages(Defaults.pages) }) { Text("Revenir aux pages par défaut") }
    }
}

@Composable
private fun AdvSection(app: AppState, version: String) {
    var text by remember { mutableStateOf(Codec.pidsToJson(app.custom).toString(2)) }
    var err by remember { mutableStateOf(false) }
    Section("PID personnalisés (style FORScan)") {
        Text("Un PID = un ECU (header), une requête brute (Mode 01 ou 22), le nombre d'octets et la formule brut × scale + offset. Exemple :\n" +
            "[{\"id\":\"INJ_PW\",\"label\":\"Injection\",\"unit\":\"ms\",\"header\":\"7E0\",\"cmd\":\"22XXXX\",\"bytes\":2,\"scale\":0.001,\"offset\":0,\"min\":0,\"max\":20,\"decimals\":2,\"style\":\"NUMERIC\"}]",
            fontSize = 12.sp, color = GaugeGrey)
        OutlinedTextField(text, { t ->
            text = t
            runCatching { Codec.pidsFromJson(JSONArray(t)) }.onSuccess { app.updateCustom(it); err = false }.onFailure { err = true }
        }, Modifier.fillMaxWidth(), minLines = 6, isError = err, supportingText = { if (err) Text("JSON invalide") })
    }
    Section("À propos") { Text("ELMDash v$version", fontSize = 14.sp); Text("Téléphone + montre Wear OS · ELM327 Bluetooth", fontSize = 12.sp, color = GaugeGrey) }
}
