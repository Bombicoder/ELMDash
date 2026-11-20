package fr.elmdash.core

/** Un injecteur du catalogue : débit statique en cm³/min (essence, ~3 bar). Valeurs indicatives : l'étalonnage plein à plein affine le résultat. */
data class Injector(val brand: String, val model: String, val ccMin: Double, val note: String = "") {
    val name: String get() = "$brand $model"
}

object Injectors {
    private fun lb(brand: String, series: String, lbHr: Int, note: String = "") =
        Injector(brand, "$series $lbHr lb/h", Math.round(lbHr * 10.5).toDouble(), note)      // 1 lb/h d'essence ≈ 10,5 cm³/min

    val catalog: List<Injector> = buildList {
        for (n in listOf(19, 24, 30, 36, 42, 60, 80)) add(lb("Siemens Deka", "", n).copy(model = "$n lb/h"))
        for (n in listOf(19, 24, 30, 36, 42)) add(lb("Bosch", "EV1", n))
        for (c in listOf(270, 440, 550, 650, 1000)) add(Injector("Bosch", "EV14 $c cc/min", c.toDouble()))
        for (c in listOf(100, 120, 140, 150, 160, 170, 180, 190, 200, 220, 240, 260, 280, 300, 320, 350, 380, 400, 440, 480, 500, 550, 600, 650, 700, 800, 900, 1000)) {
            val note = when {
                c <= 140 -> "Petits moteurs atmosphériques 3 cylindres, 1,0 L"
                c <= 200 -> "Moteurs d'origine 4 cylindres atmosphériques, 1,4 à 2,0 L"
                c <= 260 -> "4 à 6 cylindres atmosphériques, 2,0 à 3,0 L"
                c <= 350 -> "Turbo d'origine ou V6/V8, ~200 à 300 ch"
                c <= 550 -> "Préparation, turbo ou éthanol ~300 à 450 ch"
                else -> "Forte préparation, > 450 ch"
            }
            add(Injector("Générique", "$c cc/min", c.toDouble(), note))
        }
    }

    fun search(q: String): List<Injector> {
        val t = q.trim().lowercase()
        if (t.isEmpty()) return catalog
        val num = t.replace(',', '.').toDoubleOrNull()
        return catalog.filter { i ->
            i.name.lowercase().contains(t) || i.note.lowercase().contains(t) ||
                (num != null && kotlin.math.abs(i.ccMin - num) <= maxOf(15.0, num * 0.06))
        }
    }
}
