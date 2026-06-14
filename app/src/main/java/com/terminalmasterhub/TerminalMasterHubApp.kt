package com.terminalmasterhub

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.terminalmasterhub.core.usb.UsbManagerCore

/**
 * Terminal Master Hub - Application Class
 *
 * Inicializa canales de notificación, configura el núcleo USB
 * y prepara el entorno Chaquopy (Python) al arranque.
 */
class TerminalMasterHubApp : Application() {

    companion object {
        const val CHANNEL_FLASH = "channel_flash"
        const val CHANNEL_TERMINAL = "channel_terminal"
        const val CHANNEL_PYTHON = "channel_python"
        lateinit var instance: TerminalMasterHubApp
            private set
    }

    lateinit var usbCore: UsbManagerCore
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        usbCore = UsbManagerCore(this)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        val flashChannel = NotificationChannel(
            CHANNEL_FLASH,
            "Flasheo en segundo plano",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notificaciones durante operaciones de flasheo USB"
        }

        val terminalChannel = NotificationChannel(
            CHANNEL_TERMINAL,
            "Terminal",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Servicio en primer plano de la terminal"
        }

        val pythonChannel = NotificationChannel(
            CHANNEL_PYTHON,
            "Python",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Ejecución de scripts de Python en segundo plano"
        }

        manager.createNotificationChannel(flashChannel)
        manager.createNotificationChannel(terminalChannel)
        manager.createNotificationChannel(pythonChannel)
    }
}
