package fr.elmdash

import android.app.Application
import fr.elmdash.core.CrashReport

class ElmApp : Application() {
    override fun onCreate() { super.onCreate(); CrashReport.install(this); runCatching { fr.elmdash.core.I18n.set(fr.elmdash.core.ConfigStore(this).settings.lang) } }
}
