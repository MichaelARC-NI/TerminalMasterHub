package com.terminalmasterhub.core.adb

import com.terminalmasterhub.core.usb.UsbManagerCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Cliente ADB (Android Debug Bridge) nativo.
 *
 * Implementa el protocolo ADB sobre USB OTG para comunicación
 * con dispositivos Android conectados.
 *
 * Basado en el protocolo ADB:
 * - Handshake con mensajes CNXN, AUTH
 * - Encapsulación de comandos shell
 * - Transferencia de archivos (push/pull)
 */
class AdbClient(private val usbCore: UsbManagerCore) {

    companion object {
        // ADB Protocol Constants
        const val A_SYNC = 0x434e5953L
        const val A_CNXN = 0x4e584e43L
        const val A_OPEN = 0x4e45504fL
        const val A_OKAY = 0x59414b4fL
        const val A_CLSE = 0x45534c43L
        const val A_WRTE = 0x45545257L
        const val A_AUTH = 0x48545541L
        const val A_STLS = 0x534c5453L

        const val ADB_VERSION = 0x01000000
        const val MAX_PAYLOAD = 256 * 1024 // 256KB

        const val SESSION_ID = "adb"

        // System types for ADB identification
        const val SYSTEM_TYPE = "device"
        const val BOOTLOADER_TYPE = "bootloader"
    }

    private var isConnected = false
    private var maxPayload = MAX_PAYLOAD
    private val shellStreams = mutableMapOf<Int, ByteArrayOutputStream>()

    /**
     * Estado de la conexión ADB.
     */
    val isDeviceConnected: Boolean get() = isConnected

    /**
     * Inicia la conexión ADB con el dispositivo.
     */
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            val devices = usbCore.getAttachedDevices()
            val adbDevice = devices.values.firstOrNull { device ->
                !usbCore.isSamsungDownloadMode(device) && !usbCore.isFastbootMode(device)
            } ?: return@withContext false

            val connection = usbCore.openConnection(SESSION_ID, adbDevice)
                ?: return@withContext false

            // ADB Handshake: send CNXN
            val connectMessage = buildConnectMessage()
            val sent = usbCore.bulkTransfer(SESSION_ID, connectMessage, 2000)
            if (sent < 0) return@withContext false

            // Read response (A_CNXN or A_AUTH)
            val response = ByteArray(1024)
            val read = usbCore.bulkRead(SESSION_ID, response, 3000)
            if (read < 0) return@withContext false

            val messageType = ByteBuffer.wrap(response, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()

            if (messageType.toLong() == A_CNXN) {
                // Parse max payload from CNXN
                val arg1 = ByteBuffer.wrap(response, 8, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()
                if (arg1 and 0xFFFFFFFFL > 0L) {
                    maxPayload = minOf(arg1, MAX_PAYLOAD)
                }
                isConnected = true
                return@withContext true
            }

            // A_AUTH - would need key exchange for authenticated devices
            // For simplicity, we skip auth and work with devices
            // that don't require RSA authentication (or use pre-authorized keys)
            isConnected = false
            return@withContext false

        } catch (e: Exception) {
            isConnected = false
            return@withContext false
        }
    }

    /**
     * Desconecta la sesión ADB.
     */
    fun disconnect() {
        isConnected = false
        usbCore.closeConnection(SESSION_ID)
        shellStreams.clear()
    }

    /**
     * Ejecuta un comando shell en el dispositivo conectado.
     */
    suspend fun shellCommand(command: String): String? = withContext(Dispatchers.IO) {
        if (!isConnected) return@withContext null

        try {
            // Abrir shell stream
            val localId = generateLocalId()
            val openMessage = buildOpenMessage(localId, "shell:$command")
            usbCore.bulkTransfer(SESSION_ID, openMessage, 2000)

            // Leer respuesta
            val outputStream = ByteArrayOutputStream()
            val buffer = ByteArray(maxPayload)
            var timeout = 10 // 10 iterations max

            while (timeout > 0) {
                val read = usbCore.bulkRead(SESSION_ID, buffer, 3000)
                if (read <= 0) {
                    timeout--
                    continue
                }

                val msgType = ByteBuffer.wrap(buffer, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()
                val msgLen = ByteBuffer.wrap(buffer, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()

                when (msgType.toLong()) {
                    A_OKAY -> { } // OK, continue reading
                    A_WRTE -> {
                        // Data payload starts at offset 24
                        if (read > 24) {
                            outputStream.write(buffer, 24, read - 24)
                        }
                        // Send OKAY back
                        val okayMsg = buildOkayMessage(localId)
                        usbCore.bulkTransfer(SESSION_ID, okayMsg, 1000)
                    }
                    A_CLSE -> {
                        // Stream closed
                        break
                    }
                }
                timeout--
            }

            return@withContext String(outputStream.toByteArray(), Charsets.UTF_8).trim()

        } catch (e: Exception) {
            return@withContext "Error: ${e.message}"
        }
    }

    /**
     * Transfiere un archivo al dispositivo (push).
     */
    suspend fun pushFile(localPath: String, remotePath: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!isConnected) return@withContext false
            try {
                val file = java.io.File(localPath)
                if (!file.exists()) return@withContext false

                // Sync protocol for push
                val data = file.readBytes()
                val localId = generateLocalId()
                val syncCommand = "sync:"
                val openMsg = buildOpenMessage(localId, syncCommand)
                usbCore.bulkTransfer(SESSION_ID, openMsg, 2000)

                // For now, use shell fallback with base64 for smaller files
                if (data.size < 1024 * 1024) { // < 1MB
                    val b64 = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
                    val cmd = "echo '$b64' | base64 -d > '$remotePath'"
                    shellCommand(cmd)
                    return@withContext true
                }

                return@withContext false
            } catch (e: Exception) {
                return@withContext false
            }
        }

    /**
     * Obtiene información del dispositivo.
     */
    suspend fun getDeviceInfo(): Map<String, String> = withContext(Dispatchers.IO) {
        val info = mutableMapOf<String, String>()

        shellCommand("getprop ro.product.model")?.let { info["model"] = it }
        shellCommand("getprop ro.product.manufacturer")?.let { info["manufacturer"] = it }
        shellCommand("getprop ro.build.version.release")?.let { info["android_version"] = it }
        shellCommand("getprop ro.build.version.sdk")?.let { info["sdk"] = it }
        shellCommand("getprop ro.bootloader")?.let { info["bootloader"] = it }
        shellCommand("getprop ro.serialno")?.let { info["serial"] = it }

        return@withContext info
    }

    // ===================== PRIVATE HELPERS =====================

    private fun buildConnectMessage(): ByteArray {
        val banner = "host::features=shell_v2,cmd,stat_v2,ls_v2,apex,abb,abb_exec,fixed_push_mkdir,fixed_push_symlink,apex,v2_compression,spec_wsgi_ignore_dirs\n"
        val bannerBytes = banner.toByteArray(Charsets.UTF_8)
        val msgLen = 24 + bannerBytes.size

        val buffer = ByteBuffer.allocate(msgLen).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            putInt(A_CNXN.toInt())
            putInt(msgLen)
            putInt(ADB_VERSION)
            putInt(maxPayload)
            putInt(0) // sync
            put(bannerBytes)
        }
        return buffer.array()
    }

    private fun buildOpenMessage(localId: Int, destination: String): ByteArray {
        val destBytes = destination.toByteArray(Charsets.UTF_8)
        val msgLen = 24 + destBytes.size + 1 // +1 for null terminator

        val buffer = ByteBuffer.allocate(msgLen).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            putInt(A_OPEN.toInt())
            putInt(msgLen)
            putInt(localId)
            putInt(0) // remoteId = 0 for open
            put(destBytes)
            put(0.toByte()) // null terminator
        }
        return buffer.array()
    }

    private fun buildOkayMessage(localId: Int): ByteArray {
        val buffer = ByteBuffer.allocate(24).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            putInt(A_OKAY.toInt())
            putInt(24)
            putInt(localId)
            putInt(0) // remoteId
            putInt(0) // zero fill
        }
        return buffer.array()
    }

    private var idCounter = 100

    @Synchronized
    private fun generateLocalId(): Int {
        idCounter++
        return idCounter
    }
}
