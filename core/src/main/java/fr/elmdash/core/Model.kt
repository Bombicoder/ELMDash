package fr.elmdash.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Les 4 types d'affichage, assignables à n'importe quelle donnée. */
enum class Style {
    DIAL, CURVE, NUMERIC, BAR;
    fun fr() = when (this) { DIAL -> "Manomètre"; CURVE -> "Courbe"; NUMERIC -> "Numérique"; BAR -> "Barre" }
    fun icon() = when (this) { DIAL -> "◔"; CURVE -> "∿"; NUMERIC -> "123"; BAR -> "▬" }
}

/** Variante de la barre : simple, température (échelle bleu→rouge) ou centrée (valeurs ±). */
enum class Kind { PLAIN, TEMP, CENTER }

/** Protocole de dialogue avec le calculateur. */
enum class ProtoMode(val label: String, val desc: String) {
    CLASSIC("OBD-II ancien (ISO 9141 / J1850)", "Défaut. Véhicules 1996-2003, par ex. Ford Probe 1997 : ISO 9141-2, KWP, J1850, puis CAN."),
    ISO("K-line seul (ISO 9141 / KWP)", "ISO 9141-2, puis KWP lent et rapide. Connexion la plus rapide sur les véhicules K-line."),
    STANDARD("OBD-II standard récent", "Détection automatique puis CAN ISO 15765-4. Pour les véhicules récents."),
    FORSCAN("FORScan (CAN + en-têtes ECU)", "CAN 500 kbps, adressage physique 7E0, requêtes Mode 22 (PID perso).")
}

/** État du voyant de liaison. */
enum class Link(val label: String) {
    OFF("Boîtier non connecté"), CONNECTING("Connexion…"), ADAPTER_OK("Boîtier OK · calculateur muet"),
    LIVE("Boîtier et calculateur OK"), SIM("Simulation")
}

data class PidDef(
    val id: String, val label: String, val unit: String,
    val header: String, val cmd: String, val bytes: Int,
    val scale: Double, val offset: Double, val signed: Boolean,
    val min: Double, val max: Double, val decimals: Int,
    val style: Style = Style.BAR, val group: String = "Perso", val warn: Double? = null, val kind: Kind = Kind.PLAIN
)

data class Meta(val id: String, val label: String, val unit: String, val min: Double, val max: Double, val decimals: Int,
                val style: Style, val group: String, val warn: Double?, val kind: Kind)

/** Un cadran : une lecture principale + des lectures supplémentaires affichées ensemble (même unité). */
data class GaugeCfg(val pid: String, val min: Double, val max: Double, val warn: Double?, val decimals: Int,
                    val label: String? = null, val style: Style? = null, val extra: List<String> = emptyList(),
                    val warm: Boolean = false)      // afficher la jauge de montée en température sur ce cadran
data class PageCfg(val name: String, val gauges: List<GaugeCfg>)
enum class FuelMode { INJECTOR, MAF }
data class WarmCfg(val enabled: Boolean = true, val pid: String = "COOLANT", val ready: Double = 85.0, val hot: Double = 108.0)

data class Settings(
    val deviceMac: String = "",
    val ethanolPct: Double = 85.0,
    val boxEnabled: Boolean = true,
    val ecuRefEthanol: Double = 5.0,
    val fuelMode: FuelMode = FuelMode.MAF,
    val cylinders: Int = 4,
    val injectorCcMin: Double = 200.0,
    val deadTimeMs: Double = 0.8,
    val simulate: Boolean = false,
    val watchDirect: Boolean = false,    // true = le téléphone ne touche pas au boîtier (la montre s'y connecte seule)
    val watchScale: Double = 1.0,        // taille du cadran sur la montre (1.0 = 100 %)
    val proto: ProtoMode = ProtoMode.CLASSIC,
    val watchDtc: Boolean = true,        // page « codes défaut » sur la montre
    val lang: String = "",              // "" = automatique (langue du système si disponible, sinon anglais)
    val injName: String = "",           // injecteur choisi dans le catalogue
    val fuelCal: Double = 1.0            // facteur d'étalonnage carburant (plein à plein)
)
data class Config(val pages: List<PageCfg>, val custom: List<PidDef>, val warm: WarmCfg, val settings: Settings)

/** Cadrans « malins » : plusieurs lectures sur un seul cadran. */
data class Combo(val title: String, val pid: String, val extra: List<String>, val style: Style)

object Pids {
    private fun s(id: String, label: String, unit: String, cmd: String, bytes: Int, scale: Double, offset: Double,
                  min: Double, max: Double, dec: Int, style: Style, group: String, warn: Double? = null, kind: Kind = Kind.PLAIN) =
        PidDef(id, label, unit, "7E0", cmd, bytes, scale, offset, false, min, max, dec, style, group, warn, kind)

    /** Mode 01 (SAE J1979) : identique sur tous les véhicules OBD-II (1996 → aujourd'hui). */
    val standard = listOf(
        s("RPM", "Régime", "tr/min", "010C", 2, 0.25, 0.0, 0.0, 7000.0, 0, Style.DIAL, "Moteur", 6200.0),
        s("SPEED", "Vitesse", "km/h", "010D", 1, 1.0, 0.0, 0.0, 240.0, 0, Style.NUMERIC, "Moteur"),
        s("LOAD", "Charge moteur", "%", "0104", 1, 100.0 / 255, 0.0, 0.0, 100.0, 0, Style.BAR, "Moteur"),
        s("ABSLOAD", "Charge absolue", "%", "0143", 2, 100.0 / 255, 0.0, 0.0, 200.0, 0, Style.BAR, "Moteur"),
        s("THROTTLE", "Papillon", "%", "0111", 1, 100.0 / 255, 0.0, 0.0, 100.0, 0, Style.BAR, "Moteur"),
        s("PEDAL", "Pédale", "%", "0149", 1, 100.0 / 255, 0.0, 0.0, 100.0, 0, Style.BAR, "Moteur"),
        s("TIMING", "Avance allumage", "°", "010E", 1, 0.5, -64.0, -20.0, 60.0, 0, Style.BAR, "Moteur", null, Kind.CENTER),
        s("RUNTIME", "Temps moteur", "s", "011F", 2, 1.0, 0.0, 0.0, 3600.0, 0, Style.NUMERIC, "Moteur"),
        s("COOLANT", "Eau", "°C", "0105", 1, 1.0, -40.0, 40.0, 130.0, 0, Style.BAR, "Températures", 105.0, Kind.TEMP),
        s("OIL", "Huile", "°C", "015C", 1, 1.0, -40.0, 40.0, 150.0, 0, Style.BAR, "Températures", 130.0, Kind.TEMP),
        s("IAT", "Air admission", "°C", "010F", 1, 1.0, -40.0, -20.0, 100.0, 0, Style.BAR, "Températures", 70.0, Kind.TEMP),
        s("AMBIENT", "Air extérieur", "°C", "0146", 1, 1.0, -40.0, -20.0, 50.0, 0, Style.BAR, "Températures", null, Kind.TEMP),
        s("CAT1", "Catalyseur", "°C", "013C", 2, 0.1, -40.0, 0.0, 1000.0, 0, Style.BAR, "Températures", 900.0, Kind.TEMP),
        s("MAP", "Pression admission", "kPa", "010B", 1, 1.0, 0.0, 0.0, 250.0, 0, Style.BAR, "Air et pression"),
        s("BARO", "Pression atmo.", "kPa", "0133", 1, 1.0, 0.0, 80.0, 110.0, 0, Style.NUMERIC, "Air et pression"),
        s("MAF", "Débit d'air", "g/s", "0110", 2, 0.01, 0.0, 0.0, 300.0, 1, Style.BAR, "Air et pression"),
        s("FUELP", "Pression carburant", "kPa", "010A", 1, 3.0, 0.0, 0.0, 765.0, 0, Style.BAR, "Carburant"),
        s("FRP", "Pression rampe", "kPa", "0123", 2, 10.0, 0.0, 0.0, 25000.0, 0, Style.BAR, "Carburant"),
        s("FUELLEVEL", "Niveau carburant", "%", "012F", 1, 100.0 / 255, 0.0, 0.0, 100.0, 0, Style.BAR, "Carburant"),
        s("FUELRATE_ECU", "Débit carburant", "L/h", "015E", 2, 0.05, 0.0, 0.0, 40.0, 1, Style.NUMERIC, "Carburant"),
        s("ETHANOL", "Éthanol", "%", "0152", 1, 100.0 / 255, 0.0, 0.0, 100.0, 0, Style.BAR, "Carburant"),
        s("LAMBDA", "Lambda piloté", "λ", "0144", 2, 2.0 / 65536, 0.0, 0.7, 1.3, 2, Style.CURVE, "Mélange", 0.15, Kind.CENTER),
        s("LAMBDA_B1", "Lambda mesuré", "λ", "0124", 2, 2.0 / 65536, 0.0, 0.7, 1.3, 2, Style.CURVE, "Mélange", 0.15, Kind.CENTER),
        s("O2_B1S1", "Sonde O2 amont", "V", "0114", 1, 0.005, 0.0, 0.0, 1.0, 2, Style.CURVE, "Mélange", null, Kind.CENTER),
        s("O2_B1S2", "Sonde O2 aval", "V", "0115", 1, 0.005, 0.0, 0.0, 1.0, 2, Style.CURVE, "Mélange", null, Kind.CENTER),
        s("STFT1", "Correction CT", "%", "0106", 1, 100.0 / 128, -100.0, -25.0, 25.0, 1, Style.BAR, "Mélange", 15.0, Kind.CENTER),
        s("LTFT1", "Correction LT", "%", "0107", 1, 100.0 / 128, -100.0, -25.0, 25.0, 1, Style.BAR, "Mélange", 15.0, Kind.CENTER),
        s("EGR", "EGR", "%", "012C", 1, 100.0 / 255, 0.0, 0.0, 100.0, 0, Style.BAR, "Émissions"),
        s("EVAP", "Purge EVAP", "%", "012E", 1, 100.0 / 255, 0.0, 0.0, 100.0, 0, Style.BAR, "Émissions"),
        s("VOLT", "Tension ECU", "V", "0142", 2, 0.001, 0.0, 8.0, 16.0, 1, Style.NUMERIC, "Électrique"),
        PidDef("BATT", "Tension", "V", "", "ATRV", 0, 1.0, 0.0, false, 10.0, 15.0, 1, Style.NUMERIC, "Électrique", null, Kind.PLAIN),
        s("WARMUPS", "Cycles chauffe", "", "0130", 1, 1.0, 0.0, 0.0, 255.0, 0, Style.NUMERIC, "Compteurs"),
        s("DIST_CLR", "Km depuis effacement", "km", "0131", 2, 1.0, 0.0, 0.0, 65535.0, 0, Style.NUMERIC, "Compteurs"),
        s("DIST_MIL", "Km voyant allumé", "km", "0121", 2, 1.0, 0.0, 0.0, 65535.0, 0, Style.NUMERIC, "Compteurs")
    )

    /** Valeurs calculées par l'appli (consommation, trajet). */
    val virtual = listOf(
        Meta("FUEL_L100", "Conso", "L/100", 0.0, 30.0, 1, Style.DIAL, "Consommation", 20.0, Kind.PLAIN),
        Meta("FUEL_LH", "Conso (débit)", "L/h", 0.0, 40.0, 1, Style.NUMERIC, "Consommation", null, Kind.PLAIN),
        Meta("FUEL_AVG", "Conso moyenne", "L/100", 0.0, 30.0, 1, Style.NUMERIC, "Consommation", null, Kind.PLAIN),
        Meta("FUEL_TRIPL", "Carburant trajet", "L", 0.0, 60.0, 2, Style.NUMERIC, "Consommation", null, Kind.PLAIN),
        Meta("TRIP_KM", "Distance trajet", "km", 0.0, 500.0, 1, Style.NUMERIC, "Consommation", null, Kind.PLAIN),
        Meta("TRIP_MIN", "Durée trajet", "min", 0.0, 600.0, 0, Style.NUMERIC, "Consommation", null, Kind.PLAIN)
    )

    val combos = listOf(
        Combo("Lambda piloté + mesuré", "LAMBDA", listOf("LAMBDA_B1"), Style.CURVE),
        Combo("Conso instantanée + moyenne", "FUEL_L100", listOf("FUEL_AVG"), Style.DIAL),
        Combo("Sonde O2 amont + aval", "O2_B1S1", listOf("O2_B1S2"), Style.CURVE),
        Combo("Corrections CT + LT", "STFT1", listOf("LTFT1"), Style.CURVE),
        Combo("Eau + huile", "COOLANT", listOf("OIL"), Style.CURVE)
    )

    fun PidDef.toMeta() = Meta(id, label, unit, min, max, decimals, style, group, warn, kind)
    fun allMeta(custom: List<PidDef>): List<Meta> = (standard + custom).map { it.toMeta() } + virtual
    fun meta(id: String, custom: List<PidDef>): Meta =
        allMeta(custom).firstOrNull { it.id == id } ?: Meta(id, id, "", 0.0, 100.0, 0, Style.BAR, "?", null, Kind.PLAIN)

    fun idsOf(g: GaugeCfg): List<String> = listOf(g.pid) + g.extra

    /** PID réels à interroger pour afficher [id]. */
    fun deps(id: String, s: Settings): List<String> = when (id) {
        "FUEL_LH", "FUEL_L100", "FUEL_AVG", "FUEL_TRIPL", "TRIP_KM", "TRIP_MIN" ->
            if (s.fuelMode == FuelMode.INJECTOR) listOf("RPM", "SPEED", "INJ_PW") else listOf("MAF", "LAMBDA", "RPM", "SPEED")
        else -> listOf(id)
    }
}

object Telemetry {
    val values = MutableStateFlow<Map<String, Double>>(emptyMap())
    val status = MutableStateFlow("Déconnecté")
    val link = MutableStateFlow(Link.OFF)
    val adapterInfo = MutableStateFlow("")
    val phoneVisible = MutableStateFlow<Set<String>>(emptySet())
    val watchVisible = MutableStateFlow<Set<String>>(emptySet())
    val editVisible = MutableStateFlow<Set<String>>(emptySet())    // aperçu en direct dans l'éditeur de cadran
    val supported = MutableStateFlow<Set<String>>(emptySet())      // résultat du scan du calculateur
    val scanRequest = MutableStateFlow(false)
    val reconnect = MutableStateFlow(false)
    val tripReset = MutableStateFlow(false)
    val rawIn = MutableStateFlow<String?>(null)
    val rawOut = MutableStateFlow("")
    // codes défaut
    val mil = MutableStateFlow(false)
    val dtcCount = MutableStateFlow(0)
    val dtcList = MutableStateFlow<List<String>>(emptyList())
    val dtcRequest = MutableStateFlow<String?>(null)               // "READ" ou "CLEAR"
    val dtcBusy = MutableStateFlow(false)
    // étalonnage carburant
    val calActive = MutableStateFlow(false)
    val calKm = MutableStateFlow(0.0)
    val calL = MutableStateFlow(0.0)                                // litres estimés (brut, sans correction)
    val calCommand = MutableStateFlow<String?>(null)                // "START" ou "STOP"
}

object Defaults {
    val pages = listOf(
        PageCfg("Moteur", listOf(GaugeCfg("RPM", 0.0, 7000.0, 6200.0, 0), GaugeCfg("COOLANT", 40.0, 130.0, 105.0, 0, null, null, emptyList(), true))),
        PageCfg("Conso", listOf(
            GaugeCfg("FUEL_L100", 0.0, 30.0, 20.0, 1, null, Style.DIAL, listOf("FUEL_AVG")),
            GaugeCfg("FUEL_LH", 0.0, 40.0, null, 1))),
        PageCfg("Tableau", listOf(
            GaugeCfg("COOLANT", 40.0, 130.0, 105.0, 0), GaugeCfg("IAT", -20.0, 100.0, 70.0, 0),
            GaugeCfg("LOAD", 0.0, 100.0, null, 0), GaugeCfg("BATT", 10.0, 15.0, null, 1)))
    )
}

object Codec {
    fun pagesToJson(p: List<PageCfg>) = JSONArray().apply {
        p.forEach { pg ->
            put(JSONObject().put("name", pg.name).put("g", JSONArray().apply {
                pg.gauges.forEach { g ->
                    put(JSONObject().put("pid", g.pid).put("min", g.min).put("max", g.max).put("dec", g.decimals).put("warm", g.warm).also { o ->
                        g.warn?.let { o.put("warn", it) }; g.label?.let { o.put("label", it) }; g.style?.let { o.put("style", it.name) }
                        if (g.extra.isNotEmpty()) o.put("extra", JSONArray(g.extra))
                    })
                }
            }))
        }
    }

    fun pagesFromJson(a: JSONArray): List<PageCfg> = (0 until a.length()).map { i ->
        val o = a.getJSONObject(i); val ga = o.getJSONArray("g")
        PageCfg(o.getString("name"), (0 until ga.length()).map { j ->
            val g = ga.getJSONObject(j)
            val ex = g.optJSONArray("extra")
            GaugeCfg(g.getString("pid"), g.getDouble("min"), g.getDouble("max"),
                if (g.has("warn")) g.getDouble("warn") else null, g.optInt("dec", 0), g.optString("label").ifEmpty { null },
                runCatching { Style.valueOf(g.optString("style")) }.getOrNull(),
                if (ex == null) emptyList() else (0 until ex.length()).map { k -> ex.getString(k) }, g.optBoolean("warm", true))
        })
    }

    fun pidsToJson(l: List<PidDef>) = JSONArray().apply {
        l.forEach { p ->
            put(JSONObject().put("id", p.id).put("label", p.label).put("unit", p.unit).put("header", p.header).put("cmd", p.cmd)
                .put("bytes", p.bytes).put("scale", p.scale).put("offset", p.offset).put("signed", p.signed)
                .put("min", p.min).put("max", p.max).put("decimals", p.decimals).put("style", p.style.name).put("kind", p.kind.name))
        }
    }

    fun pidsFromJson(a: JSONArray): List<PidDef> = (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        PidDef(o.getString("id"), o.optString("label", o.getString("id")), o.optString("unit"), o.optString("header", "7E0"),
            o.getString("cmd"), o.optInt("bytes", 1), o.optDouble("scale", 1.0), o.optDouble("offset", 0.0), o.optBoolean("signed", false),
            o.optDouble("min", 0.0), o.optDouble("max", 100.0), o.optInt("decimals", 0),
            runCatching { Style.valueOf(o.optString("style")) }.getOrDefault(Style.NUMERIC), "Perso", null,
            runCatching { Kind.valueOf(o.optString("kind")) }.getOrDefault(Kind.PLAIN))
    }

    fun settingsToJson(s: Settings) = JSONObject().put("mac", s.deviceMac).put("eth", s.ethanolPct).put("box", s.boxEnabled)
        .put("ref", s.ecuRefEthanol).put("mode", s.fuelMode.name).put("cyl", s.cylinders).put("cc", s.injectorCcMin)
        .put("dead", s.deadTimeMs).put("sim", s.simulate).put("wd", s.watchDirect).put("ws", s.watchScale)
        .put("proto", s.proto.name).put("wdtc", s.watchDtc).put("lang", s.lang).put("inj", s.injName).put("cal", s.fuelCal)

    fun settingsFromJson(o: JSONObject) = Settings(o.optString("mac"), o.optDouble("eth", 85.0), o.optBoolean("box", true),
        o.optDouble("ref", 5.0), runCatching { FuelMode.valueOf(o.optString("mode", "MAF")) }.getOrDefault(FuelMode.MAF),
        o.optInt("cyl", 4), o.optDouble("cc", 200.0), o.optDouble("dead", 0.8), o.optBoolean("sim", false),
        o.optBoolean("wd", false), o.optDouble("ws", 1.0),
        runCatching { ProtoMode.valueOf(o.optString("proto", "CLASSIC")) }.getOrDefault(ProtoMode.CLASSIC), o.optBoolean("wdtc", true),
        o.optString("lang"), o.optString("inj"), o.optDouble("cal", 1.0))

    fun warmToJson(w: WarmCfg) = JSONObject().put("on", w.enabled).put("pid", w.pid).put("ready", w.ready).put("hot", w.hot)
    fun warmFromJson(o: JSONObject) = WarmCfg(o.optBoolean("on", true), o.optString("pid", "COOLANT"), o.optDouble("ready", 85.0), o.optDouble("hot", 108.0))

    fun configFromJson(json: String): Config {
        val o = JSONObject(json)
        return Config(pagesFromJson(o.getJSONArray("pages")), pidsFromJson(o.getJSONArray("custom")),
            o.optJSONObject("warm")?.let { warmFromJson(it) } ?: WarmCfg(),
            o.optJSONObject("settings")?.let { settingsFromJson(it) } ?: Settings())
    }
}

class ConfigStore(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("elmdash", Context.MODE_PRIVATE)

    var settings: Settings
        get() = sp.getString("settings", null)?.let { runCatching { Codec.settingsFromJson(JSONObject(it)) }.getOrNull() } ?: Settings()
        set(v) { sp.edit().putString("settings", Codec.settingsToJson(v).toString()).apply() }
    var pages: List<PageCfg>
        get() = sp.getString("pages", null)?.let { runCatching { Codec.pagesFromJson(JSONArray(it)) }.getOrNull() } ?: Defaults.pages
        set(v) { sp.edit().putString("pages", Codec.pagesToJson(v).toString()).apply() }
    var customPids: List<PidDef>
        get() = sp.getString("custom", null)?.let { runCatching { Codec.pidsFromJson(JSONArray(it)) }.getOrNull() } ?: emptyList()
        set(v) { sp.edit().putString("custom", Codec.pidsToJson(v).toString()).apply() }
    var warm: WarmCfg
        get() = sp.getString("warm", null)?.let { runCatching { Codec.warmFromJson(JSONObject(it)) }.getOrNull() } ?: WarmCfg()
        set(v) { sp.edit().putString("warm", Codec.warmToJson(v).toString()).apply() }

    fun allPids() = Pids.standard + customPids
    fun configJson(): String = JSONObject().put("pages", Codec.pagesToJson(pages)).put("custom", Codec.pidsToJson(customPids))
        .put("warm", Codec.warmToJson(warm)).put("settings", Codec.settingsToJson(settings)).toString()
}
