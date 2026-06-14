package com.terminalmasterhub.ui.fastboot

import android.app.Activity
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import com.terminalmasterhub.TerminalMasterHubApp
import com.terminalmasterhub.core.usb.UsbManagerCore

/**
 * Actividad que responde al intent filter USB_DEVICE_ATTACHED
 * para dispositivos ADB/Fastboot.
 *
 * Se abre automáticamente cuando se conecta un dispositivo
 * en modo fastboot o ADB por OTG.
 */
class UsbDeviceActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        if (device != null) {
            val usbCore = TerminalMasterHubApp.instance.usbCore
            if (usbCore.isFastbootMode(device)) {
                usbCore.requestPermission(device)
            }
        }

        // Redirigir a la actividad principal en la sesión Fastboot
        val mainIntent = packageManager.getLaunchIntentForPackage(packageName)
        mainIntent?.putExtra("navigate_to", 1) // Tab 1 = Fastboot
        startActivity(mainIntent ?: Intent(this, com.terminalmasterhub.MainActivity::class.java))
        finish()
    }
}
