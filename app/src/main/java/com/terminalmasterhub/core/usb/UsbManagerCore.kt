package com.terminalmasterhub.core.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Núcleo de gestión USB OTG.
 *
 * Proporciona detección, solicitud de permisos y manejo de
 * conexiones USB para todos los módulos (ADB, Fastboot, Odin3).
 * Las sesiones se aíslan para que no haya cruce de protocolos.
 */
class UsbManagerCore(private val context: Context) {

    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    // Mapa de conexiones activas por sesión
    private val activeConnections = ConcurrentHashMap<String, UsbDeviceConnection>()
    private val activeEndpoints = ConcurrentHashMap<String, Pair<UsbEndpoint, UsbEndpoint>>()

    companion object {
        const val ACTION_USB_PERMISSION = "com.terminalmasterhub.USB_PERMISSION"

        // Vendor IDs conocidos
        val VENDOR_NAMES = mapOf(
            1256 to "Samsung",
            10007 to "Xiaomi",
            6343 to "Google/Android",
            8921 to "OnePlus/Oppo",
            4817 to "Huawei",
            7070 to "Motorola",
            3725 to "MediaTek",
            1478 to "Qualcomm",
            1004 to "LG",
            1355 to "Sony",
            1514 to "ASUS",
            1204 to "Lenovo"
        )

        // Samsung Download Mode PIDs
        private val SAMSUNG_DOWNLOAD_PIDS = setOf(26717, 26720, 26819)
    }

    /**
     * Solicita permiso para acceder a un dispositivo USB.
     * El resultado se entrega via [UsbBroadcastReceiver].
     */
    fun requestPermission(device: UsbDevice): PendingIntent {
        val intent = Intent(ACTION_USB_PERMISSION)
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        usbManager.requestPermission(device, pendingIntent)
        return pendingIntent
    }

    /**
     * Obtiene la lista de dispositivos USB conectados actualmente.
     */
    fun getAttachedDevices(): Map<String, UsbDevice> {
        return usbManager.deviceList
    }

    /**
     * Identifica si un dispositivo está en modo Samsung Download.
     */
    fun isSamsungDownloadMode(device: UsbDevice): Boolean {
        return device.vendorId == 1256 &&
                device.productId in SAMSUNG_DOWNLOAD_PIDS
    }

    /**
     * Identifica si un dispositivo está en modo Fastboot.
     * Fastboot usa normalmente VID 0x18D1 (6343) con PID específico.
     */
    fun isFastbootMode(device: UsbDevice): Boolean {
        // Fastboot: Google VID (6343) o Qualcomm VID (1478)
        return (device.vendorId == 6343 || device.vendorId == 1478 || device.vendorId == 7110)
    }

    /**
     * Obtiene nombre legible del fabricante.
     */
    fun getVendorName(device: UsbDevice): String {
        return VENDOR_NAMES[device.vendorId] ?: "Desconocido (0x${device.vendorId.toString(16)})"
    }

    /**
     * Abre una conexión USB para una sesión específica.
     *
     * @param sessionId Identificador único de la sesión (ej. "fastboot", "samsung", "xiaomi")
     * @param device Dispositivo USB a conectar
     * @return Conexión establecida o null si falla
     */
    suspend fun openConnection(sessionId: String, device: UsbDevice): UsbDeviceConnection? =
        withContext(Dispatchers.IO) {
            try {
                // Cerrar conexión previa de la sesión si existe
                closeConnection(sessionId)

                val connection = usbManager.openDevice(device)
                if (connection != null) {
                    // Claim the first interface
                    val interface_ = device.getInterface(0)
                    if (interface_ != null) {
                        connection.claimInterface(interface_, true)
                        val endpoints = findEndpoints(interface_)
                        if (endpoints != null) {
                            activeEndpoints[sessionId] = endpoints
                        }
                    }
                    activeConnections[sessionId] = connection
                }
                connection
            } catch (e: SecurityException) {
                null
            } catch (e: IOException) {
                null
            }
        }

    /**
     * Cierra la conexión USB de una sesión.
     */
    fun closeConnection(sessionId: String) {
        activeConnections.remove(sessionId)?.apply {
            try {
                close()
            } catch (_: Exception) {}
        }
        activeEndpoints.remove(sessionId)
    }

    /**
     * Cierra todas las conexiones activas.
     */
    fun closeAllConnections() {
        activeConnections.keys.toList().forEach { closeConnection(it) }
    }

    /**
     * Envía datos por el bulk endpoint de salida.
     */
    suspend fun bulkTransfer(
        sessionId: String,
        data: ByteArray,
        timeout: Int = 1000
    ): Int = withContext(Dispatchers.IO) {
        val connection = activeConnections[sessionId]
        val endpoints = activeEndpoints[sessionId]
        if (connection != null && endpoints != null) {
            connection.bulkTransfer(endpoints.first, data, data.size, timeout)
        } else {
            -1
        }
    }

    /**
     * Lee datos del bulk endpoint de entrada.
     */
    suspend fun bulkRead(
        sessionId: String,
        buffer: ByteArray,
        timeout: Int = 1000
    ): Int = withContext(Dispatchers.IO) {
        val connection = activeConnections[sessionId]
        val endpoints = activeEndpoints[sessionId]
        if (connection != null && endpoints != null) {
            connection.bulkTransfer(endpoints.second, buffer, buffer.size, timeout)
        } else {
            -1
        }
    }

    /**
     * Envía comando y recibe respuesta (para Fastboot/ADB).
     */
    suspend fun sendCommand(
        sessionId: String,
        command: ByteArray,
        responseBuffer: ByteArray = ByteArray(4096),
        timeout: Int = 2000
    ): String? = withContext(Dispatchers.IO) {
        try {
            val sent = bulkTransfer(sessionId, command, timeout)
            if (sent < 0) return@withContext null

            val read = bulkRead(sessionId, responseBuffer, timeout)
            if (read < 0) return@withContext null

            String(responseBuffer, 0, read, Charsets.UTF_8).trim()
        } catch (e: Exception) {
            null
        }
    }

    private fun findEndpoints(interface_: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
        var outEndpoint: UsbEndpoint? = null
        var inEndpoint: UsbEndpoint? = null

        for (i in 0 until interface_.endpointCount) {
            val ep = interface_.getEndpoint(i)
            when (ep.type) {
                UsbConstants.USB_ENDPOINT_XFER_BULK -> {
                    if (ep.direction == UsbConstants.USB_DIR_OUT) {
                        outEndpoint = ep
                    } else if (ep.direction == UsbConstants.USB_DIR_IN) {
                        inEndpoint = ep
                    }
                }
            }
        }

        return if (outEndpoint != null && inEndpoint != null) {
            Pair(outEndpoint, inEndpoint)
        } else {
            null
        }
    }
}
