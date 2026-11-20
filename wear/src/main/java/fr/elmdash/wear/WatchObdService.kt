package fr.elmdash.wear

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import fr.elmdash.core.*
import kotlinx.coroutines.*

class WearApp : android.app.Application() {
    override fun onCreate() { super.onCreate(); CrashReport.install(this); WatchState.ensure(this) }
}

/** Connexion DIRECTE de la montre au boîtier ELM327 (sans téléphone). */
class WatchObdService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
        Telemetry.link.value = Link.OFF; Telemetry.status.value = "Erreur : ${e.javaClass.simpleName} ${e.message ?: ""}".take(100)
    })
    private var job: Job? = null
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        WatchState.ensure(this)
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Telemetry.link.value = Link.OFF; Telemetry.status.value = "Autorisation Bluetooth requise pour ELMDash (réglages de la montre)"
            stopSelf(); return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("obd", "OBD", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = NotificationCompat.Builder(this, "obd").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("ElmDash").setContentText(tr("Connexion directe au boîtier")).build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) else startForeground(1, n)
        } catch (e: Exception) { Telemetry.status.value = "Service refusé : ${e.javaClass.simpleName}"; stopSelf(); return START_NOT_STICKY }
        if (job == null) job = scope.launch {
            ObdEngine(this@WatchObdService,
                { WatchState.settings.value.copy(simulate = WatchState.sim.value) },
                { (Pids.standard + WatchState.custom.value).associateBy { it.id } },
                { WatchState.visible.value }, { WatchState.mac.value }, { false }, {}
            ) { v -> WatchState.values.value = v }.run()
        }
        return START_STICKY
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
