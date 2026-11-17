@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package fr.elmdash.core

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

val GaugeCyan = Color(0xFF22D3EE)
val GaugeRed = Color(0xFFFF4D4D)
val GaugeAmber = Color(0xFFFFB020)
val GaugeViolet = Color(0xFFA78BFA)
val GaugeGreen = Color(0xFF4ADE80)
val GaugeGrey = Color(0xFF8AA0B2)
val SeriesColors = listOf(GaugeCyan, GaugeAmber, GaugeViolet, GaugeGreen)

/** true sur la montre (écran OLED) : fonds noirs purs, aucun rectangle de fond. */
val LocalOled = compositionLocalOf { false }

/** 0 → 1 quand la valeur approche de la limite (départ à 60 % de la plage jusqu'à la limite). */
private fun rampQ(v: Double?, min: Double, warn: Double?): Float {
    if (v == null || warn == null || warn <= min) return 0f
    val rs = min + (warn - min) * 0.6
    return ((v - rs) / (warn - rs)).toFloat().coerceIn(0f, 1f)
}
private fun rampColor(base: Color, q: Float): Color =
    if (q <= 0f) base else if (q < 0.5f) lerp(base, GaugeAmber, q * 2f) else lerp(GaugeAmber, GaugeRed, (q - 0.5f) * 2f)

/** Une lecture affichée sur un cadran (un cadran peut en porter jusqu'à 4). */
data class Reading(val label: String, val unit: String, val value: Double?, val decimals: Int, val color: Color) {
    fun text(): String = if (value == null) "--" else "%.${decimals}f".format(value)
}

private fun fracOf(v: Double?, min: Double, span: Double) = (((v ?: min) - min) / span).toFloat().coerceIn(0f, 1f)

@Composable
fun Gauge(style: Style, title: String, reads: List<Reading>, min: Double, max: Double, warn: Double?,
          kind: Kind = Kind.PLAIN, modifier: Modifier = Modifier) {
    if (reads.isEmpty()) return
    when (style) {
        Style.DIAL -> DialGauge(title, reads, min, max, warn, modifier)
        Style.CURVE -> CurveGauge(title, reads, min, max, warn, modifier.fillMaxSize())
        Style.NUMERIC -> NumericGauge(title, reads, warn, modifier.fillMaxSize())
        Style.BAR -> BarGauge(title, reads, min, max, warn, kind, modifier.fillMaxSize())
    }
}

/**
 * Manomètre : arc 240°, graduations, aiguille ; lectures supplémentaires = aiguilles fines de couleur.
 * Effet « shift light » : de la moitié de la plage jusqu'à la limite, TOUT le cadran (arc, graduations, aiguille, chiffres, fond)
 * vire progressivement à l'orange puis au rouge ; à la limite (rupteur) il clignote en rouge.
 * Sur la montre (OLED) : fond noir pur et peu de texte.
 */
@Composable
fun DialGauge(title: String, reads: List<Reading>, min: Double, max: Double, warn: Double?, modifier: Modifier = Modifier) {
    val warm = LocalWarm.current
    val oled = LocalOled.current
    val span = (max - min).coerceAtLeast(1e-6)
    val prim = reads.first()
    val frac by animateFloatAsState(fracOf(prim.value, min, span), tween(200, easing = LinearOutSlowInEasing), label = "frac")
    val extras = reads.drop(1)
    val extraFr = extras.map { fracOf(it.value, min, span) }
    val warnFrac = warn?.let { ((it - min) / span).toFloat().coerceIn(0f, 1f) }
    val limit = if (warnFrac != null && warnFrac > 0.08f) warnFrac else null
    val q = if (limit != null) ((frac - limit * 0.5f) / (limit * 0.5f)).coerceIn(0f, 1f) else 0f   // 0 à 50 % de la limite → 1 à la limite
    val over = limit != null && frac >= limit
    val blink by rememberInfiniteTransition(label = "shift").animateFloat(0f, 1f, infiniteRepeatable(tween(130), RepeatMode.Reverse), label = "b")
    val flash = if (over) blink else 0f
    val theme = if (over) lerp(GaugeRed, Color(0xFF7A0000), flash) else rampColor(prim.color, q)
    val glow = (0.35f * q + 0.65f * flash).coerceIn(0f, 1f)
    val bg = if (oled) Brush.radialGradient(listOf(lerp(Color(0xFF05090D), Color(0xFF4A0709), glow), Color.Black))
             else Brush.radialGradient(listOf(lerp(Color(0xFF0C141B), Color(0xFF4A0709), glow), Color(0xFF010305)))

    BoxWithConstraints(modifier.aspectRatio(1f).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        val w = maxWidth
        val d = LocalDensity.current
        fun sp(f: Float): TextUnit = with(d) { (w * f).toSp() }

        Canvas(Modifier.fillMaxSize()) {
            val st = size.minDimension * 0.075f
            val r = size.minDimension / 2f - st * 0.75f
            val c = center
            val tl = Offset(c.x - r, c.y - r); val sz = Size(2 * r, 2 * r)
            val start = 150f; val sweep = 240f

            drawArc(theme.copy(alpha = 0.14f), start, sweep, false, tl, sz, style = Stroke(st, cap = StrokeCap.Round))
            if (limit != null && limit < 1f)
                drawArc(Color(0x66FF4D4D), start + sweep * limit, sweep * (1f - limit), false, tl, sz, style = Stroke(st * 0.45f))

            val tickColor = lerp(Color.White, theme, q * 0.8f)
            for (i in 0..24) {
                val a = Math.toRadians((start + sweep * i / 24f).toDouble()); val major = i % 4 == 0
                val r1 = r - st * 0.85f; val r2 = r1 - st * (if (major) 0.8f else 0.45f)
                drawLine(tickColor.copy(alpha = if (major) 0.8f else (if (oled) 0.26f else 0.33f)),
                    Offset(c.x + r1 * cos(a).toFloat(), c.y + r1 * sin(a).toFloat()),
                    Offset(c.x + r2 * cos(a).toFloat(), c.y + r2 * sin(a).toFloat()), strokeWidth = if (major) 3f else 1.5f)
            }

            // Montée en température : jauge intérieure + ondes de fin de chauffe
            if (warm != null) {
                val rw = r - st * 2.3f; val wl = Offset(c.x - rw, c.y - rw); val ws = Size(2 * rw, 2 * rw)
                if (warm.phase == 0) {
                    drawArc(Color(0x443B82F6), start, sweep, false, wl, ws, style = Stroke(st * 0.3f, cap = StrokeCap.Round))
                    drawArc(warm.color, start, sweep * maxOf(warm.progress, 0.01f), false, wl, ws, style = Stroke(st * 0.3f, cap = StrokeCap.Round))
                } else {
                    drawArc(warm.color.copy(alpha = warm.pulse), start, sweep, false, wl, ws, style = Stroke(st * 0.42f, cap = StrokeCap.Round))
                    if (warm.phase == 1) listOf(warm.ripple, (warm.ripple + 0.5f) % 1f).forEach { qq ->
                        drawCircle(warm.color.copy(alpha = (1f - qq) * 0.55f), radius = rw * (0.25f + 0.75f * qq), center = c, style = Stroke(st * 0.22f))
                    }
                }
            }

            if (frac > 0.002f) rotate(start, c) {
                val brush = Brush.sweepGradient(0f to theme.copy(alpha = 0.45f), (sweep / 360f * frac) to theme, 1f to theme, center = c)
                drawArc(brush, 0f, sweep * frac, false, tl, sz, style = Stroke(st, cap = StrokeCap.Round))
            }
            extras.forEachIndexed { i, e ->
                val ea = Math.toRadians((start + sweep * extraFr[i]).toDouble())
                val ex = cos(ea).toFloat(); val ey = sin(ea).toFloat()
                drawCircle(e.color, st * 0.42f, Offset(c.x + r * ex, c.y + r * ey))
                drawLine(e.color, c, Offset(c.x + r * 0.36f * ex, c.y + r * 0.36f * ey), strokeWidth = st * 0.1f, cap = StrokeCap.Round)
            }
            val ang = Math.toRadians((start + sweep * frac).toDouble())
            val tipPos = Offset(c.x + r * cos(ang).toFloat(), c.y + r * sin(ang).toFloat())
            drawCircle(theme.copy(alpha = 0.30f), st * 1.25f, tipPos)
            drawCircle(Color.White, st * 0.28f, tipPos)
            val nl = r * (if (oled) 0.40f else 0.46f)
            drawLine(theme, c, Offset(c.x + nl * cos(ang).toFloat(), c.y + nl * sin(ang).toFloat()), strokeWidth = st * 0.16f, cap = StrokeCap.Round)
            drawCircle(Color(0xFF0B1118), st * 0.5f, c)
            drawCircle(theme, st * 0.5f, c, style = Stroke(st * 0.12f))
        }

        // Texte : valeur + unité sur UNE ligne, bien sous le pivot ; sur la montre, lectures supplémentaires sans libellé
        val valueColor = if (prim.value == null) GaugeGrey else if (over) theme else lerp(Color.White, theme, q)
        Column(Modifier.align(Alignment.Center).offset(y = w * (if (extras.isEmpty()) 0.19f else 0.165f)), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(if (prim.value == null) "–" else prim.text(), color = valueColor,
                    fontSize = sp(if (prim.value == null) 0.1f else if (extras.isEmpty()) 0.15f else 0.115f), fontWeight = FontWeight.Bold, maxLines = 1)
                if (prim.value != null) Text(" " + prim.unit, color = GaugeGrey, fontSize = sp(0.05f), maxLines = 1)
            }
            extras.forEach { e ->
                Text(if (oled) "● ${e.text()}" else "● ${e.text()} ${e.label.take(11)}", color = e.color, fontSize = sp(if (oled) 0.07f else 0.05f), maxLines = 1)
            }
        }
        if (!(oled && extras.size >= 2)) Text(warm?.text() ?: title, color = warm?.color ?: theme, fontSize = sp(if (warm != null) 0.065f else 0.055f), maxLines = 1,
            fontWeight = if (warm != null) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = w * (if (oled) 0.15f else 0.05f)))
    }
}

@Composable
private fun CardFrame(modifier: Modifier, content: @Composable BoxWithConstraintsScope.() -> Unit) {
    val warm = LocalWarm.current
    val oled = LocalOled.current
    BoxWithConstraints(modifier.clip(RoundedCornerShape(18.dp))
        .then(if (oled) Modifier else Modifier.background(Brush.verticalGradient(listOf(Color(0xFF0C141B), Color(0xFF020407)))))
        .padding(10.dp)) {
        content()
        if (warm != null) Canvas(Modifier.fillMaxSize()) {
            if (warm.phase == 0) drawLine(warm.color, Offset(0f, 0f), Offset(size.width * maxOf(warm.progress, 0.02f), 0f), 6f, StrokeCap.Round)
            else drawRoundRect(warm.color.copy(alpha = warm.pulse), style = Stroke(6f), cornerRadius = CornerRadius(30f))
        }
    }
}

@Composable
private fun TitleRow(title: String, unit: String, small: TextUnit) {
    val warm = LocalWarm.current
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
        Text(warm?.text() ?: title, color = warm?.color ?: GaugeCyan, fontSize = small, maxLines = 1, modifier = Modifier.weight(1f))
        Text(unit, color = GaugeGrey, fontSize = small)
    }
}

@Composable
private fun rememberHistory(reads: List<Reading>): List<MutableList<Float>> {
    val n = reads.size
    val hist = remember(n) { List(n) { mutableStateListOf<Float>() } }
    val cur = rememberUpdatedState(reads)
    LaunchedEffect(n) {
        while (true) {
            delay(500)
            cur.value.forEachIndexed { i, r ->
                if (i < hist.size) { val h = hist[i]; h.add((r.value ?: Double.NaN).toFloat()); if (h.size > 120) h.removeAt(0) }
            }
        }
    }
    return hist
}

/** Courbe : historique des ~60 dernières secondes, toutes les lectures superposées. */
@Composable
private fun CurveGauge(title: String, reads: List<Reading>, min: Double, max: Double, warn: Double?, modifier: Modifier) {
    val oled = LocalOled.current
    val hist = rememberHistory(reads)
    val span = (max - min).coerceAtLeast(1e-6)
    CardFrame(modifier) {
        val d = LocalDensity.current
        val small = with(d) { minOf(maxWidth * 0.07f, maxHeight * 0.09f).toSp() }
        val bigv = with(d) { minOf(maxWidth * 0.13f, maxHeight * 0.17f).toSp() }
        Column(Modifier.fillMaxSize()) {
            TitleRow(title, reads[0].unit, small)
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                reads.forEachIndexed { i, r ->
                    Column {
                        Text(r.text(), color = r.color, fontSize = if (i == 0) bigv else small, fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                        if (!oled) Text(r.label.take(16), color = GaugeGrey, fontSize = small, maxLines = 1)
                    }
                }
            }
            Canvas(Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp)) {
                val w = size.width; val h = size.height
                for (k in 0..3) { val y = h * k / 3f; drawLine(Color(0x22FFFFFF), Offset(0f, y), Offset(w, y), 1f) }
                if (warn != null) {
                    val wy = h * (1f - ((warn - min) / span).toFloat().coerceIn(0f, 1f))
                    drawLine(Color(0x88FF4D4D), Offset(0f, wy), Offset(w, wy), 2f)
                }
                val step = w / 119f
                hist.forEachIndexed { si, hs ->
                    val color = reads.getOrNull(si)?.color ?: GaugeCyan
                    val path = Path(); var started = false; var lx = 0f; var ly = 0f
                    hs.forEachIndexed { i, v ->
                        if (v.isNaN()) { started = false } else {
                            val x = w - (hs.size - 1 - i) * step
                            val y = h * (1f - ((v - min) / span).toFloat().coerceIn(0f, 1f))
                            if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
                            lx = x; ly = y
                        }
                    }
                    drawPath(path, color.copy(alpha = 0.18f), style = Stroke(9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawPath(path, color, style = Stroke(3.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    if (hs.isNotEmpty() && !hs.last().isNaN()) drawCircle(color, 5f, Offset(lx, ly))
                }
            }
        }
    }
}

/** Valeur numérique : grand chiffre lisible, lectures supplémentaires dessous, min/max de la session. */
@Composable
private fun NumericGauge(title: String, reads: List<Reading>, warn: Double?, modifier: Modifier) {
    val oled = LocalOled.current
    val prim = reads.first()
    var lo by remember { mutableStateOf<Double?>(null) }
    var hi by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(prim.value) { prim.value?.let { lo = minOf(lo ?: it, it); hi = maxOf(hi ?: it, it) } }
    val hot = prim.value != null && warn != null && prim.value >= warn
    CardFrame(modifier) {
        val d = LocalDensity.current
        val multi = reads.size > 1
        val big = with(d) { minOf(maxWidth * (if (multi) 0.2f else 0.27f), maxHeight * (if (multi) 0.3f else 0.42f)).toSp() }
        val small = with(d) { minOf(maxWidth * 0.075f, maxHeight * 0.1f).toSp() }
        val l = lo; val h = hi
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally) {
            TitleRow(title, prim.unit, small)
            Text(prim.text(), color = if (hot) GaugeRed else Color.White, fontSize = big, fontWeight = FontWeight.Bold, maxLines = 1)
            if (multi) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                reads.drop(1).forEach { r -> Text(if (oled) "● ${r.text()} ${r.unit}" else "● ${r.text()} ${r.unit}  ${r.label.take(18)}", color = r.color, fontSize = small, maxLines = 1) }
            }
            if (!oled && l != null && h != null) Text("min %.${prim.decimals}f · max %.${prim.decimals}f".format(l, h), color = GaugeGrey, fontSize = small, maxLines = 1)
        }
    }
}

/** Barre : une par lecture. Variantes automatiques : simple, température (échelle bleu→rouge) ou centrée (±). */
@Composable
private fun BarGauge(title: String, reads: List<Reading>, min: Double, max: Double, warn: Double?, kind: Kind, modifier: Modifier) {
    CardFrame(modifier) {
        val d = LocalDensity.current
        val rows = reads.size
        val small = with(d) { minOf(maxWidth * 0.07f, maxHeight * 0.1f).toSp() }
        val big = with(d) { minOf(maxWidth * 0.17f, maxHeight * (if (rows > 1) 0.18f else 0.28f)).toSp() }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TitleRow(title, reads[0].unit, small)
            reads.forEachIndexed { i, r -> BarRow(r, min, max, if (i == 0) warn else null, kind, big, small, Modifier.weight(1f).fillMaxWidth()) }
        }
    }
}

@Composable
private fun BarRow(r: Reading, min: Double, max: Double, warn: Double?, kind: Kind, big: TextUnit, small: TextUnit, modifier: Modifier) {
    val span = (max - min).coerceAtLeast(1e-6)
    val frac by animateFloatAsState(fracOf(r.value, min, span), tween(220), label = "bar")
    val mid = (min + max) / 2
    val v = r.value
    val hot = v != null && warn != null && (if (kind == Kind.CENTER) abs(v - mid) >= warn else v >= warn)
    val tip = if (hot) GaugeRed else rampColor(r.color, if (kind == Kind.PLAIN) rampQ(v, min, warn) else 0f)
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.Bottom) {
            Text(r.text(), color = if (hot) GaugeRed else Color.White, fontSize = big, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(r.label.take(20), color = r.color, fontSize = small, maxLines = 1)
        }
        Canvas(Modifier.fillMaxWidth().height(12.dp)) {
            val w = size.width; val h = size.height; val cr = CornerRadius(h / 2)
            when (kind) {
                Kind.PLAIN -> {
                    drawRoundRect(Color(0x1FFFFFFF), size = Size(w, h), cornerRadius = cr)
                    drawRoundRect(Brush.horizontalGradient(listOf(r.color.copy(alpha = 0.45f), tip)), size = Size(maxOf(h, w * frac), h), cornerRadius = cr)
                    if (warn != null) { val x = w * ((warn - min) / span).toFloat().coerceIn(0f, 1f); drawLine(GaugeRed, Offset(x, -2f), Offset(x, h + 2f), 3f) }
                }
                Kind.TEMP -> {
                    drawRoundRect(Brush.horizontalGradient(listOf(Color(0xFF3B82F6), Color(0xFF22D3EE), Color(0xFF4ADE80), Color(0xFFFBBF24), Color(0xFFEF4444))), size = Size(w, h), cornerRadius = cr, alpha = 0.85f)
                    val x = w * frac
                    drawCircle(Color.White.copy(alpha = 0.3f), h * 1.1f, Offset(x, h / 2)); drawLine(Color.White, Offset(x, -4f), Offset(x, h + 4f), 5f, StrokeCap.Round)
                }
                Kind.CENTER -> {
                    drawRoundRect(Color(0x1FFFFFFF), size = Size(w, h), cornerRadius = cr)
                    val cx = w / 2; val x = w * frac
                    drawRoundRect(tip, topLeft = Offset(minOf(cx, x), 0f), size = Size(abs(x - cx).coerceAtLeast(4f), h), cornerRadius = cr)
                    drawLine(Color.White, Offset(cx, -3f), Offset(cx, h + 3f), 3f)
                }
            }
        }
    }
}

@Composable
fun GaugeCell(g: GaugeCfg, values: Map<String, Double>, custom: List<PidDef>, modifier: Modifier = Modifier) {
    val m = Pids.meta(g.pid, custom)
    val reads = Pids.idsOf(g).take(4).mapIndexed { i, id ->
        val mm = Pids.meta(id, custom)
        Reading(mm.label, mm.unit, values[id], if (i == 0) g.decimals else mm.decimals, SeriesColors[i % SeriesColors.size])
    }
    val warm = if (g.warm) LocalWarm.current else null     // la jauge de chauffe n'apparaît que sur les cadrans où elle est cochée
    CompositionLocalProvider(LocalWarm provides warm) {
        Gauge(g.style ?: m.style, g.label ?: m.label, reads, g.min, g.max, g.warn, m.kind, modifier)
    }
}

/** Page de cadrans : la grille s'adapte (1 → grand, 2 → empilés, 3-6 → 2 colonnes, 7+ → 3 colonnes), rien n'est jamais écrasé. */
@Composable
fun GaugePage(gauges: List<GaugeCfg>, values: Map<String, Double>, custom: List<PidDef>, modifier: Modifier = Modifier,
              cellPad: Dp = 6.dp, cardInset: PaddingValues = PaddingValues(0.dp), onGaugeClick: ((Int) -> Unit)? = null) {
    val cells = gauges.take(12)
    val cols = when { cells.size <= 2 -> 1; cells.size <= 6 -> 2; else -> 3 }
    Column(modifier.fillMaxSize()) {
        if (cells.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) { Text("Page vide : appuie sur ＋", color = GaugeGrey) }
        cells.chunked(cols).forEachIndexed { rowIdx, row ->
            Row(Modifier.weight(1f).fillMaxWidth()) {
                row.forEachIndexed { colIdx, g ->
                    val idx = rowIdx * cols + colIdx
                    val st = g.style ?: Pids.meta(g.pid, custom).style
                    Box(
                        Modifier.weight(1f).fillMaxHeight().padding(cellPad)
                            .then(if (st == Style.DIAL) Modifier else Modifier.padding(cardInset))
                            .then(if (onGaugeClick != null) Modifier.clickable { onGaugeClick(idx) } else Modifier),
                        Alignment.Center
                    ) { GaugeCell(g, values, custom) }
                }
            }
        }
    }
}
