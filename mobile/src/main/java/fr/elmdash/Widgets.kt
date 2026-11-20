@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package fr.elmdash

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.elmdash.core.GaugeCyan
import fr.elmdash.core.GaugeGrey
import fr.elmdash.core.Lang
import fr.elmdash.core.Text

fun fmtNum(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()

@Composable
fun NumField(label: String, v: Double?, modifier: Modifier = Modifier, onV: (Double?) -> Unit) {
    var t by remember { mutableStateOf(v?.let { fmtNum(it) } ?: "") }
    OutlinedTextField(t, { t = it; onV(it.replace(',', '.').toDoubleOrNull()) }, modifier, label = { Text(label) },
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}

@Composable
fun MenuPicker(text: String, items: List<String>, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ open = true }) { Text(text, maxLines = 1) }
        DropdownMenu(open, { open = false }) { items.forEachIndexed { i, s -> DropdownMenuItem(text = { Text(s) }, onClick = { onPick(i); open = false }) } }
    }
}

/** Carte sélectionnable (choix de protocole, de boîtier…). */
@Composable
fun OptionCard(title: String, desc: String?, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (selected) Color(0x3322D3EE) else Color(0xFF131C25))
            .clickable { onClick() }.padding(12.dp)
    ) {
        Text((if (selected) "● " else "○ ") + title, color = if (selected) GaugeCyan else Color.White, fontSize = 14.sp)
        if (desc != null) Text(desc, color = GaugeGrey, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF0E151C)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, color = GaugeCyan, fontSize = 15.sp)
        content()
    }
}

/** Drapeau cliquable : ouvre la liste des langues. */
@Composable
fun LangPicker(current: Lang, onPick: (Lang) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Text(current.flag, fontSize = 24.sp, modifier = Modifier.clickable { open = true }.padding(horizontal = 8.dp))
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("Langue") },
        text = {
            Column {
                Lang.values().forEach { l ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(l); open = false }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(l.flag, fontSize = 26.sp); Spacer(Modifier.width(14.dp)); Text(l.label, fontSize = 17.sp)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton({ open = false }) { Text("Fermer") } }
    )
}
