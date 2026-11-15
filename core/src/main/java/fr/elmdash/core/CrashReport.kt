package fr.elmdash.core

import android.content.Context

/** Enregistre la trace du dernier plantage pour l'afficher au lancement suivant (utile pour diagnostiquer sans ordinateur). */
object CrashReport {
    fun install(ctx: Context) {
        val sp = ctx.applicationContext.getSharedPreferences("crash", Context.MODE_PRIVATE)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { sp.edit().putString("trace", "Thread ${t.name}\n" + e.stackTraceToString().take(3500)).commit() }
            prev?.uncaughtException(t, e)
        }
    }
    fun last(ctx: Context): String? = ctx.getSharedPreferences("crash", Context.MODE_PRIVATE).getString("trace", null)
    fun clear(ctx: Context) { ctx.getSharedPreferences("crash", Context.MODE_PRIVATE).edit().remove("trace").apply() }
}
