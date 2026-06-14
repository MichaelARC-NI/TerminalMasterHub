package com.terminalmasterhub.core.usb

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.terminalmasterhub.MainActivity
import com.terminalmasterhub.TerminalMasterHubApp

/**
 * Servicio en primer plano para flasheo USB.
 *
 * Mantiene el proceso vivo durante operaciones largas:
 * - Flasheo de ROMs Xiaomi
 * - Flasheo Samsung vía Odin3
 * - Descompresión de archivos grandes
 *
 * Usa corrutinas para no bloquear el hilo principal.
 */
class FlashForegroundService : Service() {

    companion object {
        const val NOTIFICATION_ID = 1001
        const val ACTION_START_FLASH = "com.terminalmasterhub.START_FLASH"
        const val ACTION_STOP_FLASH = "com.terminalmasterhub.STOP_FLASH"
        const val EXTRA_SESSION_TYPE = "session_type"
        const val EXTRA_FILE_PATH = "file_path"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_FLASH -> {
                val notification = createNotification()
                startForeground(NOTIFICATION_ID, notification)
            }
            ACTION_STOP_FLASH -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, FlashForegroundService::class.java).apply {
            action = ACTION_STOP_FLASH
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, TerminalMasterHubApp.CHANNEL_FLASH)
            .setContentTitle("Terminal Master Hub")
            .setContentText("Operación de flasheo en progreso...")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Detener", stopPendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    /**
     * Actualiza el texto de la notificación durante el flasheo.
     */
    fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(this, TerminalMasterHubApp.CHANNEL_FLASH)
            .setContentTitle("Terminal Master Hub")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setSilent(true)
            .build()

        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }
}
