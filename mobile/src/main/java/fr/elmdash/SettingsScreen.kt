@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package fr.elmdash

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fr.elmdash.core.*
import fr.elmdash.core.Text
import org.json.JSONArray
import kotlin.math.roundToInt

enum class SettingsSection(val label: String) { BOX("Boîtier"), DATA("Données"), FUEL("Carburant"), WATCH("Montre"), PAGES("Pages"), ADV("Avancé") }

/** Pages proposées automatiquement d'après ce que le calculateur sait fournir. */
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
    val scroll = rememberScrollState()                       // hors de tout « key » : la position ne saute pas quand on change une option
    var section by remember { mutableStateOf(SettingsSection.BOX) }
    LaunchedEffect(section) { scroll.scrollTo(0) }
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?" }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onBack) { Text("← Retour") }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) { Text("Réglages", fontSize = 20.sp); Text("ELMDash v$version", fontSize = 11.sp, color = GaugeGrey) }
            LangPicker(I18n.lang) { l -> I18n.set(l.code); app.updateSettings(app.settings.copy(lang = l.code)) }
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
    }
    Section("Boîtier ELM327") {
        Text("Appairer d'abord le boîtier dans les réglages Bluetooth d'Android (code 1234 ou 0000), puis le choisir ici.", fontSize = 12.sp, color = GaugeGrey)
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

    Section("Données lues par le calculateur") {
        Button({ Telemetry.scanRequest.value = true }, enabled = live && !scanning) { Text(if (scanning) "Scan en cours…" else "Scanner le calculateur") }
        if (!live) Text("Connecter le boîtier (voyants verts) pour lister les données disponibles.", fontSize = 12.sp, color = GaugeGrey)
        if (sup.isNotEmpty()) {
            Text(trf("%d données disponibles. Seules les données déclarées par le véhicule apparaissent.", found.values.sumOf { it.size }), fontSize = 12.sp, color = GaugeGrey)
            Button({ app.updatePages(autoPages(sup, app.custom)) }) { Text("Créer des pages adaptées à ce véhicule") }
        }
    }
    found.forEach { (group, list) ->
        Section(group) {
            list.forEach { m ->
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr(m.label) + if (m.unit.isNotEmpty()) " (${m.unit})" else "", fontSize = 14.sp)
                        Text(trf("Affichage conseillé : %s", tr(m.style.fr())), fontSize = 11.sp, color = GaugeGrey)
                    }
                    OutlinedButton({ add = defaultCfg(m) }) { Text("＋ Cadran") }
                }
            }
        }
    }
    Section("Terminal brut") {
        Text("Envoyer une requête (ex. 010C, 0100, 22F40C). La réponse s'affiche en hexadécimal.", fontSize = 12.sp, color = GaugeGrey)
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

/** Recherche dans le catalogue d'injecteurs. */
@Composable
private fun InjectorDialog(onPick: (Injector) -> Unit, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val list = remember(q) { Injectors.search(q) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFF0E151C), modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.9f)) {
            Column(Modifier.padding(14.dp)) {
                Text("Catalogue d'injecteurs", fontSize = 18.sp, color = Color.White)
                OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(vertical = 8.dp), singleLine = true,
                    label = { Text("Rechercher (marque, modèle, débit en cc/min…)") })
                Text("Débits indicatifs (essence). L'étalonnage plein à plein affine le résultat.", fontSize = 11.sp, color = GaugeGrey)
                LazyColumn(Modifier.weight(1f)) {
                    items(list) { inj ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(inj) }.padding(vertical = 9.dp)) {
                            Text(inj.name + "  ·  " + fmtNum(inj.ccMin) + " cc/min", color = Color.White, fontSize = 14.sp)
                            if (inj.note.isNotEmpty()) Text(inj.note, color = GaugeGrey, fontSize = 11.sp)
                        }
                    }
                }
                TextButton(onDismiss) { Text("Fermer") }
            }
        }
    }
}

@Composable
private fun FuelSection(app: AppState) {
    val s = app.settings
    val link by Telemetry.link.collectAsState()
    val calActive by Telemetry.calActive.collectAsState(); val calKm by Telemetry.calKm.collectAsState(); val calL by Telemetry.calL.collectAsState()
    var picker by remember { mutableStateOf(false) }
    var pickN by remember { mutableIntStateOf(0) }
    var liters by remember { mutableStateOf<Double?>(null) }
    val live = link == Link.LIVE || link == Link.SIM

    Section("Carburant et éthanol") {
        Text(trf("Éthanol dans le réservoir : %d %%", s.ethanolPct.roundToInt()))
        Slider(s.ethanolPct.toFloat(), { app.updateSettings(s.copy(ethanolPct = it.roundToInt().toDouble())) }, valueRange = 0f..100f)
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(s.boxEnabled, { app.updateSettings(s.copy(boxEnabled = it)) }); Spacer(Modifier.width(8.dp)); Text("Boîtier éthanol (le calculateur croit brûler de l'essence)", fontSize = 13.sp) }
        Text(trf("Éthanol du carburant de référence du calculateur : %d %%", s.ecuRefEthanol.roundToInt()))
        Slider(s.ecuRefEthanol.toFloat(), { app.updateSettings(s.copy(ecuRefEthanol = it.roundToInt().toDouble())) }, valueRange = 0f..10f)
        Text(trf("Enrichissement estimé : ×%.2f", FuelMath.enrichment(s.ethanolPct / 100, s.ecuRefEthanol / 100)), color = GaugeCyan)
    }
    Section("Méthode de calcul de la consommation") {
        OptionCard("Débit d'air (MAF + lambda)", "Fonctionne avec tout véhicule qui lit le débit d'air.", s.fuelMode == FuelMode.MAF) { app.updateSettings(s.copy(fuelMode = FuelMode.MAF)) }
        OptionCard("Injecteurs", "Calcul d'après le temps d'ouverture des injecteurs, lu sur le calculateur.", s.fuelMode == FuelMode.INJECTOR) { app.updateSettings(s.copy(fuelMode = FuelMode.INJECTOR)) }
    }
    if (s.fuelMode == FuelMode.INJECTOR) {
        Section("Injecteurs") {
            Text(if (s.injName.isEmpty()) "Aucun injecteur choisi" else s.injName + "  ·  " + fmtNum(s.injectorCcMin) + " cc/min", fontSize = 14.sp)
            Button({ picker = true }) { Text("Choisir dans le catalogue") }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                key(pickN) { NumField("Débit (cc/min)", s.injectorCcMin, Modifier.weight(1f)) { v -> v?.let { app.updateSettings(app.settings.copy(injectorCcMin = it, injName = if (it == app.settings.injectorCcMin) app.settings.injName else "Personnalisé")) } } }
                NumField("Cylindres", s.cylinders.toDouble(), Modifier.weight(1f)) { v -> v?.let { app.updateSettings(app.settings.copy(cylinders = it.toInt().coerceIn(1, 12))) } }
                NumField("Temps mort (ms)", s.deadTimeMs, Modifier.weight(1f)) { v -> v?.let { app.updateSettings(app.settings.copy(deadTimeMs = it)) } }
            }
        }
        InjectorPidSection(app)
    }
    Section("Étalonnage du carburant") {
        Text("Pour une consommation au plus juste : faire le plein, démarrer l'étalonnage, rouler normalement, refaire le plein au même endroit puis saisir les litres ajoutés. Le résultat est conservé.", fontSize = 12.sp, color = GaugeGrey)
        Text(trf("Facteur actuel : ×%.3f", s.fuelCal), color = GaugeCyan)
        if (!calActive) {
            Button({ Telemetry.calCommand.value = "START" }, enabled = live) { Text("Démarrer l'étalonnage (après un plein)") }
            if (!live) Text("Le boîtier doit être connecté.", fontSize = 12.sp, color = GaugeGrey)
        } else {
            Text(trf("En cours : %.1f km · %.2f L estimés", calKm, calL))
            NumField("Litres ajoutés au plein", null, Modifier.fillMaxWidth()) { liters = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({
                    val real = liters
                    if (real != null && real > 0 && calL > 0.3) { app.updateSettings(s.copy(fuelCal = (real / calL).coerceIn(0.5, 2.0))); Telemetry.calCommand.value = "STOP" }
                }, enabled = (liters ?: 0.0) > 0 && calL > 0.3) { Text("Appliquer") }
                OutlinedButton({ Telemetry.calCommand.value = "STOP" }) { Text("Annuler") }
            }
            if (calL <= 0.3) Text("Rouler encore un peu pour obtenir une mesure exploitable.", fontSize = 12.sp, color = GaugeGrey)
        }
        if (s.fuelCal != 1.0) TextButton({ app.updateSettings(s.copy(fuelCal = 1.0)) }) { Text("Réinitialiser l'étalonnage") }
        OutlinedButton({ Telemetry.tripReset.value = true }) { Text("Remettre le trajet à zéro") }
    }
    if (picker) InjectorDialog({ inj -> app.updateSettings(app.settings.copy(injName = inj.name, injectorCcMin = inj.ccMin)); pickN++; picker = false }) { picker = false }
}

/** Le PID « activité des injecteurs » (temps d'ouverture en ms) dépend du véhicule : il se règle ici. */
@Composable
private fun InjectorPidSection(app: AppState) {
    val existing = app.custom.firstOrNull { it.id == "INJ_PW" }
    var req by remember { mutableStateOf(existing?.cmd ?: "") }
    var hdr by remember { mutableStateOf(existing?.header ?: "7E0") }
    var bytes by remember { mutableStateOf(existing?.bytes?.toString() ?: "2") }
    var scale by remember { mutableStateOf(existing?.scale?.toString() ?: "0.001") }
    Section("Activité des injecteurs (PID)") {
        Text("Requête du calculateur donnant le temps d'ouverture des injecteurs (ex. 22XXXX), fournie par la documentation du véhicule.", fontSize = 12.sp, color = GaugeGrey)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(req, { req = it.uppercase().filter { c -> c.isLetterOrDigit() } }, Modifier.weight(1f), singleLine = true, label = { Text("Requête") })
            OutlinedTextField(hdr, { hdr = it.uppercase().filter { c -> c.isLetterOrDigit() } }, Modifier.weight(1f), singleLine = true, label = { Text("Adresse ECU") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(bytes, { bytes = it }, Modifier.weight(1f), singleLine = true, label = { Text("Octets") })
            OutlinedTextField(scale, { scale = it }, Modifier.weight(1f), singleLine = true, label = { Text("Échelle (ms par unité)") })
        }
        Button({
            val b = bytes.toIntOrNull(); val sc = scale.replace(',', '.').toDoubleOrNull()
            if (req.length >= 4 && b != null && sc != null)
                app.updateCustom(app.custom.filter { it.id != "INJ_PW" } + PidDef("INJ_PW", "Injection", "ms", hdr, req, b.coerceIn(1, 4), sc, 0.0, false, 0.0, 20.0, 2, Style.NUMERIC, "Perso"))
        }) { Text(if (existing == null) "Enregistrer le PID" else "Mettre à jour le PID") }
    }
}

@Composable
private fun WatchSection(app: AppState) {
    val s = app.settings; val w = app.warm
    Section("Taille du cadran sur la montre") {
        Text("${(s.watchScale * 100).roundToInt()} %")
        Slider(s.watchScale.toFloat(), { app.updateSettings(s.copy(watchScale = (it * 20).roundToInt() / 20.0)) }, valueRange = 0.6f..1.6f)
        Text("100 % = cadran ajusté à l'écran. Au-delà, il déborde légèrement pour mieux remplir l'écran rond. Réglable aussi sur la montre.", fontSize = 12.sp, color = GaugeGrey)
    }
    Section("Montée en température") {
        Text("Source et seuils de la jauge de chauffe. Elle s'affiche sur les cadrans où « Afficher la montée en température » est coché : bleu → rouge pendant la chauffe, clignote en vert quand c'est prêt (avec vibration) puis disparaît ; clignote en rouge en cas de surchauffe.", fontSize = 12.sp, color = GaugeGrey)
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(w.enabled, { app.updateWarm(w.copy(enabled = it)) }); Spacer(Modifier.width(8.dp)); Text("Activer") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = w.pid == "COOLANT", onClick = { app.updateWarm(w.copy(pid = "COOLANT")) }, label = { Text("Eau") })
            FilterChip(selected = w.pid == "OIL", onClick = { app.updateWarm(w.copy(pid = "OIL")) }, label = { Text("Huile") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumField("Prêt à (°C)", w.ready, Modifier.weight(1f)) { v -> v?.let { app.updateWarm(app.warm.copy(ready = it)) } }
            NumField("Surchauffe (°C)", w.hot, Modifier.weight(1f)) { v -> v?.let { app.updateWarm(app.warm.copy(hot = it)) } }
        }
    }
    Section("Codes défaut sur la montre") {
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(s.watchDtc, { app.updateSettings(s.copy(watchDtc = it)) }); Spacer(Modifier.width(8.dp)); Text("Page « codes défaut » et témoin moteur", fontSize = 13.sp) }
    }
}

@Composable
private fun PagesSection(app: AppState) {
    val pages = app.pages
    val sup by Telemetry.supported.collectAsState()
    Section("Pages") {
        Text("Toucher un cadran sur le tableau de bord pour le modifier ; le bouton ＋ en ajoute un. Ici : gérer les pages.", fontSize = 12.sp, color = GaugeGrey)
        pages.forEachIndexed { i, p ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(p.name, { n -> app.updatePages(app.pages.mapIndexed { j, q -> if (j == i) q.copy(name = n) else q }) },
                    Modifier.weight(1f), singleLine = true, label = { Text(trf("%d cadran(s)", p.gauges.size)) })
                TextButton({ if (i > 0) app.updatePages(pages.toMutableList().also { l -> l.add(i - 1, l.removeAt(i)) }) }) { Text("↑") }
                TextButton({ if (i < pages.size - 1) app.updatePages(pages.toMutableList().also { l -> l.add(i + 1, l.removeAt(i)) }) }) { Text("↓") }
                TextButton({ app.updatePages(pages.filterIndexed { j, _ -> j != i }) }) { Text("✕", color = GaugeRed) }
            }
        }
        OutlinedButton({ app.updatePages(pages + PageCfg(trf("Page %d", pages.size + 1), emptyList())) }) { Text("＋ Ajouter une page") }
    }
    Section("Modèles") {
        Button({ app.updatePages(autoPages(sup, app.custom)) }, enabled = sup.isNotEmpty()) { Text("Pages adaptées à ce véhicule") }
        if (sup.isEmpty()) Text("Disponible après le scan du calculateur (section « Données »).", fontSize = 12.sp, color = GaugeGrey)
        OutlinedButton({ app.updatePages(Defaults.pages) }) { Text("Revenir aux pages par défaut") }
    }
}

@Composable
private fun AdvSection(app: AppState, version: String) {
    var text by remember { mutableStateOf(Codec.pidsToJson(app.custom).toString(2)) }
    var err by remember { mutableStateOf(false) }
    Section("PID personnalisés") {
        Text("Un PID = un ECU (adresse), une requête brute (Mode 01 ou 22), un nombre d'octets et la formule brut × échelle + décalage. Exemple :", fontSize = 12.sp, color = GaugeGrey)
        Text("[{\"id\":\"MON_PID\",\"label\":\"Mon PID\",\"unit\":\"V\",\"header\":\"7E0\",\"cmd\":\"22XXXX\",\"bytes\":2,\"scale\":0.001,\"offset\":0,\"min\":0,\"max\":20,\"decimals\":2,\"style\":\"NUMERIC\"}]",
            fontSize = 12.sp, color = GaugeGrey)
        OutlinedTextField(text, { t ->
            text = t
            runCatching { Codec.pidsFromJson(JSONArray(t)) }.onSuccess { app.updateCustom(it); err = false }.onFailure { err = true }
        }, Modifier.fillMaxWidth(), minLines = 6, isError = err, supportingText = { if (err) Text("JSON invalide") })
    }
    Section("À propos") { Text("ELMDash v$version", fontSize = 14.sp); Text("Téléphone + montre Wear OS · boîtier ELM327 Bluetooth", fontSize = 12.sp, color = GaugeGrey) }
}
