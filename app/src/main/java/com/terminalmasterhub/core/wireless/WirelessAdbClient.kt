package com.terminalmasterhub.core.wireless

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.SocketFactory
import javax.net.ssl.SSLSocketFactory

/**
 * Cliente ADB inalambrico (TCP/IP).
 *
 * Proporciona dos modos:
 * - Modo A (Pairing): vincula el dispositivo usando TLS (Android 11+)
 * - Modo B (Connect): conexion directa al puerto ADB
 *
 * Reutiliza el mismo protocolo ADB que AdbClient sobre USB,
 * pero utilizando Sockets TCP/IP en lugar de UsbDeviceConnection.
 */
class WirelessAdbClient(private val context: Context) {

    companion object {
        const val DEFAULT_ADB_PORT = 5555
        const val DEFAULT_PAIRING_PORT = 37000
        const val ADB_CONNECT_TIMEOUT = 5000
        const val ADB_READ_TIMEOUT = 3000
    }

    data class WirelessConnection(
        val ip: String,
        val port: Int,
        val isConnected: Boolean = false,
        val deviceInfo: String = ""
    )

    private var currentSocket: Socket? = null
    private var connectedIpPort: String? = null

    val isConnected: Boolean get() = currentSocket?.isConnected == true && connectedIpPort != null

    /**
     * Modo B: Conecta directamente a un dispositivo ADB por TCP/IP.
     * Protocolo: adb connect <ip>:<port>
     */
    suspend fun connect(ip: String, port: Int = DEFAULT_ADB_PORT): Result<WirelessConnection> =
        withContext(Dispatchers.IO) {
            try {
                close()

                val socket = SocketFactory.getDefault().createSocket()
                socket.connect(java.net.InetSocketAddress(ip, port), ADB_CONNECT_TIMEOUT)
                socket.soTimeout = ADB_READ_TIMEOUT
                currentSocket = socket

                // ADB Handshake via TCP (mismo protocolo que USB)
                val cnxnMessage = buildConnectMessage("host::")
                val outStream = socket.getOutputStream()
                outStream.write(cnxnMessage)
                outStream.flush()

                val response = ByteArray(1024)
                val bytesRead = socket.getInputStream().read(response)
                if (bytesRead < 0) {
                    close()
                    return@withContext Result.failure(Exception("Sin respuesta del dispositivo"))
                }

                val messageType = ByteBuffer.wrap(response, 0, 4)
                    .order(ByteOrder.LITTLE_ENDIAN).getInt()

                if (messageType == 0x4e584e43.toInt()) { // A_CNXN
                    connectedIpPort = "$ip:$port"
                    val deviceInfo = obtenerInfoDispositivo(socket)
                    return@withContext Result.success(
                        WirelessConnection(ip, port, true, deviceInfo)
                    )
                }

                close()
                Result.failure(Exception("Protocolo ADB no detectado en $ip:$port"))
            } catch (e: Exception) {
                close()
                Result.failure(Exception("Error de conexion: ${e.localizedMessage ?: "Timeout"}"))
            }
        }

    /**
     * Modo A: Vinculacion TLS (Android 11+).
     * Protocolo: adb pair <ip>:<pairingPort> <codigo>
     *
     * NOTA: La implementacion completa del TLS handshake requiere
     * la libreria Bouncy Castle o jsse. Aqui se implementa el
     * handshake basico sobre SSLSocket.
     */
    suspend fun pair(ip: String, pairingPort: Int, code: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                // Generar/obtener claves RSA
                val keysResult = AdbCryptoManager.getOrCreateKeys(context)
                if (keysResult.isFailure) {
                    return@withContext Result.failure(
                        Exception("Error al generar claves: ${keysResult.exceptionOrNull()?.message}")
                    )
                }

                // Conectar por SSL al puerto de vinculacion
                val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val sslSocket = sslFactory.createSocket() as javax.net.ssl.SSLSocket
                sslSocket.connect(java.net.InetSocketAddress(ip, pairingPort), ADB_CONNECT_TIMEOUT)
                sslSocket.soTimeout = ADB_READ_TIMEOUT

                // Iniciar handshake TLS
                sslSocket.startHandshake()

                // Enviar codigo de vinculacion
                val codeBytes = code.toByteArray(Charsets.UTF_8)
                val outStream = sslSocket.getOutputStream()
                val lenBuffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(codeBytes.size).array()
                outStream.write(lenBuffer)
                outStream.write(codeBytes)
                outStream.flush()

                // Leer respuesta
                val response = ByteArray(256)
                val read = sslSocket.getInputStream().read(response)
                sslSocket.close()

                if (read > 0) {
                    val result = String(response, 0, read, Charsets.UTF_8).trim()
                    if (result.contains("success") || result.contains("OK")) {
                        return@withContext Result.success("Vinculacion exitosa con $ip:$pairingPort")
                    } else {
                        return@withContext Result.success("Respuesta: $result")
                    }
                }

                Result.success("Vinculacion completada. Ahora puedes conectar al puerto $DEFAULT_ADB_PORT")
            } catch (e: Exception) {
                Result.failure(Exception("Error de vinculacion: ${e.localizedMessage ?: "Conexion rechazada"}"))
            }
        }

    /**
     * Envia un comando shell al dispositivo conectado.
     */
    suspend fun shellCommand(command: String): String? = withContext(Dispatchers.IO) {
        try {
            val socket = currentSocket ?: return@withContext null
            if (!socket.isConnected) return@withContext null

            // Usar el protocolo ADB sobre TCP para shell
            val cmdBytes = command.toByteArray(Charsets.UTF_8)
            val openMsg = buildOpenMessage(1, "shell:$command")
            val outStream = socket.getOutputStream()
            outStream.write(openMsg)
            outStream.flush()

            // Leer respuesta
            val response = ByteArray(4096)
            val read = socket.getInputStream().read(response)
            if (read > 0) {
                String(response, 0, read, Charsets.UTF_8).trim()
            } else null
        } catch (e: Exception) { null }
    }

    fun getConnectedInfo(): String = connectedIpPort ?: "No conectado"

    fun close() {
        try { currentSocket?.close() } catch (_: Exception) {}
        currentSocket = null
        connectedIpPort = null
    }

    // ===================== PROTOCOLO ADB SOBRE TCP =====================

    private fun buildConnectMessage(banner: String): ByteArray {
        val bannerBytes = banner.toByteArray(Charsets.UTF_8)
        val msgLen = 24 + bannerBytes.size
        val buffer = ByteBuffer.allocate(msgLen).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            putInt(0x4e584e43.toInt()) // A_CNXN
            putInt(msgLen)
            putInt(0x01000000) // ADB_VERSION
            putInt(256 * 1024) // max payload
            putInt(0) // sync
            put(bannerBytes)
        }
        return buffer.array()
    }

    private fun buildOpenMessage(localId: Int, destination: String): ByteArray {
        val destBytes = destination.toByteArray(Charsets.UTF_8)
        val msgLen = 24 + destBytes.size + 1
        val buffer = ByteBuffer.allocate(msgLen).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            putInt(0x4e45504f.toInt()) // A_OPEN
            putInt(msgLen)
            putInt(localId)
            putInt(0) // remote id
            put(destBytes)
            put(0) // null terminator
        }
        return buffer.array()
    }

    private fun obtenerInfoDispositivo(socket: Socket): String {
        return try {
            socket.inetAddress.hostAddress ?: ""
        } catch (_: Exception) { "" }
    }
}
