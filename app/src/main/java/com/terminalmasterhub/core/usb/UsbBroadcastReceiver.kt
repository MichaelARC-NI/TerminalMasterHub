package com.terminalmasterhub.core.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.terminalmasterhub.TerminalMasterHubApp

/**
 * BroadcastReceiver para eventos USB.
 *
 * Maneja:
 * - Permisos USB concedidos/denegados
 * - Desconexión de dispositivos USB
 */
class UsbBroadcastReceiver : BroadcastReceiver() {

    companion object {
        var onPermissionResult: ((Boolean, UsbDevice?) -> Unit)? = null
        var onDeviceDetached: ((UsbDevice?) -> Unit)? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                val device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                TerminalMasterHubApp.instance.usbCore.closeAllConnections()
                onDeviceDetached?.invoke(device)
            }

            UsbManagerCore.ACTION_USB_PERMISSION -> {
                val device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                onPermissionResult?.invoke(granted, device)
            }
        }
    }
}
