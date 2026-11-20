package fr.elmdash.core

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Montée en température, dessinée DANS chaque cadran :
 *  froid (phase 0)  : jauge intérieure qui se remplit, de plus en plus rouge, + température
 *  prêt  (phase 1)  : clignotement vert + ondes qui s'étendent pendant 3 s + vibration, puis tout disparaît
 *  surchauffe (3)   : clignotement rouge + vibration longue
 */
class WarmUi(val phase: Int, val progress: Float, val temp: Double, val color: Color, val pulse: Float, val ripple: Float) {
    fun text(): String = when (phase) { 0 -> trf("Froid %d°", temp.roundToInt()); 1 -> trf("Prêt ✓ %d°", temp.roundToInt()); else -> trf("Surchauffe %d°", temp.roundToInt()) }
}

val LocalWarm = compositionLocalOf<WarmUi?> { null }

@Composable
fun rememberWarmUi(temp: Double?, cfg: WarmCfg, onBuzz: (Boolean) -> Unit): WarmUi? {
    val buzz by rememberUpdatedState(onBuzz)
    var phase by remember { mutableIntStateOf(-1) }      // -1 inconnu · 0 froid · 1 prêt · 2 terminé · 3 surchauffe
    var t0 by remember { mutableDoubleStateOf(0.0) }

    LaunchedEffect(temp) {
        val t = temp ?: return@LaunchedEffect
        when {
            phase == -1 -> { t0 = minOf(t, cfg.ready - 40); phase = if (t >= cfg.ready) 2 else 0 }   // moteur déjà chaud : aucune fausse alerte
            t >= cfg.hot -> { if (phase != 3) buzz(true); phase = 3 }
            phase == 3 && t < cfg.hot - 3 -> phase = 2
            phase == 0 && t >= cfg.ready -> { phase = 1; buzz(false) }
            phase == 2 && t < cfg.ready - 15 -> { t0 = minOf(t, cfg.ready - 40); phase = 0 }          // moteur refroidi : on réarme
        }
    }
    LaunchedEffect(phase) { if (phase == 1) { delay(3000); phase = 2 } }

    val tr = rememberInfiniteTransition(label = "warm")
    val pulse by tr.animateFloat(0.45f, 1f, infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "pa")
    val ripple by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "rp")

    if (!cfg.enabled || temp == null || phase < 0 || phase == 2) return null
    val p = ((temp - t0) / (cfg.ready - t0).coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f)
    val blue = Color(0xFF3B82F6); val orange = Color(0xFFFB923C); val red = Color(0xFFEF4444)
    val col = when (phase) {
        0 -> if (p < 0.6f) lerp(blue, orange, p / 0.6f) else lerp(orange, red, (p - 0.6f) / 0.4f)   // de plus en plus rouge
        1 -> Color(0xFF4ADE80)
        else -> Color(0xFFFF4D4D)
    }
    return WarmUi(phase, p, temp, col, pulse, ripple)
}
