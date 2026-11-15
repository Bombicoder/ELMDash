package fr.elmdash.core

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

@SuppressLint("MissingPermission")
fun bondedDevices(ctx: Context): List<BluetoothDevice> =
    runCatching { ctx.getSystemService(BluetoothManager::class.java).adapter?.bondedDevices?.toList() }.getOrNull() ?: emptyList()

@SuppressLint("MissingPermission")
fun findBonded(ctx: Context, mac: String): BluetoothDevice? {
    val l = bondedDevices(ctx)
    if (mac.isNotEmpty()) return l.firstOrNull { it.address == mac }
    val hints = listOf("OBD", "ELM", "V-LINK", "VLINK", "VGATE", "VEEPEAK", "OBDLINK", "KONNWEI")
    return l.firstOrNull { d -> hints.any { (runCatching { d.name }.getOrNull() ?: "").uppercase().contains(it) } }
}

/**
 * Liaison ELM327 (Bluetooth classique SPP).
 *  CLASSIC  : ISO 9141-2 d'abord (Ford Probe 1997), puis détection auto, J1850, KWP, puis CAN.
 *  STANDARD : détection automatique OBD-II puis CAN ISO 15765-4.
 *  FORSCAN  : CAN 11 bits, adressage physique des ECU (ATSH7E0), requêtes Mode 22.
 */
class ObdLink {
    private var sock: BluetoothSocket? = null
    private var inp: InputStream? = null
    private var out: OutputStream? = null
    private var header: String? = null
    var emu: ElmEmulator? = null      // non nul = mode simulation
    var info = ""
    var proto = ""
    private var isCan = false
    private var can11 = false
    private var forscan = false
    private val spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    @SuppressLint("MissingPermission")
    fun open(d: BluetoothDevice): Boolean = try {
        close()
        BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
        val s = d.createRfcommSocketToServiceRecord(spp)
        s.connect(); sock = s; inp = s.inputStream; out = s.outputStream; true
    } catch (e: IOException) { close(); false } catch (e: SecurityException) { close(); false }

    fun close() { runCatching { sock?.close() }; sock = null; inp = null; out = null; header = null }

    @Synchronized
    @Throws(IOException::class)
    fun send(cmd: String, timeoutMs: Long = 2000): String {
        emu?.let {
            if (Sim.state.value.adapterAbsent) throw IOException("simulation : boîtier absent")
            Thread.sleep(12)
            return it.respond(cmd)
        }
        val i = inp ?: throw IOException("fermé"); val o = out ?: throw IOException("fermé")
        while (i.available() > 0) i.read()
        o.write((cmd + "\r").toByteArray()); o.flush()
        val sb = StringBuilder(); val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            if (i.available() > 0) { val c = i.read(); if (c == '>'.code) break; sb.append(c.toChar()) } else Thread.sleep(2)
        }
        return sb.toString()
    }

    /** Aplatit une réponse : retire espaces, "SEARCHING...", ligne de longueur et préfixes "0:" "1:" des trames ISO-TP multiples. */
    fun clean(raw: String): String {
        val lines = raw.uppercase().lines().map { it.trim().replace(" ", "") }.filter { it.isNotEmpty() && !it.startsWith("SEARCHING") }
        return lines.filterIndexed { i, l -> !(lines.size > 1 && i == 0 && Regex("[0-9A-F]{3}").matches(l)) }
            .joinToString("") { it.replace(Regex("^[0-9A-F]:"), "") }
    }

    fun init(mode: ProtoMode): Boolean {
        forscan = mode == ProtoMode.FORSCAN
        var v = send("ATZ", 3500)
        if (!v.uppercase().contains("ELM")) { Thread.sleep(600); v = send("ATZ", 3500) }
        val ver = Regex("ELM327[^\\r\\n]*", RegexOption.IGNORE_CASE).find(v)?.value ?: ""
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATCAF1")) send(c)
        header = null
        val order = when (mode) {
            ProtoMode.CLASSIC -> listOf("3", "0", "1", "5", "4", "2", "6", "7", "8", "9")
            ProtoMode.STANDARD -> listOf("0", "6", "7", "8", "9", "5", "4", "3", "1", "2")
            ProtoMode.FORSCAN -> listOf("6", "8", "7", "9", "0")
        }
        var ok = false
        for (p in order) {
            send("ATSP$p")
            val wait = when (p) { "6", "7", "8", "9" -> 5000L; "0", "3", "4", "5" -> 15000L; else -> 7000L }
            if (clean(send("0100", wait)).contains("4100")) { proto = p; ok = true; break }
        }
        if (ok) {
            val dp = send("ATDP").trim().replace("\r", " ").replace("\n", " ")
            isCan = proto in listOf("6", "7", "8", "9") || Regex("CAN|15765", RegexOption.IGNORE_CASE).containsMatchIn(dp)
            can11 = proto in listOf("6", "8") || Regex("11", RegexOption.IGNORE_CASE).containsMatchIn(dp) && isCan
            info = listOf(ver, dp).filter { it.isNotEmpty() }.joinToString(" · ")
        }
        return ok
    }

    private fun applyHeader(p: PidDef) {
        if (!can11) return
        val want = if (p.header.isNotEmpty() && (forscan || !p.cmd.startsWith("01"))) p.header else "7DF"
        if (want != header) { send("ATSH$want"); header = want }
    }

    fun read(p: PidDef): Double? {
        if (p.cmd == "ATRV") return Regex("([0-9]+\\.?[0-9]*)V").find(send("ATRV").uppercase())?.groupValues?.get(1)?.toDoubleOrNull()
        applyHeader(p)
        val r = clean(send(p.cmd))
        if (listOf("NODATA", "ERROR", "UNABLE", "?", "STOPPED").any { r.contains(it) }) return null
        val prefix = "%02X".format(p.cmd.substring(0, 2).toInt(16) + 0x40) + p.cmd.substring(2)
        val idx = r.indexOf(prefix); if (idx < 0) return null
        val hex = r.substring(idx + prefix.length)
        if (hex.length < p.bytes * 2) return null
        var raw = hex.substring(0, p.bytes * 2).toLongOrNull(16) ?: return null
        if (p.signed && raw >= (1L shl (p.bytes * 8 - 1))) raw -= 1L shl (p.bytes * 8)
        return raw * p.scale + p.offset
    }

    /** PID Mode 01 déclarés par le calculateur (masques 0100, 0120, 0140…). */
    fun supportedPids(): Set<Int> {
        if (can11) { send("ATSH" + (if (forscan) "7E0" else "7DF")); header = if (forscan) "7E0" else "7DF" }
        val out = HashSet<Int>(); var base = 0
        while (base <= 0xC0) {
            val pre = "41%02X".format(base)
            val r = clean(send("01%02X".format(base), 4000))
            val i = r.indexOf(pre)
            if (i < 0 || r.length < i + pre.length + 8) break
            val mask = r.substring(i + pre.length, i + pre.length + 8).toLongOrNull(16) ?: break
            for (b in 0 until 32) if (mask and (1L shl (31 - b)) != 0L) out.add(base + b + 1)
            if (mask and 1L == 0L) break
            base += 0x20
        }
        return out
    }

    /** Témoin moteur (MIL) et nombre de codes : PID 0101. */
    fun milStatus(): Pair<Boolean, Int>? {
        if (can11 && header != "7DF" && !forscan) { send("ATSH7DF"); header = "7DF" }
        val r = clean(send("0101", 4000))
        val i = r.indexOf("4101"); if (i < 0 || r.length < i + 6) return null
        val a = r.substring(i + 4, i + 6).toIntOrNull(16) ?: return null
        return Pair((a and 0x80) != 0, a and 0x7F)
    }

    fun readDtcs(): List<String> {
        val raw = send("03", 6000).uppercase()
        if (raw.replace(" ", "").contains("NODATA")) return emptyList()
        val hex = StringBuilder()
        if (isCan) {
            val j = clean(raw); val i = j.indexOf("43")
            if (i >= 0 && j.length >= i + 4) hex.append(j.substring(i + 4))     // saute "43" + nombre de codes
        } else {
            raw.lines().map { it.replace(" ", "").trim() }.filter { it.startsWith("43") }.forEach { hex.append(it.substring(2)) }
        }
        return Dtc.decode(hex.toString())
    }

    fun clearDtcs(): Boolean = clean(send("04", 6000)).contains("44")
}
