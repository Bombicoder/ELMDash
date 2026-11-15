package fr.elmdash

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import fr.elmdash.core.*

/** Garde l'appli vivante en arrière-plan (service de premier plan). Le moteur tourne dans EngineHost. */
class ObdService : Service() {
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("obd", "OBD", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = NotificationCompat.Builder(this, "obd").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("ElmDash").setContentText("Lecture OBD en cours").build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) else startForeground(1, n)
        } catch (e: Exception) {
            Telemetry.status.value = "Service refusé par Android : ${e.javaClass.simpleName}"
            stopSelf(); return START_NOT_STICKY
        }
        EngineHost.ensureStarted(this)
        return START_STICKY
    }
}
