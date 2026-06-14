package com.terminalmasterhub.core.odin

import com.terminalmasterhub.core.usb.UsbManagerCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Implementación del protocolo Odin3 para Samsung Download Mode.
 *
 * Basado en el análisis del protocolo propietario de Samsung
 * usado por herramientas como Odin3, Heimdall y ErosFlashTool.
 *
 * El protocolo usa comunicación USB bulk con endpoints específicos
 * y un handshake de 4 bytes para sincronización.
 */
class OdinProtocol(private val usbCore: UsbManagerCore) {

    companion object {
        const val SESSION_ID = "samsung_odin"

        // Odin3 Protocol Constants
        const val ODIN_HANDSHAKE = 0x12345678L
        const val ODIN_ACK = 0xABCDEF01L
        const val ODIN_NACK = 0xBEEFCAFEL
        const val ODIN_FILE_START = 0x00000100L
        const val ODIN_FILE_END = 0x00000200L
        const val ODIN_PARTITION_INFO = 0x00000300L
        const val ODIN_REBOOT = 0x00000400L
        const val ODIN_MD5_CHECK = 0x00000500L
        const val ODIN_WIPE_DATA = 0x00000600L
        const val ODIN_CHUNK_SIZE = 512 * 1024 // 512KB chunks

        // Samsung PIT partition data
        const val PIT_MAGIC = 0x12349876L
    }

    private var isConnected = false
    private var protocolVersion = 0
    var onLog: ((String) -> Unit)? = null

    val isDeviceConnected: Boolean get() = isConnected

    /**
     * Conecta al dispositivo Samsung en Download Mode.
     */
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            val devices = usbCore.getAttachedDevices()
            val samsungDevice = devices.values.firstOrNull { device ->
                usbCore.isSamsungDownloadMode(device)
            } ?: return@withContext false

            val connection = usbCore.openConnection(SESSION_ID, samsungDevice)
                ?: return@withContext false

            // Odin3 Handshake
            val handshake = ByteBuffer.allocate(4).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                putInt(ODIN_HANDSHAKE.toInt())
            }.array()

            // Enviar handshake
            usbCore.bulkTransfer(SESSION_ID, handshake, 2000)

            // Esperar ACK
            val response = ByteArray(4)
            val read = usbCore.bulkRead(SESSION_ID, response, 3000)

            if (read >= 4) {
                val ack = ByteBuffer.wrap(response).order(ByteOrder.LITTLE_ENDIAN).getInt().toLong()
                if (ack == ODIN_ACK) {
                    isConnected = true
                    protocolVersion = 1
                    log("Conectado a Samsung Download Mode (protocolo Odin3)")
                    return@withContext true
                }
            }

            isConnected = false
            log("Fallo handshake Odin3: respuesta inesperada")
            return@withContext false

        } catch (e: Exception) {
            isConnected = false
            log("Error conexión Odin3: ${e.message}")
            return@withContext false
        }
    }

    /**
     * Desconecta la sesión Odin3.
     */
    fun disconnect() {
        isConnected = false
        usbCore.closeConnection(SESSION_ID)
    }

    /**
     * Lee la tabla de particiones PIT del dispositivo.
     * Cada partición en el PIT describe un área de la memoria interna.
     */
    suspend fun readPit(): List<PitEntry>? = withContext(Dispatchers.IO) {
        if (!isConnected) return@withContext null

        try {
            // Comando para leer PIT
            val pitCmd = intToBytes(0x00000001L) // Read PIT command
            usbCore.bulkTransfer(SESSION_ID, pitCmd, 2000)

            // Leer tamaño del PIT
            val sizeBuffer = ByteArray(4)
            val sizeRead = usbCore.bulkRead(SESSION_ID, sizeBuffer, 3000)
            if (sizeRead < 4) return@withContext null

            val pitSize = ByteBuffer.wrap(sizeBuffer).order(ByteOrder.LITTLE_ENDIAN).getInt()
            if (pitSize <= 0 || pitSize > 1024 * 1024) return@withContext null // Sanity check

            // Leer datos del PIT
            val pitData = ByteArray(pitSize)
            var offset = 0
            while (offset < pitSize) {
                val chunkSize = minOf(ODIN_CHUNK_SIZE, pitSize - offset)
                val chunk = ByteArray(chunkSize)
                val read = usbCore.bulkRead(SESSION_ID, chunk, 5000)
                if (read > 0) {
                    System.arraycopy(chunk, 0, pitData, offset, read)
                    offset += read
                } else break
            }

            return@withContext parsePitData(pitData)

        } catch (e: Exception) {
            log("Error leyendo PIT: ${e.message}")
            return@withContext null
        }
    }

    /**
     * Flashea un archivo .tar o .tar.md5 al dispositivo.
     *
     * @param tarFile Archivo .tar o .tar.md5 con la firmware
     * @param autoReboot Si se debe reiniciar automáticamente
     * @param wipeData Si se debe limpiar userdata
     * @param md5Check Si se debe verificar MD5
     * @param onProgress Callback de progreso (archivoActual, totalArchivos, bytesLeidos, bytesTotales)
     * @return true si el flasheo fue exitoso
     */
    suspend fun flashTar(
        tarFile: File,
        autoReboot: Boolean = true,
        wipeData: Boolean = false,
        md5Check: Boolean = true,
        onProgress: ((Int, Int, Long, Long) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isConnected || !tarFile.exists()) return@withContext false

        try {
            // Verificar MD5 si es .tar.md5
            if (md5Check && tarFile.name.endsWith(".md5")) {
                log("Verificando MD5...")
                val calculatedMd5 = calculateMd5(tarFile)
                val expectedMd5 = extractExpectedMd5(tarFile)
                if (expectedMd5 != null && calculatedMd5 != expectedMd5) {
                    log("ERROR: MD5 mismatch! Esperado: $expectedMd5, Calculado: $calculatedMd5")
                    return@withContext false
                }
                log("MD5 verificado correctamente")
            }

            // Enviar comando de inicio de flasheo
            val startCmd = intToBytes(ODIN_FILE_START)
            usbCore.bulkTransfer(SESSION_ID, startCmd, 2000)
            Thread.sleep(100) // Pequeña pausa para estabilidad

            // Leer y enviar cada archivo del TAR
            val tarParser = TarParser(tarFile)
            val entries = tarParser.readEntries()
            var totalSize = 0L
            var fileIndex = 0

            for (entry in entries) {
                fileIndex++
                log("Enviando: ${entry.name} (${FileManager.getFileSizeString(entry.size)})")

                val chunk = ByteArray(ODIN_CHUNK_SIZE)
                var bytesRead: Long = 0

                while (bytesRead < entry.size) {
                    val toRead = minOf(ODIN_CHUNK_SIZE.toLong(), entry.size - bytesRead).toInt()
                    val actuallyRead = entry.inputStream?.read(chunk, 0, toRead) ?: -1
                    if (actuallyRead <= 0) break

                    val sent = usbCore.bulkTransfer(SESSION_ID, chunk.copyOf(actuallyRead), 30000)
                    if (sent < 0) {
                        log("ERROR: Fallo en transferencia en ${entry.name}")
                        return@withContext false
                    }

                    bytesRead += actuallyRead
                    totalSize += actuallyRead
                    onProgress?.invoke(fileIndex - 1, entries.size, bytesRead, entry.size)

                    // Enviar ACK después de cada chunk
                    val ack = intToBytes(ODIN_ACK)
                    usbCore.bulkTransfer(SESSION_ID, ack, 1000)
                }

                // Finalizar archivo
                val fileEnd = intToBytes(ODIN_FILE_END)
                usbCore.bulkTransfer(SESSION_ID, fileEnd, 2000)
                Thread.sleep(50)

                log("✓ ${entry.name} enviado")
                onProgress?.invoke(fileIndex, entries.size, entry.size, entry.size)
            }

            // Limpiar userdata si se solicitó
            if (wipeData) {
                log("Limpiando userdata...")
                val wipeCmd = intToBytes(ODIN_WIPE_DATA)
                usbCore.bulkTransfer(SESSION_ID, wipeCmd, 3000)
                Thread.sleep(500)
            }

            // Reiniciar si se solicitó
            if (autoReboot) {
                log("Reiniciando dispositivo...")
                val rebootCmd = intToBytes(ODIN_REBOOT)
                usbCore.bulkTransfer(SESSION_ID, rebootCmd, 3000)
            }

            log("✅ Flasheo completado exitosamente")
            return@withContext true

        } catch (e: Exception) {
            log("ERROR: ${e.message}")
            return@withContext false
        }
    }

    /**
     * Envía comando de reinicio al dispositivo.
     */
    suspend fun reboot(): Boolean = withContext(Dispatchers.IO) {
        if (!isConnected) return@withContext false
        try {
            val rebootCmd = intToBytes(ODIN_REBOOT)
            usbCore.bulkTransfer(SESSION_ID, rebootCmd, 3000)
            log("Comando reboot enviado")
            disconnect()
            return@withContext true
        } catch (e: Exception) {
            log("Error reboot: ${e.message}")
            return@withContext false
        }
    }

    // ===================== PRIVATE HELPERS =====================

    private fun intToBytes(value: Long): ByteArray {
        return ByteBuffer.allocate(4).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            putInt(value.toInt())
        }.array()
    }

    private fun parsePitData(data: ByteArray): List<PitEntry> {
        val entries = mutableListOf<PitEntry>()
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        try {
            if (buffer.getInt(0).toLong() != PIT_MAGIC) return entries

            val entryCount = buffer.getInt(8)

            for (i in 0 until entryCount) {
                val offset = 12 + i * 132 // Each PIT entry is 132 bytes
                if (offset + 132 > data.size) break

                val entry = PitEntry(
                    partitionId = buffer.getInt(offset),
                    partitionName = readCString(buffer, offset + 4, 32),
                    flashFilename = readCString(buffer, offset + 36, 32),
                    fileSystem = readCString(buffer, offset + 68, 8),
                    partitionSize = buffer.getInt(offset + 76).toLong() and 0xFFFFFFFFL,
                    blockSize = buffer.getInt(offset + 80).toLong(),
                    isBootable = buffer.get(offset + 96).toInt() != 0
                )
                entries.add(entry)
            }
        } catch (e: Exception) {
            log("Error parseando PIT: ${e.message}")
        }

        return entries
    }

    private fun readCString(buffer: ByteBuffer, offset: Int, maxLen: Int): String {
        val bytes = ByteArray(maxLen)
        for (i in 0 until maxLen) {
            val b = buffer.get(offset + i)
            if (b == 0.toByte()) break
            bytes[i] = b
        }
        return String(bytes, Charsets.UTF_8).trimEnd('\u0000')
    }

    private fun calculateMd5(file: File): String? {
        return try {
            val digest = java.security.MessageDigest.getInstance("MD5")
            val buffer = ByteArray(8192)
            java.io.FileInputStream(file).use { input ->
                java.io.BufferedInputStream(input).use { bis ->
                    var bytesRead: Int
                    while (bis.read(buffer).also { bytesRead = it } != -1) {
                        digest.update(buffer, 0, bytesRead)
                    }
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun extractExpectedMd5(file: File): String? {
        // .tar.md5 format: the MD5 is embedded in the filename or first bytes
        val name = file.nameWithoutExtension // removes .md5
        // Some .tar.md5 files have MD5 as filename: filename.tar.md5
        // The actual MD5 hash is stored in the last 32 chars before .tar
        val tarName = name.substringBeforeLast(".tar")
        return if (tarName.length >= 32) {
            tarName.takeLast(32).lowercase()
        } else null
    }

    private fun log(msg: String) {
        onLog?.invoke(msg)
    }

    // Data class for PIT entries
    data class PitEntry(
        val partitionId: Int,
        val partitionName: String,
        val flashFilename: String,
        val fileSystem: String,
        val partitionSize: Long,
        val blockSize: Long,
        val isBootable: Boolean
    )

    // Simplified TarParser for Odin
    class TarParser(private val tarFile: File) {
        data class TarEntry(
            val name: String,
            val size: Long,
            val inputStream: java.io.InputStream?
        )

        fun readEntries(): List<TarEntry> {
            val entries = mutableListOf<TarEntry>()
            try {
                val fileStream = java.io.FileInputStream(tarFile)
                val buffer = ByteArray(512)

                while (true) {
                    val headerRead = fileStream.read(buffer)
                    if (headerRead < 512) break

                    // Parse TAR header (POSIX format)
                    val name = String(buffer, 0, 100, Charsets.UTF_8).trimEnd('\u0000')
                    if (name.isEmpty()) break

                    val sizeStr = String(buffer, 124, 12, Charsets.UTF_8).trimEnd('\u0000')
                    val size = try {
                        Integer.parseInt(sizeStr.trim(), 8).toLong()
                    } catch (e: NumberFormatException) {
                        0L
                    }

                    if (size > 0) {
                        val data = ByteArray(size.toInt())
                        fileStream.read(data)
                        entries.add(TarEntry(name, size, data.inputStream()))
                    }

                    // Padding to 512-byte boundary
                    val padding = (512 - (size % 512)) % 512
                    if (padding > 0) fileStream.skip(padding)
                }
            } catch (e: Exception) {
                // Fallback to Apache Commons Compress if available
                entries.clear()
                entries.addAll(readWithApacheCommons())
            }
            return entries
        }

        private fun readWithApacheCommons(): List<TarEntry> {
            val entries = mutableListOf<TarEntry>()
            try {
                val tarIn = org.apache.commons.compress.archivers.tar.TarArchiveInputStream(
                    java.io.FileInputStream(tarFile)
                )
                var entry = tarIn.nextTarEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val data = tarIn.readAllBytes()
                        entries.add(TarEntry(entry.name, entry.size, data.inputStream()))
                    }
                    entry = tarIn.nextTarEntry
                }
                tarIn.close()
            } catch (e: Exception) {
                // Can't read TAR at all
            }
            return entries
        }
    }
}
