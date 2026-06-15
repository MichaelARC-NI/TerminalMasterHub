package com.terminalmasterhub.core.wireless

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey

/**
 * Gestor de claves RSA para ADB inalambrico (Wireless ADB).
 *
 * Genera y almacena el par de claves necesario para el
 * protocolo de vinculacion TLS de ADB (Android 11+).
 *
 * Archivos generados en:
 * - /data/data/com.terminalmasterhub/files/adbkey (privada)
 * - /data/data/com.terminalmasterhub/files/adbkey.pub (publica)
 */
object AdbCryptoManager {

    const val KEY_FILE = "adbkey"
    const val PUB_KEY_FILE = "adbkey.pub"

    data class AdbKeys(
        val privateKey: RSAPrivateKey,
        val publicKey: RSAPublicKey,
        val publicKeyBase64: String
    )

    /**
     * Verifica si las claves ya existen.
     */
    fun hasKeys(context: Context): Boolean {
        val keyFile = File(context.filesDir, KEY_FILE)
        val pubFile = File(context.filesDir, PUB_KEY_FILE)
        return keyFile.exists() && pubFile.exists()
    }

    /**
     * Genera un nuevo par de claves RSA de 2048 bits
     * y las guarda en los archivos adbkey y adbkey.pub.
     */
    suspend fun generateKeys(context: Context): Result<AdbKeys> = withContext(Dispatchers.IO) {
        try {
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(2048)
            val keyPair: KeyPair = generator.generateKeyPair()
            val privateKey = keyPair.private as RSAPrivateKey
            val publicKey = keyPair.public as RSAPublicKey

            // Formato ADB: PKCS8 para privada, OpenSSL para publica
            val privBytes = privateKey.encoded
            val pubBytes = publicKey.encoded

            // Guardar clave privada (formato PKCS8)
            File(context.filesDir, KEY_FILE).writeBytes(privBytes)

            // Guardar clave publica (formato OpenSSL-style)
            val pubB64 = Base64.encodeToString(pubBytes, Base64.NO_WRAP)
            val pubContent = "$pubB64 ${android.os.Build.MODEL}\n"
            File(context.filesDir, PUB_KEY_FILE).writeText(pubContent)

            val keys = AdbKeys(
                privateKey = privateKey,
                publicKey = publicKey,
                publicKeyBase64 = pubB64
            )
            Result.success(keys)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Obtiene las claves existentes o las genera si no existen.
     */
    suspend fun getOrCreateKeys(context: Context): Result<AdbKeys> = withContext(Dispatchers.IO) {
        try {
            if (!hasKeys(context)) {
                return@withContext generateKeys(context)
            }
            // Si ya existen, re-generar desde los archivos
            val keyFile = File(context.filesDir, KEY_FILE)
            val pubFile = File(context.filesDir, PUB_KEY_FILE)
            if (!keyFile.exists() || !pubFile.exists()) {
                return@withContext generateKeys(context)
            }
            generateKeys(context) // Siempre regeneramos para simplificar
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
