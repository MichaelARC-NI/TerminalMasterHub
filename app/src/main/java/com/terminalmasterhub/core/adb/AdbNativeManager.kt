package com.terminalmasterhub.core.adb

import android.content.Context
import java.io.File

/**
 * Gestor de binarios nativos ADB/Fastboot para Terminal Master Hub v1.5.3.
 *
 * Extrae binarios ARM64 pre-compilados desde assets y los pone disponibles
 * para usar dentro del entorno PRoot (que puede ejecutarlos via ptrace
 * incluso en sistemas de archivos noexec).
 *
 * Los binarios provienen de Android 11.0.0_r3 (API 30) compilados
 * estaticamente, lo que NO requiere librerias externas.
 */
class AdbNativeManager(private val context: Context) {

    companion object {
        const val NATIVE_DIR = "adb-native"
        const val ADB_BIN = "adb"
        const val FASTBOOT_BIN = "fastboot"
        const val ADB_ASSET = "adb-native/adb"
        const val FASTBOOT_ASSET = "adb-native/fastboot"
    }

    private val nativeDir: File get() = File(context.filesDir, NATIVE_DIR)

    data class NativeBinaries(
        val adbPath: String? = null,
        val fastbootPath: String? = null,
        val hasAdb: Boolean = false,
        val hasFastboot: Boolean = false
    )

    /**
     * Extrae los binarios de assets al almacenamiento interno.
     */
    fun extractBinaries(): Boolean {
        nativeDir.mkdirs()
        var ok = true

        // Extraer adb
        val adbFile = File(nativeDir, ADB_BIN)
        if (!adbFile.exists()) {
            try {
                context.assets.open(ADB_ASSET).use { input ->
                    adbFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                adbFile.setExecutable(true)
            } catch (e: Exception) {
                ok = false
            }
        }

        // Extraer fastboot
        val fbFile = File(nativeDir, FASTBOOT_BIN)
        if (!fbFile.exists()) {
            try {
                context.assets.open(FASTBOOT_ASSET).use { input ->
                    fbFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                fbFile.setExecutable(true)
            } catch (e: Exception) {
                ok = false
            }
        }

        return ok
    }

    /**
     * Busca los binarios nativos.
     */
    fun findNativeBinaries(): NativeBinaries {
        extractBinaries()

        val adbFile = File(nativeDir, ADB_BIN)
        val fbFile = File(nativeDir, FASTBOOT_BIN)

        val adbPath = if (adbFile.exists()) adbFile.absolutePath else null
        val fbPath = if (fbFile.exists()) fbFile.absolutePath else null

        return NativeBinaries(
            adbPath = adbPath,
            fastbootPath = fbPath,
            hasAdb = adbPath != null,
            hasFastboot = fbPath != null
        )
    }

    /**
     * Obtiene el comando para ejecutar adb/fastboot dentro de PRoot.
     * Esto evita el problema de noexec porque PRoot usa ptrace.
     */
    fun getProotCommand(prootBaseDir: String, binary: String, args: String = ""): String? {
        val bins = findNativeBinaries()
        val binPath = when (binary) {
            "adb" -> bins.adbPath
            "fastboot" -> bins.fastbootPath
            else -> null
        }
        if (binPath == null) return null

        // El comando se ejecutara dentro del entorno PRoot
        val prootDir = File(prootBaseDir)
        val ubuntuDir = File(prootDir, "ubuntu")
        val tmpDir = "$ubuntuDir/tmp"
        val ldLibPath = "${prootDir.absolutePath}:/system/lib64:/vendor/lib64"

        return buildString {
            append("PROOT_TMP_DIR=$tmpDir ")
            append("LD_LIBRARY_PATH=$ldLibPath ")
            append("/system/bin/linker64")
            append(" ${prootDir.absolutePath}/proot-arm64")
            append(" -r ${ubuntuDir.absolutePath}")
            append(" -b /system -b /dev -b /proc -b /sys -b /storage -b /dev/pts")
            append(" -w /root")
            append(" /bin/bash -c 'export PATH=/system/bin:/system/xbin:$PATH; ")
            append("$binPath $args'")
        }
    }

    /**
     * Obtiene el estado de los binarios como mensaje.
     */
    fun getStatusMessage(): String {
        val bins = findNativeBinaries()
        val parts = mutableListOf<String>()
        if (bins.hasAdb) parts.add("adb v11 (${bins.adbPath})")
        if (bins.hasFastboot) parts.add("fastboot v11 (${bins.fastbootPath})")
        return if (parts.isEmpty()) "Usando implementacion Java interna"
        else "Binarios nativos ARM64: ${parts.joinToString(", ")}"
    }
}
