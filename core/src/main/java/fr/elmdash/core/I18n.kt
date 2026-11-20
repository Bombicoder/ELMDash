package fr.elmdash.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import java.util.Locale

/** Langues disponibles. Les textes sources de l'appli sont en français ; les autres langues viennent du dictionnaire (I18nData). */
enum class Lang(val code: String, val flag: String, val label: String) {
    EN("en", "🇬🇧", "English"), FR("fr", "🇫🇷", "Français"), ES("es", "🇪🇸", "Español"), DE("de", "🇩🇪", "Deutsch")
}

object I18n {
    /** État Compose : changer la langue redessine tout l'écran immédiatement. */
    var lang by mutableStateOf(Lang.EN)

    /** Langue du système si elle est disponible, sinon anglais. */
    fun auto(): Lang = Lang.values().firstOrNull { it.code == Locale.getDefault().language } ?: Lang.EN

    /** [code] vide = automatique. */
    fun set(code: String) { lang = if (code.isEmpty()) auto() else Lang.values().firstOrNull { it.code == code } ?: auto() }
}

fun tr(key: String): String {
    val l = I18n.lang
    if (l == Lang.FR) return key
    val e = I18nData.table[key] ?: return key
    return e.getOrNull(when (l) { Lang.ES -> 1; Lang.DE -> 2; else -> 0 }) ?: key
}

/** Traduit puis formate (%d, %s, %.1f, %% …). */
fun trf(key: String, vararg a: Any?): String = tr(key).format(*a)

/** Remplace Text de Material : toute chaîne affichée passe par le dictionnaire. */
@Composable
fun Text(
    text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified, fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null, fontFamily: FontFamily? = null, textAlign: TextAlign? = null, maxLines: Int = Int.MAX_VALUE
) {
    androidx.compose.material3.Text(tr(text), modifier, color, fontSize, fontWeight = fontWeight, fontFamily = fontFamily, textAlign = textAlign, maxLines = maxLines)
}
