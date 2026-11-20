@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package fr.elmdash

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import fr.elmdash.core.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fr.elmdash.core.*

fun defaultCfg(m: Meta) = GaugeCfg(m.id, m.min, m.max, m.warn, m.decimals)
fun comboCfg(c: Combo, custom: List<PidDef>): GaugeCfg {
    val m = Pids.meta(c.pid, custom)
    return GaugeCfg(c.pid, m.min, m.max, m.warn, m.decimals, null, c.style, c.extra)
}

@Composable
private fun PickRow(m: Meta, ok: Boolean, showTick: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(m.label, color = if (ok) Color.White else GaugeGrey, fontSize = 14.sp)
            Text("${m.unit.ifEmpty { "—" }} · ${tr(m.style.fr())}", color = GaugeGrey, fontSize = 11.sp)
        }
        if (ok && showTick) Text("✓", color = GaugeGreen, fontSize = 16.sp)
    }
}

/** Choix d'une donnée : recherche + ce que TON calculateur sait lire en premier. */
@Composable
fun DataPickerDialog(custom: List<PidDef>, unitFilter: String?, exclude: Set<String>, onPick: (Meta) -> Unit, onDismiss: () -> Unit) {
    val sup by Telemetry.supported.collectAsState()
    var q by remember { mutableStateOf("") }
    val all = remember(custom) { Pids.allMeta(custom) }
    val filtered = all.filter { m ->
        (unitFilter == null || m.unit == unitFilter) && m.id !in exclude &&
            (q.isBlank() || m.label.contains(q, true) || m.group.contains(q, true) || m.id.contains(q, true))
    }
    fun isOk(m: Meta) = sup.isEmpty() || m.id in sup || m.group == "Perso"
    val ok = filtered.filter { isOk(it) }
    val nok = filtered.filter { !isOk(it) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFF0E151C), modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.9f)) {
            Column(Modifier.padding(14.dp)) {
                Text("Choisir une donnée", fontSize = 18.sp, color = Color.White)
                OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(vertical = 8.dp), singleLine = true, label = { Text("Rechercher") })
                if (sup.isNotEmpty()) Text("✓ = lu par le calculateur (scan automatique).", fontSize = 11.sp, color = GaugeGrey)
                LazyColumn(Modifier.weight(1f)) {
                    ok.groupBy { it.group }.forEach { (g, list) ->
                        item { Text(g, color = GaugeCyan, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)) }
                        items(list) { m -> PickRow(m, true, sup.isNotEmpty()) { onPick(m) } }
                    }
                    if (nok.isNotEmpty()) {
                        item { Text("Non déclarées par le calculateur", color = GaugeAmber, fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp)) }
                        items(nok) { m -> PickRow(m, false, false) { onPick(m) } }
                    }
                }
                TextButton(onDismiss) { Text("Annuler") }
            }
        }
    }
}

/**
 * Éditeur de cadran : donnée(s), type d'affichage, échelle, page. Aperçu en direct avec les vraies valeurs (ou la simulation).
 * [preset] pré-remplit un nouveau cadran ; [initial] = cadran existant à modifier.
 */
@Composable
fun GaugeEditorDialog(
    initial: GaugeCfg?, preset: GaugeCfg?, pages: List<PageCfg>, pageIndex: Int, custom: List<PidDef>,
    onSave: (GaugeCfg, Int) -> Unit, onDelete: (() -> Unit)?, onDismiss: () -> Unit
) {
    val values by Telemetry.values.collectAsState()
    val sup by Telemetry.supported.collectAsState()
    var cfg by remember { mutableStateOf(initial ?: preset) }
    var target by remember { mutableIntStateOf(pageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))) }
    var picker by remember { mutableIntStateOf(if (initial == null && preset == null) 1 else 0) }   // 1 = donnée principale, 2 = lecture en plus
    var resetN by remember { mutableIntStateOf(0) }
    val c = cfg
    val ids = if (c == null) emptyList() else Pids.idsOf(c)
    DisposableEffect(ids) { Telemetry.editVisible.value = ids.toSet(); onDispose { Telemetry.editVisible.value = emptySet() } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFF0E151C), modifier = Modifier.fillMaxWidth(0.97f).fillMaxHeight(0.95f)) {
            Column(Modifier.fillMaxSize().padding(14.dp)) {
                Text(if (initial == null) "Nouveau cadran" else "Modifier le cadran", fontSize = 20.sp, color = Color.White)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

                    Text("Cadrans malins : plusieurs lectures ensemble", color = GaugeCyan, fontSize = 13.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pids.combos.forEach { cb ->
                            val avail = sup.isEmpty() || (listOf(cb.pid) + cb.extra).all { it in sup }
                            FilterChip(selected = c != null && c.pid == cb.pid && c.extra == cb.extra, enabled = avail,
                                onClick = { cfg = comboCfg(cb, custom); resetN++ }, label = { Text(cb.title) })
                        }
                    }

                    if (c == null) {
                        Button({ picker = 1 }) { Text("Choisir une donnée") }
                    } else {
                        val m = Pids.meta(c.pid, custom)
                        Box(Modifier.fillMaxWidth().height(210.dp), Alignment.Center) { GaugeCell(c, values, custom) }

                        Text("Type d'affichage", color = GaugeCyan, fontSize = 13.sp)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Style.values().forEach { s ->
                                FilterChip(selected = (c.style ?: m.style) == s, onClick = { cfg = c.copy(style = s) }, label = { Text("${s.icon()} ${tr(s.fr())}") })
                            }
                        }

                        Text("Données affichées", color = GaugeCyan, fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton({ picker = 1 }) { Text(m.label, maxLines = 1) }
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            c.extra.forEach { id ->
                                FilterChip(selected = true, onClick = { cfg = c.copy(extra = c.extra - id) }, label = { Text(tr(Pids.meta(id, custom).label).take(24) + "  ✕") })
                            }
                            if (c.extra.size < 3) OutlinedButton({ picker = 2 }) { Text("＋ Ajouter une lecture") }
                        }
                        if (c.extra.isNotEmpty()) Text("Les lectures supplémentaires partagent l'échelle ci-dessous (même unité).", fontSize = 11.sp, color = GaugeGrey)

                        Text("Échelle et alerte", color = GaugeCyan, fontSize = 13.sp)
                        key(c.pid, resetN) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                NumField("Min", c.min, Modifier.weight(1f)) { v -> v?.let { x -> cfg = cfg?.copy(min = x) } }
                                NumField("Max", c.max, Modifier.weight(1f)) { v -> v?.let { x -> cfg = cfg?.copy(max = x) } }
                                NumField(if ((c.style ?: m.style) == Style.DIAL) "Limite (rouge)" else "Alerte", c.warn, Modifier.weight(1f)) { v -> cfg = cfg?.copy(warn = v) }
                            }
                        }
                        TextButton({ cfg = c.copy(min = m.min, max = m.max, warn = m.warn); resetN++ }) { Text("Échelle automatique") }
                        if ((c.style ?: m.style) == Style.DIAL) Text("Manomètre « shift light » : de la moitié de la plage jusqu'à la limite, tout le cadran vire à l'orange puis au rouge, et clignote en rouge à la limite (rupteur). Définir la limite ici (ex. 6200 tr/min).", fontSize = 11.sp, color = GaugeGrey)

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(c.warm, { on -> cfg = cfg?.copy(warm = on) }); Spacer(Modifier.width(8.dp))
                            Text("Afficher la montée en température sur ce cadran", color = Color.White, fontSize = 13.sp)
                        }
                        if (c.warm) Text("Jauge intérieure à la forme du cadran : bleu → rouge pendant la chauffe, clignote en vert quand c'est assez chaud puis disparaît. Surchauffe : elle clignote en rouge. Source et seuils : Réglages › Montre.", fontSize = 11.sp, color = GaugeGrey)

                        OutlinedTextField(c.label ?: "", { t -> cfg = cfg?.copy(label = t.ifBlank { null }) }, Modifier.fillMaxWidth(),
                            singleLine = true, label = { Text("Nom affiché (optionnel)") })
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Page :", color = Color.White)
                            MenuPicker(pages.getOrNull(target)?.name ?: "—", pages.map { it.name }) { target = it }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (onDelete != null) TextButton(onDelete) { Text("Supprimer", color = GaugeRed) }
                    Box(Modifier.weight(1f))
                    OutlinedButton(onDismiss) { Text("Annuler") }
                    Button({ cfg?.let { onSave(it, target) } }, enabled = c != null) { Text(if (initial == null) "Ajouter" else "Enregistrer") }
                }
            }
        }
    }

    if (picker == 1) DataPickerDialog(custom, null, emptySet(), { m ->
        cfg = defaultCfg(m).copy(style = cfg?.style); resetN++; picker = 0
    }) { picker = 0 }
    val baseCfg = cfg
    if (picker == 2 && baseCfg != null) DataPickerDialog(custom, Pids.meta(baseCfg.pid, custom).unit, Pids.idsOf(baseCfg).toSet(), { m ->
        cfg = baseCfg.copy(extra = baseCfg.extra + m.id); picker = 0
    }) { picker = 0 }
}
