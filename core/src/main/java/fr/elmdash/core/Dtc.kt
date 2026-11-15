package fr.elmdash.core

/** Codes défaut (DTC) : décodage des réponses Mode 03 et descriptions des codes courants. */
object Dtc {
    fun decode(hex: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i + 4 <= hex.length) {
            val w = hex.substring(i, i + 4); i += 4
            if (w == "0000") continue
            val b = w.toIntOrNull(16) ?: continue
            val letter = "PCBU"[(b shr 14) and 3]
            val d1 = (b shr 12) and 3
            out.add("$letter$d1" + "%03X".format(b and 0xFFF))
        }
        return out.distinct()
    }

    fun encode(code: String): String {
        val li = "PCBU".indexOf(code[0]).coerceAtLeast(0)
        val d1 = code[1].digitToInt()
        val rest = code.substring(2).toInt(16)
        return "%04X".format((li shl 14) or (d1 shl 12) or rest)
    }

    private val names = mapOf(
        "P0100" to "Circuit du débitmètre d'air (MAF)", "P0101" to "Débitmètre d'air : plage / performance",
        "P0102" to "Débitmètre d'air : signal trop bas", "P0103" to "Débitmètre d'air : signal trop haut",
        "P0105" to "Capteur de pression collecteur (MAP)", "P0107" to "MAP : signal trop bas", "P0108" to "MAP : signal trop haut",
        "P0110" to "Capteur température d'air d'admission", "P0112" to "Température d'air : signal bas", "P0113" to "Température d'air : signal haut",
        "P0115" to "Capteur température d'eau", "P0117" to "Température d'eau : signal bas", "P0118" to "Température d'eau : signal haut",
        "P0120" to "Capteur de position papillon (TPS)", "P0122" to "TPS : signal bas", "P0123" to "TPS : signal haut",
        "P0125" to "Température d'eau insuffisante pour la boucle fermée", "P0128" to "Thermostat : température sous le seuil",
        "P0130" to "Sonde O2 amont : circuit", "P0131" to "Sonde O2 amont : tension basse", "P0132" to "Sonde O2 amont : tension haute",
        "P0133" to "Sonde O2 amont : réponse lente", "P0134" to "Sonde O2 amont : pas d'activité", "P0135" to "Sonde O2 amont : chauffage",
        "P0136" to "Sonde O2 aval : circuit", "P0141" to "Sonde O2 aval : chauffage",
        "P0171" to "Mélange trop pauvre", "P0172" to "Mélange trop riche",
        "P0300" to "Ratés d'allumage multiples", "P0301" to "Raté d'allumage cylindre 1", "P0302" to "Raté d'allumage cylindre 2",
        "P0303" to "Raté d'allumage cylindre 3", "P0304" to "Raté d'allumage cylindre 4",
        "P0325" to "Capteur de cliquetis", "P0335" to "Capteur de vilebrequin", "P0340" to "Capteur d'arbre à cames",
        "P0400" to "Débit EGR", "P0420" to "Rendement du catalyseur insuffisant",
        "P0440" to "Système EVAP", "P0442" to "EVAP : petite fuite", "P0455" to "EVAP : grosse fuite",
        "P0500" to "Capteur de vitesse véhicule", "P0505" to "Régulation du ralenti", "P0506" to "Ralenti trop bas", "P0507" to "Ralenti trop haut",
        "P0562" to "Tension système trop basse", "P0563" to "Tension système trop haute", "P0601" to "Calculateur : erreur mémoire",
        "P0700" to "Boîte automatique : défaut"
    )

    fun describe(code: String): String = names[code] ?: when {
        code.startsWith("P0") -> "Code générique motorisation"
        code.startsWith("P1") || code.startsWith("P2") || code.startsWith("P3") -> "Code constructeur (Ford / Mazda)"
        code.startsWith("C") -> "Châssis (freinage, direction)"
        code.startsWith("B") -> "Carrosserie / habitacle"
        code.startsWith("U") -> "Réseau de communication"
        else -> "Code inconnu"
    }
}
