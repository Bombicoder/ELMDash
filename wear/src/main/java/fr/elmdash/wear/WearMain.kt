@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package fr.elmdash.wear

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import fr.elmdash.core.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

object WatchState {
    val values = MutableStateFlow<Map<String, Double>>(emptyMap())
    val pages = MutableStateFlow<List<PageCfg>>(emptyList())
    val custom = MutableStateFlow<List<PidDef>>(emptyList())
    val warm = MutableStateFlow(WarmCfg())
    val settings = MutableStateFlow(Settings())
    val visible = MutableStateFlow<Set<String>>(emptySet())
    val direct = MutableStateFlow(false)          // connexion directe au boîtier, sans téléphone
    val sim = MutableStateFlow(false)
    val mac = MutableStateFlow("")
    val bright = MutableStateFlow(0.8f)           // luminosité fixe de l'appli (jamais automatique)
    val scale = MutableStateFlow(1f)              // taille du cadran
    val rot = MutableStateFlow(0f)                // rotation (position de la couronne)
    @Volatile var lastMsg = 0L
    private var loaded = false
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("w", Context.MODE_PRIVATE)

    fun ensure(c: Context) {
        if (loaded) return; loaded = true
        val p = sp(c)
        direct.value = p.getBoolean("direct", false); sim.value = p.getBoolean("sim", false); mac.value = p.getString("mac", "") ?: ""
        bright.value = p.getFloat("bright", 0.8f); scale.value = p.getFloat("scale", 1f); rot.value = p.getFloat("rot", 0f)
        p.getString("cfg", null)?.let { applyConfig(c, it, false) }
    }
    fun setDirect(c: Context, v: Boolean) { direct.value = v; sp(c).edit().putBoolean("direct", v).apply() }
    fun setSim(c: Context, v: Boolean) { sim.value = v; sp(c).edit().putBoolean("sim", v).apply() }
    fun setMac(c: Context, v: String) { mac.value = v; sp(c).edit().putString("mac", v).apply() }
    fun setBright(c: Context, v: Float) { bright.value = v.coerceIn(0.05f, 1f); sp(c).edit().putFloat("bright", bright.value).apply() }
    fun addBright(c: Context, d: Float) = setBright(c, ((bright.value + d) * 20).roundToInt() / 20f)
    fun setScale(c: Context, v: Float) { scale.value = v.coerceIn(0.6f, 1.6f); sp(c).edit().putFloat("scale", scale.value).apply() }
    fun setRot(c: Context, v: Float) { rot.value = ((v % 360f) + 360f) % 360f; sp(c).edit().putFloat("rot", rot.value).apply() }

    fun applyConfig(c: Context, json: String, applyScale: Boolean = true) {
        runCatching { Codec.configFromJson(json) }.onSuccess { cfg ->
            pages.value = cfg.pages; custom.value = cfg.custom; warm.value = cfg.warm; settings.value = cfg.settings
            if (applyScale) setScale(c, cfg.settings.watchScale.toFloat())   // la taille réglée sur le téléphone s'applique à la montre
            sp(c).edit().putString("cfg", json).apply()
        }
    }
}

class WearReceiver : WearableListenerService() {
    override fun onMessageReceived(e: MessageEvent) {
        WatchState.ensure(this)
        if (WatchState.direct.value) return           // en connexion directe, on ignore le téléphone
        runCatching {
            when (e.path) {
                "/telemetry" -> {
                    val o = JSONObject(String(e.data)); WatchState.lastMsg = System.currentTimeMillis()
                    val vs = o.keys().asSequence().filter { !it.startsWith("_") }.associateWith { o.getDouble(it) }
                    if (vs.isNotEmpty()) WatchState.values.value = vs
                    val l = o.optInt("_link", -1)
                    if (l >= 0) { Telemetry.link.value = Link.values()[l]; Telemetry.status.value = Link.values()[l].label }
                    if (o.has("_mil")) { Telemetry.mil.value = o.getDouble("_mil") > 0.5; Telemetry.dtcCount.value = o.optDouble("_dtc", 0.0).toInt() }
                }
                "/dtc" -> {
                    val o = JSONObject(String(e.data)); val a = o.getJSONArray("codes")
                    Telemetry.mil.value = o.getBoolean("mil"); Telemetry.dtcList.value = (0 until a.length()).map { a.getString(it) }
                }
                else -> Unit
            }
        }
    }
    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { ev ->
            if (ev.dataItem.uri.path == "/config")
                DataMapItem.fromDataItem(ev.dataItem).dataMap.getString("json")?.let { WatchState.applyConfig(this, it) }
        }
    }
}

class WearMain : ComponentActivity() {
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // écran toujours allumé, jamais de veille
        WatchState.ensure(this)
        applyBrightness(WatchState.bright.value)
        val need = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) permLauncher.launch(need.toTypedArray())
        runCatching { WearSync.hello(this) }
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { WatchApp(this) } }
    }

    /** Luminosité fixée par l'appli : la luminosité automatique du système n'a plus d'effet tant que l'appli est affichée. */
    fun applyBrightness(b: Float) { val lp = window.attributes; lp.screenBrightness = b.coerceIn(0.02f, 1f); window.attributes = lp }
}

@Suppress("DEPRECATION")
private fun buzz(ctx: Context, long: Boolean) {
    val v = ctx.getSystemService(Vibrator::class.java) ?: return
    v.vibrate(VibrationEffect.createWaveform(if (long) longArrayOf(0, 400, 150, 400, 150, 400) else longArrayOf(0, 120, 100, 120), -1))
}

@Composable
fun WatchApp(act: WearMain) {
    val ctx = LocalContext.current; val view = LocalView.current; val scope = rememberCoroutineScope()
    val pages by WatchState.pages.collectAsState(); val values by WatchState.values.collectAsState(); val custom by WatchState.custom.collectAsState()
    val warmCfg by WatchState.warm.collectAsState(); val direct by WatchState.direct.collectAsState(); val st by WatchState.settings.collectAsState()
    val bright by WatchState.bright.collectAsState(); val scale by WatchState.scale.collectAsState(); val rot by WatchState.rot.collectAsState()
    val link by Telemetry.link.collectAsState(); val mil by Telemetry.mil.collectAsState()
    val gauges = remember(pages) { pages.flatMap { it.gauges } }
    val dtcPage = st.watchDtc
    val total = gauges.size + (if (dtcPage) 1 else 0) + 1            // + codes défaut + réglages
    val pager = rememberPagerState { total }
    val needWarm = warmCfg.enabled && gauges.any { it.warm }                 // au moins un cadran a la jauge de chauffe cochée
    val warmUi = rememberWarmUi(values[warmCfg.pid], warmCfg.copy(enabled = needWarm)) { long -> buzz(ctx, long) }

    // Luminosité : fixée par l'appli, modifiable seulement par double-tap (haut = + / bas = −)
    var showB by remember { mutableStateOf(false) }; var firstB by remember { mutableStateOf(true) }
    LaunchedEffect(bright) { act.applyBrightness(bright); if (firstB) firstB = false else { showB = true; delay(2200); showB = false } }

    // Connexion directe (sans téléphone) : démarre / arrête le service
    LaunchedEffect(direct) {
        val i = Intent(ctx, WatchObdService::class.java)
        if (direct) runCatching { ContextCompat.startForegroundService(ctx, i) } else ctx.stopService(i)
    }
    // Téléphone injoignable ?
    LaunchedEffect(direct) {
        while (!direct) { delay(2000); if (System.currentTimeMillis() - WatchState.lastMsg > 6000) { Telemetry.link.value = Link.OFF; Telemetry.status.value = "Téléphone hors de portée ou appli fermée" } }
    }
    // PID à lire : le(s) cadran(s) affiché(s) + la température surveillée, quel que soit le cadran
    LaunchedEffect(pager.currentPage, gauges, warmCfg, direct) {
        val g = gauges.getOrNull(pager.currentPage)
        val ids = ((if (g != null) Pids.idsOf(g) else emptyList()) + (if (needWarm) listOf(warmCfg.pid) else emptyList())).distinct()
        if (direct) WatchState.visible.value = ids.toSet() else runCatching { WearSync.sendVisible(ctx, ids) }
    }

    val fr = remember { FocusRequester() }; var acc by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(pager.currentPage) { delay(120); if (pager.currentPage < gauges.size) runCatching { fr.requestFocus() } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize()
            .graphicsLayer { rotationZ = rot; scaleX = scale; scaleY = scale }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { o -> WatchState.addBright(ctx, if (o.y < size.height / 2f) 0.1f else -0.1f) }) }) {

            CompositionLocalProvider(LocalWarm provides warmUi, LocalOled provides true) {
                HorizontalPager(pager, Modifier.fillMaxSize()
                    .onRotaryScrollEvent { e ->                      // molette : change de cadran
                        acc += e.verticalScrollPixels
                        if (abs(acc) > 60f) {
                            val dir = if (acc > 0) 1 else -1; acc = 0f
                            scope.launch { pager.animateScrollToPage((pager.currentPage + dir).coerceIn(0, total - 1)) }
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                        true
                    }.focusRequester(fr).focusable()
                ) { i ->
                    when {
                        i < gauges.size -> GaugePage(listOf(gauges[i]), values, custom, cellPad = 0.dp, cardInset = PaddingValues(horizontal = 20.dp, vertical = 38.dp))
                        dtcPage && i == gauges.size -> WatchDtcPage(isCurrent = pager.currentPage == i)
                        else -> WatchSettingsPage(isCurrent = pager.currentPage == i)
                    }
                }
            }

            Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                LinkLeds(link, 5.dp); Spacer(Modifier.width(4.dp))
                if (mil && dtcPage) { Text("⚠", color = GaugeAmber, fontSize = 10.sp); Spacer(Modifier.width(3.dp)) }
                if (total <= 8) repeat(total) { i ->
                    Box(Modifier.padding(1.5.dp).size(if (i == pager.currentPage) 5.dp else 3.dp).background(if (i == pager.currentPage) GaugeCyan else Color(0x55FFFFFF), CircleShape))
                } else Text("${pager.currentPage + 1}/$total", fontSize = 8.sp, color = Color(0x88FFFFFF))
            }
            AnimatedVisibility(showB, Modifier.align(Alignment.Center)) { BrightnessBadge(bright) }
        }
    }
}

@Composable
private fun BrightnessBadge(b: Float) {
    Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xEE000000)).padding(horizontal = 16.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("☀ ${(b * 100).roundToInt()} %", color = Color.White, fontSize = 18.sp)
        Spacer(Modifier.height(4.dp))
        Box(Modifier.width(84.dp).height(6.dp).clip(CircleShape).background(Color(0x33FFFFFF))) { Box(Modifier.fillMaxHeight().fillMaxWidth(b).background(GaugeCyan)) }
    }
}

@Composable
private fun Chip(text: String, on: Boolean = false, onClick: () -> Unit) {
    Text(text, fontSize = 12.sp, color = if (on) Color.Black else Color.White, textAlign = TextAlign.Center,
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) GaugeCyan else Color(0xFF1C1C1C)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp))
}

/** Page défilante à la molette. */
@Composable
private fun WatchScrollPage(isCurrent: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val scope = rememberCoroutineScope(); val scroll = rememberScrollState(); val fr = remember { FocusRequester() }
    LaunchedEffect(isCurrent) { if (isCurrent) { delay(150); runCatching { fr.requestFocus() } } }
    Column(
        Modifier.fillMaxSize().onRotaryScrollEvent { scope.launch { scroll.scrollBy(it.verticalScrollPixels) }; true }
            .focusRequester(fr).focusable().verticalScroll(scroll).padding(horizontal = 30.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally, content = content
    )
}

@Composable
fun WatchDtcPage(isCurrent: Boolean) {
    val ctx = LocalContext.current
    val mil by Telemetry.mil.collectAsState(); val count by Telemetry.dtcCount.collectAsState(); val list by Telemetry.dtcList.collectAsState()
    val busy by Telemetry.dtcBusy.collectAsState(); val direct by WatchState.direct.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(confirm) { if (confirm) { delay(3000); confirm = false } }
    fun request(r: String) { if (direct) Telemetry.dtcRequest.value = r else runCatching { WearSync.sendDtcRequest(ctx, r) } }
    WatchScrollPage(isCurrent) {
        Text("Codes défaut", fontSize = 13.sp, color = Color.White)
        Text(if (mil) "⚠ Témoin moteur ALLUMÉ" else "Témoin moteur éteint", fontSize = 12.sp, color = if (mil) GaugeAmber else GaugeGreen)
        if (count > 0 && list.isEmpty()) Text("$count code(s) à lire", fontSize = 11.sp, color = GaugeGrey)
        Chip(if (busy) "Lecture…" else "Lire les codes") { request("READ") }
        Chip(if (confirm) "Confirmer l'effacement ?" else "Effacer", confirm) { if (confirm) { request("CLEAR"); confirm = false } else confirm = true }
        list.forEach { code -> Text("$code\n${Dtc.describe(code)}", fontSize = 10.sp, color = Color.White, textAlign = TextAlign.Center) }
    }
}

@Composable
fun WatchSettingsPage(isCurrent: Boolean) {
    val ctx = LocalContext.current
    val direct by WatchState.direct.collectAsState(); val sim by WatchState.sim.collectAsState(); val mac by WatchState.mac.collectAsState()
    val scale by WatchState.scale.collectAsState(); val rot by WatchState.rot.collectAsState(); val bright by WatchState.bright.collectAsState()
    val link by Telemetry.link.collectAsState(); val status by Telemetry.status.collectAsState()
    val devices = remember(direct) { bondedDevices(ctx).map { (runCatching { it.name }.getOrNull() ?: "?") to it.address } }
    WatchScrollPage(isCurrent) {
        Text("Réglages montre", fontSize = 13.sp, color = Color.White)
        LinkLeds(link, 9.dp, labels = false)
        Text(link.label, fontSize = 11.sp, color = Color.White)
        Text(status, fontSize = 9.sp, color = GaugeGrey, textAlign = TextAlign.Center)
        Chip(if (direct) "Connexion directe : OUI" else "Connexion directe : NON", direct) { WatchState.setDirect(ctx, !direct) }
        Chip(if (sim) "Simulation : oui" else "Simulation : non", sim) { WatchState.setSim(ctx, !sim) }
        Text("Boîtier (appairé à la montre)", fontSize = 10.sp, color = GaugeGrey)
        devices.forEach { (n, a) -> Chip(n, mac == a) { WatchState.setMac(ctx, a) } }
        if (devices.isEmpty()) Text("Appaire l'ELM327 dans Réglages › Connectivité › Bluetooth de la montre", fontSize = 9.sp, color = GaugeGrey, textAlign = TextAlign.Center)
        Text("Taille du cadran ${(scale * 100).roundToInt()} %", fontSize = 11.sp, color = Color.White)
        Slider(scale, { WatchState.setScale(ctx, it) }, valueRange = 0.6f..1.6f, modifier = Modifier.width(150.dp))
        Text("Rotation (couronne) ${rot.roundToInt()}°", fontSize = 11.sp, color = Color.White)
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { listOf(0f, 90f, 180f, 270f).forEach { a -> Chip("${a.toInt()}°", rot == a) { WatchState.setRot(ctx, a) } } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Chip("−5°") { WatchState.setRot(ctx, rot - 5f) }; Chip("+5°") { WatchState.setRot(ctx, rot + 5f) } }
        Text("Luminosité ${(bright * 100).roundToInt()} %", fontSize = 11.sp, color = Color.White)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Chip("−") { WatchState.addBright(ctx, -0.1f) }; Chip("+") { WatchState.addBright(ctx, 0.1f) } }
        Text("Double-tap en haut / en bas de l'écran : luminosité. Molette : changer de page.", fontSize = 9.sp, color = GaugeGrey, textAlign = TextAlign.Center)
    }
}
