package com.terminalmasterhub.ui.samsung

import android.app.Activity
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import com.terminalmasterhub.TerminalMasterHubApp

/**
 * Actividad que responde al intent filter USB_DEVICE_ATTACHED
 * para dispositivos Samsung en Download Mode.
 *
 * Se abre automáticamente cuando se conecta un Samsung
 * en modo descarga por OTG.
 */
class SamsungUsbActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        if (device != null) {
            val usbCore = TerminalMasterHubApp.instance.usbCore
            if (usbCore.isSamsungDownloadMode(device)) {
                usbCore.requestPermission(device)
            }
        }

        // Redirigir a la actividad principal en la sesión Samsung
        val mainIntent = packageManager.getLaunchIntentForPackage(packageName)
        mainIntent?.putExtra("navigate_to", 2) // Tab 2 = Samsung
        startActivity(mainIntent ?: Intent(this, com.terminalmasterhub.MainActivity::class.java))
        finish()
    }
}
