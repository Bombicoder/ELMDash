@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package fr.elmdash.core

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun StatusDot(color: Color, size: Dp, blink: Boolean = false) {
    val a by rememberInfiniteTransition(label = "dot").animateFloat(1f, 0.25f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "a")
    Box(Modifier.size(size).background(color.copy(alpha = if (blink) a else 1f), CircleShape))
}

/** Deux témoins : 1) boîtier ELM (Bluetooth)  2) calculateur de la voiture. */
@Composable
fun LinkLeds(link: Link, size: Dp = 10.dp, labels: Boolean = false) {
    val green = Color(0xFF4ADE80); val amber = Color(0xFFF59E0B); val violet = Color(0xFFA78BFA); val off = Color(0xFF475569)
    val adapter = when (link) { Link.OFF -> Color(0xFFEF4444); Link.CONNECTING -> amber; Link.SIM -> violet; else -> green }
    val ecu = when (link) { Link.LIVE -> green; Link.SIM -> violet; Link.ADAPTER_OK -> amber; else -> off }
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(adapter, size, link == Link.CONNECTING)
        if (labels) Text(" Boîtier", color = Color(0xFFB6C4D0), fontSize = 11.sp)
        Spacer(Modifier.width(size * 0.7f))
        StatusDot(ecu, size, link == Link.ADAPTER_OK)
        if (labels) Text(" Calculateur", color = Color(0xFFB6C4D0), fontSize = 11.sp)
    }
}

/** Témoin moteur (MIL) : visible seulement quand le calculateur l'allume. */
@Composable
fun MilBadge(on: Boolean, count: Int, onClick: (() -> Unit)? = null) {
    if (!on) return
    val a by rememberInfiniteTransition(label = "mil").animateFloat(1f, 0.5f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "m")
    val amber = Color(0xFFF59E0B)
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(amber.copy(alpha = 0.22f * a))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("⚠", color = amber, fontSize = 12.sp)
        if (count > 0) Text(" $count", color = amber, fontSize = 11.sp)
    }
}
