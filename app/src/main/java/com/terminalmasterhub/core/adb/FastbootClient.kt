package com.terminalmasterhub.core.adb

import com.terminalmasterhub.core.file.FileManager
import com.terminalmasterhub.core.usb.UsbManagerCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Cliente Fastboot nativo sobre USB OTG.
 *
 * Implementa el protocolo Fastboot para comunicación directa
 * con dispositivos Android en modo bootloader.
 *
 * Soporta:
 * - Comandos manuales (devices, reboot, oem, etc.)
 * - Flasheo de imágenes de partición
 * - Desbloqueo de bootloader
 * - Operaciones sobre A/B slots
 */
class FastbootClient(private val usbCore: UsbManagerCore) {

    companion object {
        const val SESSION_ID = "fastboot"

        // Fastboot protocol constants
        const val FASTBOOT_SUCCESS = "OKAY"
        const val FASTBOOT_FAIL = "FAIL"
        const val FASTBOOT_DATA = "DATA"
        const val FASTBOOT_INFO = "INFO"

        const val MAX_DOWNLOAD_SIZE = 1024 * 1024 * 1024 // 1GB max
        const val TRANSFER_CHUNK_SIZE = 1024 * 1024 // 1MB chunks
    }

    private var isConnected = false
    private var currentSlot = ""

    val isDeviceConnected: Boolean get() = isConnected

    /**
     * Conecta al primer dispositivo en modo fastboot.
     */
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            val devices = usbCore.getAttachedDevices()
            val fastbootDevice = devices.values.firstOrNull { device ->
                usbCore.isFastbootMode(device)
            } ?: return@withContext false

            val connection = usbCore.openConnection(SESSION_ID, fastbootDevice)
                ?: return@withContext false

            isConnected = true

            // Obtener slot actual
            val slotResult = sendCommand("getvar:current-slot")
            if (slotResult != null && slotResult.startsWith(FASTBOOT_SUCCESS)) {
                currentSlot = slotResult.removePrefix(FASTBOOT_SUCCESS).trim()
            }

            return@withContext true

        } catch (e: Exception) {
            isConnected = false
            return@withContext false
        }
    }

    /**
     * Desconecta la sesión fastboot.
     */
    fun disconnect() {
        isConnected = false
        usbCore.closeConnection(SESSION_ID)
    }

    /**
     * Envía un comando fastboot y recibe respuesta.
     *
     * @param command Comando sin prefijo (ej. "devices", "reboot", "flash boot boot.img")
     * @return Respuesta del dispositivo o null si falla
     */
    suspend fun sendCommand(command: String): String? = withContext(Dispatchers.IO) {
        if (!isConnected) return@withContext null

        try {
            val cmdBytes = command.toByteArray(Charsets.UTF_8)
            val sent = usbCore.bulkTransfer(SESSION_ID, cmdBytes, 2000)
            if (sent < 0) return@withContext "FAILusb"

            // Leer respuesta
            val buffer = ByteArray(4096)
            val output = StringBuilder()

            while (true) {
                val read = usbCore.bulkRead(SESSION_ID, buffer, 5000)
                if (read <= 0) break

                val response = String(buffer, 0, read, Charsets.UTF_8)

                when {
                    response.startsWith(FASTBOOT_SUCCESS) -> {
                        output.append(response.removePrefix(FASTBOOT_SUCCESS))
                        break
                    }
                    response.startsWith(FASTBOOT_FAIL) -> {
                        val error = response.removePrefix(FASTBOOT_FAIL)
                        output.append("FAIL: $error")
                        break
                    }
                    response.startsWith(FASTBOOT_DATA) -> {
                        // DATA response means we need to send data next
                        val size = response.removePrefix(FASTBOOT_DATA).trim().toIntOrNull() ?: 0
                        output.append("DATA:$size")
                        break
                    }
                    response.startsWith(FASTBOOT_INFO) -> {
                        output.appendLine(response.removePrefix(FASTBOOT_INFO))
                        // Continue reading after INFO
                    }
                    else -> {
                        output.append(response)
                        break
                    }
                }
            }

            return@withContext output.toString().trim()

        } catch (e: Exception) {
            return@withContext "Error: ${e.message}"
        }
    }

    /**
     * Flashea un archivo de imagen en una partición.
     *
     * @param partition Nombre de la partición (boot, system, vendor, etc.)
     * @param imageFile Archivo de imagen a flashear
     * @param onProgress Callback de progreso (bytesEnviados, bytesTotales)
     * @return true si el flasheo fue exitoso
     */
    suspend fun flashPartition(
        partition: String,
        imageFile: File,
        onProgress: ((Long, Long) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isConnected || !imageFile.exists()) return@withContext false

        try {
            // 1. Enviar comando download
            val fileSize = imageFile.length()
            if (fileSize > MAX_DOWNLOAD_SIZE) return@withContext false

            onProgress?.invoke(0, fileSize)

            val downloadCmd = "download:%08x".format(fileSize)
            val cmdResult = sendCommand(downloadCmd)
            if (cmdResult == null || !cmdResult.startsWith("DATA:")) {
                sendCommand("reboot")
                return@withContext false
            }

            // 2. Enviar datos en chunks
            val dataSize = cmdResult.removePrefix("DATA:").toIntOrNull() ?: fileSize.toInt()
            val fileBytes = imageFile.readBytes()
            var offset = 0

            while (offset < fileBytes.size) {
                val chunkSize = minOf(TRANSFER_CHUNK_SIZE, fileBytes.size - offset)
                val chunk = fileBytes.copyOfRange(offset, offset + chunkSize)

                val sent = usbCore.bulkTransfer(SESSION_ID, chunk, 30000)
                if (sent < 0) return@withContext false

                offset += chunkSize
                onProgress?.invoke(offset.toLong(), fileSize)
            }

            // 3. Verificar que el download fue exitoso
            val verifyBuffer = ByteArray(64)
            val verifyRead = usbCore.bulkRead(SESSION_ID, verifyBuffer, 5000)
            if (verifyRead <= 0) return@withContext false

            val verifyStr = String(verifyBuffer, 0, verifyRead, Charsets.UTF_8)
            if (!verifyStr.startsWith(FASTBOOT_SUCCESS)) return@withContext false

            // 4. Enviar comando flash
            val flashResult = sendCommand("flash:$partition")
            if (flashResult == null) return@withContext false

            onProgress?.invoke(fileSize, fileSize)
            return@withContext flashResult.startsWith(FASTBOOT_SUCCESS)

        } catch (e: Exception) {
            return@withContext false
        }
    }

    /**
     * Flashea múltiples imágenes basado en comandos parseados de flash_all.sh.
     */
    suspend fun flashFromScript(
        commands: List<FileManager.FastbootCommand>,
        onProgress: ((Int, Int, String) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isConnected) return@withContext false

        var successCount = 0
        val total = commands.size

        for ((index, cmd) in commands.withIndex()) {
            onProgress?.invoke(index, total, cmd.rawCommand)

            when (cmd.operation) {
                "flash" -> {
                    val partition = cmd.partition ?: continue
                    val image = cmd.imageFile
                    if (image != null && image.exists()) {
                        val result = flashPartition(partition, image)
                        if (result) successCount++
                    }
                }
                "erase" -> {
                    sendCommand(cmd.rawCommand.removePrefix("fastboot ").trim())
                }
                "reboot" -> {
                    sendCommand(cmd.rawCommand.removePrefix("fastboot ").trim())
                }
                "oem" -> {
                    sendCommand(cmd.rawCommand.removePrefix("fastboot ").trim())
                }
                else -> {
                    sendCommand(cmd.rawCommand.removePrefix("fastboot ").trim())
                }
            }
        }

        return@withContext successCount > 0
    }

    /**
     * Obtiene variables del dispositivo fastboot.
     */
    suspend fun getVar(variable: String): String? {
        val result = sendCommand("getvar:$variable")
        return if (result?.startsWith(FASTBOOT_SUCCESS) == true) {
            result.removePrefix(FASTBOOT_SUCCESS).trim()
        } else null
    }

    /**
     * Obtiene todas las variables útiles del dispositivo.
     */
    suspend fun getDeviceInfo(): Map<String, String> = withContext(Dispatchers.IO) {
        val vars = listOf(
            "product", "version-bootloader", "version-baseband",
            "serialno", "secure", "slot-count", "current-slot",
            "slot-suffixed", "max-download-size", "has-slot:boot",
            "has-slot:system", "has-slot:vendor", "hw-revision",
            "battery-voltage", "battery-soc-ok", "off-mode-charge",
            "display-panel", "variant"
        )
        val info = mutableMapOf<String, String>()
        for (v in vars) {
            getVar(v)?.let { info[v] = it }
        }
        return@withContext info
    }

    /**
     * Desbloquea el bootloader.
     * Comando: fastboot oem unlock (Xiaomi/MTK genérico)
     */
    suspend fun unlockBootloader(pin: String? = null): String? {
        return if (pin != null) {
            sendCommand("oem unlock $pin")
        } else {
            sendCommand("oem unlock")
        }
    }

    /**
     * Flashea recovery personalizado.
     */
    suspend fun flashRecovery(recoveryImg: File): Boolean {
        return flashPartition("recovery", recoveryImg)
    }

    /**
     * Flashea boot personalizado.
     */
    suspend fun flashBoot(bootImg: File): Boolean {
        return flashPartition("boot", bootImg)
    }

    /**
     * Selecciona un slot A/B.
     */
    suspend fun setActiveSlot(slot: String): String? {
        return sendCommand("set_active:$slot")
    }

    /**
     * Limpia la partición de super (para ROMs dinámicas).
     */
    suspend fun eraseSuper(): String? {
        return sendCommand("erase super")
    }
}
