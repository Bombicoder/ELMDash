package fr.elmdash

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import fr.elmdash.core.ConfigStore
import fr.elmdash.core.Telemetry
import fr.elmdash.core.WearSync
import org.json.JSONArray

class PhoneReceiver : WearableListenerService() {
    override fun onMessageReceived(e: MessageEvent) {
        runCatching {
            when (e.path) {
                "/visible" -> { val a = JSONArray(String(e.data)); Telemetry.watchVisible.value = (0 until a.length()).map { a.getString(it) }.toSet() }
                "/hello" -> WearSync.pushConfig(this, ConfigStore(this))
                "/dtcreq" -> Telemetry.dtcRequest.value = String(e.data)
                else -> Unit
            }
        }
    }
}
