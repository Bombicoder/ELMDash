@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package fr.elmdash

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import fr.elmdash.core.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startObd() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val crash = CrashReport.last(this)
        EngineHost.ensureStarted(this)      // le moteur (et la simulation) démarre toujours, permission Bluetooth ou non
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(color = Color(0xFF020406), contentColor = Color.White) {
                    var report by remember { mutableStateOf(crash) }
                    val r = report
                    if (r != null) CrashScreen(r) { CrashReport.clear(this@MainActivity); report = null; askPermissions() }
                    else DashApp(ConfigStore(this@MainActivity)) { askPermissions() }
                }
            }
        }
        if (crash == null) askPermissions()
    }

    private fun askPermissions() {
        val need = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) startObd() else permLauncher.launch(need.toTypedArray())
    }

    private fun startObd() {
        EngineHost.ensureStarted(this)
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Telemetry.status.value = "Autorise « Appareils à proximité » (Paramètres Android › Applications › ELMDash) pour utiliser le boîtier"
            return
        }
        runCatching { ContextCompat.startForegroundService(this, Intent(this, ObdService::class.java)) }
        runCatching { WearSync.pushConfig(this, ConfigStore(this)) }
    }
}

@Composable
fun CrashScreen(trace: String, onContinue: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("L'appli s'est arrêtée la dernière fois", fontSize = 20.sp)
        Text("Copie ce rapport et envoie-le pour qu'on corrige le problème.", fontSize = 13.sp)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { Text(trace, fontSize = 10.sp, color = GaugeAmber) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("crash", trace)) }) { Text("Copier") }
            OutlinedButton(onContinue) { Text("Continuer") }
        }
    }
}

data class EditorTarget(val page: Int, val index: Int, val preset: GaugeCfg? = null)   // index -1 = nouveau cadran

/** État de l'appli : chaque modification est enregistrée tout de suite (plus de bouton « Enregistrer ») puis envoyée à la montre. */
class AppState(val store: ConfigStore) {
    var pages by mutableStateOf(store.pages)
        private set
    var settings by mutableStateOf(store.settings)
        private set
    var custom by mutableStateOf(store.customPids)
        private set
    var warm by mutableStateOf(store.warm)
        private set

    fun updatePages(p: List<PageCfg>) { pages = p; store.pages = p }
    fun updateSettings(s: Settings) { settings = s; store.settings = s }
    fun updateCustom(c: List<PidDef>) { custom = c; store.customPids = c }
    fun updateWarm(w: WarmCfg) { warm = w; store.warm = w }

    fun saveGauge(et: EditorTarget, cfg: GaugeCfg, target: Int) {
        if (pages.isEmpty()) { updatePages(listOf(PageCfg("Page 1", listOf(cfg)))); return }
        val lists = pages.map { it.gauges.toMutableList() }
        val t = target.coerceIn(0, lists.size - 1); val p = et.page.coerceIn(0, lists.size - 1)
        if (et.index >= 0 && et.index < lists[p].size) {
            if (t == p) lists[p][et.index] = cfg else { lists[p].removeAt(et.index); lists[t].add(cfg) }
        } else lists[t].add(cfg)
        updatePages(pages.mapIndexed { i, pg -> pg.copy(gauges = lists[i]) })
    }

    fun deleteGauge(et: EditorTarget) {
        updatePages(pages.mapIndexed { i, pg -> if (i == et.page) pg.copy(gauges = pg.gauges.filterIndexed { j, _ -> j != et.index }) else pg })
    }
}

enum class Screen { DASH, SETTINGS, SIM, DTC }

@Composable
fun DashApp(store: ConfigStore, askPermissions: () -> Unit) {
    val ctx = LocalContext.current
    val app = remember { AppState(store) }
    var screen by remember { mutableStateOf(Screen.DASH) }
    // Envoi à la montre 0,7 s après la dernière modification
    LaunchedEffect(app.pages, app.settings, app.custom, app.warm) { delay(700); runCatching { WearSync.pushConfig(ctx, store) } }
    BackHandler(enabled = screen != Screen.DASH) { screen = Screen.DASH }

    when (screen) {
        Screen.DASH -> Dashboard(app, { screen = Screen.SETTINGS }, { screen = Screen.SIM }, { screen = Screen.DTC }, askPermissions)
        Screen.SETTINGS -> SettingsScreen(app, { screen = Screen.DASH }, { screen = Screen.SIM })
        Screen.SIM -> SimScreen(app) { screen = Screen.DASH }
        Screen.DTC -> DtcScreen { screen = Screen.DASH }
    }
}

@Composable
fun Dashboard(app: AppState, onSettings: () -> Unit, onSim: () -> Unit, onDtc: () -> Unit, askPermissions: () -> Unit) {
    val values by Telemetry.values.collectAsState()
    val status by Telemetry.status.collectAsState()
    val link by Telemetry.link.collectAsState()
    val mil by Telemetry.mil.collectAsState()
    val dtcCount by Telemetry.dtcCount.collectAsState()
    val pages = app.pages
    val scope = rememberCoroutineScope()
    var editor by remember { mutableStateOf<EditorTarget?>(null) }
    val pager = rememberPagerState { pages.size.coerceAtLeast(1) }

    LaunchedEffect(pager.currentPage, pages) {
        Telemetry.phoneVisible.value = pages.getOrNull(pager.currentPage)?.gauges?.flatMap { Pids.idsOf(it) }?.toSet() ?: emptySet()
    }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            LinkLeds(link, 11.dp)
            Spacer(Modifier.width(10.dp))
            Text(status, fontSize = 12.sp, maxLines = 2, modifier = Modifier.weight(1f).clickable { if (link == Link.OFF) askPermissions() })
            MilBadge(mil, dtcCount) { onDtc() }
            if (app.settings.simulate) Text(" SIM ", fontSize = 11.sp, color = GaugeViolet, modifier = Modifier.clickable { onSim() })
            Text("⟲", fontSize = 24.sp, modifier = Modifier.clickable { Telemetry.tripReset.value = true }.padding(horizontal = 8.dp))
            Text("⚙", fontSize = 24.sp, modifier = Modifier.clickable { onSettings() }.padding(horizontal = 8.dp))
        }
        if (pages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                Button({ app.updatePages(listOf(PageCfg("Page 1", emptyList()))) }) { Text("Créer une première page") }
            }
        } else {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pages.forEachIndexed { i, p ->
                    FilterChip(selected = pager.currentPage == i, onClick = { scope.launch { pager.animateScrollToPage(i) } }, label = { Text(p.name) })
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                HorizontalPager(pager, Modifier.fillMaxSize()) { i ->
                    GaugePage(pages[i].gauges, values, app.custom, onGaugeClick = { gi -> editor = EditorTarget(i, gi) })
                }
                FloatingActionButton(
                    onClick = { editor = EditorTarget(pager.currentPage.coerceIn(0, pages.size - 1), -1) },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp),
                    containerColor = GaugeCyan, contentColor = Color.Black
                ) { Text("＋", fontSize = 26.sp) }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), Arrangement.Center) {
                repeat(pages.size) { i ->
                    Box(Modifier.padding(4.dp).size(if (i == pager.currentPage) 10.dp else 6.dp).background(if (i == pager.currentPage) GaugeCyan else Color(0x44FFFFFF), CircleShape))
                }
            }
        }
    }

    editor?.let { et ->
        val initial = pages.getOrNull(et.page)?.gauges?.getOrNull(et.index)
        GaugeEditorDialog(initial, et.preset, pages, et.page, app.custom,
            onSave = { cfg, target -> app.saveGauge(et, cfg, target); editor = null },
            onDelete = if (initial != null) ({ app.deleteGauge(et); editor = null }) else null,
            onDismiss = { editor = null })
    }
}
