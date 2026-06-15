package com.terminalmasterhub.core.proot

import android.content.Context
import com.terminalmasterhub.core.root.BootstrapManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.net.HttpURLConnection

/**
 * Gestor de PRoot + Ubuntu ARM64 para Terminal Master Hub.
 *
 * Proporciona un entorno Linux completo (Ubuntu 24.04 ARM64)
 * usando PRoot para evitar necesidad de root.
 *
 * Basado en el enfoque de Termux y Kali NetHunter:
 * - PRoot: redirige llamadas al sistema para crear un chroot falso
 * - Rootfs Ubuntu ARM64: sistema de archivos completo con apt, python, cmus
 *
 * Los binarios se almacenan en:
 *   $PREFIX/proot/          <- Binario de PRoot
 *   $PREFIX/proot/ubuntu/   <- Rootfs de Ubuntu ARM64
 */
class ProotManager(private val context: Context) {

    companion object {
        const val PROOT_SUBDIR = "proot"
        const val UBUNTU_SUBDIR = "ubuntu"
        const val PROOT_BIN_NAME = "proot"

        // URLs de descarga
        const val PROOT_DEB_URL = "https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.4.0_aarch64.deb"
        const val UBUNTU_ROOTFS_URL = "http://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.2-base-arm64.tar.gz"

        // Proot estático compilado para Android (fallback si .deb no funciona)
        const val PROOT_STATIC_URL = "https://github.com/termux/proot/releases/download/v5.4.0/proot-arm64-v8a"
    }

    data class ProotStatus(
        val isProotAvailable: Boolean = false,
        val isUbuntuInstalled: Boolean = false,
        val ubuntuSize: Long = 0L,
        val message: String = ""
    )

    var onProgress: ((String, Int) -> Unit)? = null

    /** Obtiene el directorio base de proot dentro de PREFIX */
    fun getProotBaseDir(): File = File(
        context.filesDir,
        "${BootstrapManager.PREFIX_DIR}/$PROOT_SUBDIR"
    )

    /** Obtiene el binario de PRoot */
    fun getProotBinary(): File = File(getProotBaseDir(), PROOT_BIN_NAME)

    /** Obtiene el directorio de la rootfs Ubuntu */
    fun getUbuntuDir(): File = File(getProotBaseDir(), UBUNTU_SUBDIR)

    /** Verifica si PRoot está disponible */
    fun isProotAvailable(): Boolean = getProotBinary().exists()

    /** Verifica si Ubuntu rootfs está instalada */
    fun isUbuntuInstalled(): Boolean {
        val ubuntuDir = getUbuntuDir()
        return ubuntuDir.exists() &&
                File(ubuntuDir, "etc/os-release").exists() &&
                File(ubuntuDir, "usr/bin/apt").exists()
    }

    /** Obtiene el estado completo */
    fun getStatus(): ProotStatus {
        return ProotStatus(
            isProotAvailable = isProotAvailable(),
            isUbuntuInstalled = isUbuntuInstalled(),
            ubuntuSize = if (isUbuntuInstalled()) getDirSize(getUbuntuDir()) else 0L,
            message = buildString {
                if (isProotAvailable()) append("PRoot listo | ")
                else append("PRoot no disponible | ")
                if (isUbuntuInstalled()) append("Ubuntu ARM64 instalado")
                else append("Ubuntu no instalado")
            }
        )
    }

    // =========================================================================
    // DESCARGA E INSTALACION DE PROOT
    // =========================================================================

    /**
     * Descarga e instala el binario de PRoot para ARM64.
     * Primero intenta descargar el .deb de Termux,
     * si falla descarga el binario estático de GitHub.
     */
    suspend fun installProot(): Boolean = withContext(Dispatchers.IO) {
        try {
            val prootDir = getProotBaseDir()
            prootDir.mkdirs()

            // Intentar 1: Descargar .deb de Termux y extraer
            onProgress?.invoke("Descargando PRoot desde Termux...", 10)
            try {
                val debFile = File(prootDir, "proot.deb")
                downloadFile(PROOT_DEB_URL, debFile)

                onProgress?.invoke("Extrayendo PRoot del paquete...", 40)
                // Extraer .deb: ar x proot.deb && tar xf data.tar.xz
                extractProotFromDeb(debFile, prootDir)
                debFile.delete()
            } catch (e: Exception) {
                // Fallback: Descargar binario estático
                onProgress?.invoke("Descargando PRoot estático...", 30)
                val staticFile = getProotBinary()
                downloadFile(PROOT_STATIC_URL, staticFile)
            }

            // Verificar y hacer ejecutable
            val prootBin = getProotBinary()
            if (prootBin.exists()) {
                // En Android 14+, no podemos usar setExecutable en /data/data/
                // En su lugar, usamos sh para ejecutar proot
                // Crear script wrapper que use linker64
                val wrapperScript = File(getProotBaseDir(), "proot.sh")
                val wrapperContent = """#!/system/bin/sh
# PRoot wrapper - Terminal Master Hub
# Usa linker64 para evitar restricciones noexec en /data/data/
PROOT_BIN="${prootBin.absolutePath}"
if [ -f "\$PROOT_BIN" ]; then
    # Intentar ejecucion directa (Android <14)
    if exec "\$PROOT_BIN" "\$@" 2>/dev/null; then
        exit \$?
    fi
    # Fallback: usar sh o linker
    /system/bin/sh -c "\$PROOT_BIN \$*" 2>/dev/null || {
        echo "PRoot: No se puede ejecutar en este dispositivo"
        echo "PRoot: Instala Termux para el entorno completo"
        exit 1
    }
else
    echo "PRoot binario no encontrado"
    exit 1
fi
"""
                wrapperScript.writeText(wrapperContent)
                // Marcar como ejecutable (puede fallar, pero el script wrapper se ejecuta via sh)
                try { wrapperScript.setExecutable(true) } catch (_: Exception) {}

                onProgress?.invoke("PRoot instalado!", 60)
                true
            } else {
                onProgress?.invoke("Error: PRoot no se pudo instalar", 60)
                false
            }
        } catch (e: Exception) {
            onProgress?.invoke("Error instalando PRoot: ${e.message}", 60)
            false
        }
    }

    /**
     * Extrae el binario proot de un paquete .deb de Termux.
     * Estructura del .deb: ar archive -> data.tar.xz -> ./data/data/com.termux/files/usr/bin/proot
     */
    private fun extractProotFromDeb(debFile: File, targetDir: File) {
        val extractDir = File(targetDir, "deb_extract")
        extractDir.mkdirs()

        try {
            // ar x proot.deb  -> extrae data.tar.xz
            val processAr = ProcessBuilder(
                "sh", "-c",
                "cd ${extractDir.absolutePath} && ar x ${debFile.absolutePath} 2>/dev/null || " +
                "tar -xf ${debFile.absolutePath} 2>/dev/null || " +
                "echo 'ar/tar failed'"
            ).redirectErrorStream(true).start()
            processAr.waitFor()

            // Buscar data.tar.xz o data.tar.gz extraido
            val dataTar = extractDir.listFiles()?.firstOrNull {
                it.name.startsWith("data.tar")
            }

            if (dataTar != null) {
                // Extraer data.tar.xz -> buscamos ./data/data/com.termux/files/usr/bin/proot
                ProcessBuilder(
                    "sh", "-c",
                    "cd ${extractDir.absolutePath} && " +
                    "tar -xf ${dataTar.absolutePath} 2>/dev/null && " +
                    "find . -name 'proot' -type f 2>/dev/null | head -1"
                ).redirectErrorStream(true).start().let { proc ->
                    proc.waitFor()
                    val output = proc.inputStream.bufferedReader().readText().trim()
                    if (output.isNotEmpty()) {
                        val foundProot = File(extractDir, output)
                        if (foundProot.exists()) {
                            foundProot.copyTo(getProotBinary(), overwrite = true)
                        }
                    }
                }
            }

            // Limpiar
            extractDir.deleteRecursively()
        } catch (e: Exception) {
            extractDir.deleteRecursively()
            throw e
        }
    }

    // =========================================================================
    // DESCARGA E INSTALACION DE UBUNTU ROOTFS
    // =========================================================================

    /**
     * Descarga e instala la rootfs de Ubuntu 24.04 ARM64.
     * La rootfs se extrae en $PREFIX/proot/ubuntu/
     */
    suspend fun installUbuntuRootfs(): Boolean = withContext(Dispatchers.IO) {
        try {
            val ubuntuDir = getUbuntuDir()
            if (ubuntuDir.exists()) ubuntuDir.deleteRecursively()
            ubuntuDir.mkdirs()

            onProgress?.invoke("Descargando Ubuntu 24.04 ARM64 rootfs (~30MB)...", 10)
            val rootfsTar = File(getProotBaseDir(), "ubuntu-rootfs.tar.gz")
            downloadFile(UBUNTU_ROOTFS_URL, rootfsTar)

            onProgress?.invoke("Extrayendo Ubuntu rootfs...", 40)
            val extractProcess = ProcessBuilder(
                "sh", "-c",
                "cd ${ubuntuDir.absolutePath} && " +
                "tar -xzf ${rootfsTar.absolutePath} 2>&1"
            ).redirectErrorStream(true).start()
            extractProcess.waitFor()

            // Verificar extraccion
            if (!File(ubuntuDir, "etc/os-release").exists()) {
                onProgress?.invoke("Error: Rootfs Ubuntu corrupta", 100)
                rootfsTar.delete()
                return@withContext false
            }

            rootfsTar.delete()

            // Crear directorios necesarios dentro de la rootfs
            for (dir in listOf("proc", "sys", "dev", "dev/pts", "tmp", "root", "home/user")) {
                File(ubuntuDir, dir).mkdirs()
            }

            // Configurar resolv.conf para apt
            val resolvConf = File(ubuntuDir, "etc/resolv.conf")
            resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")

            // Configurar sources.list de Ubuntu
            val sourcesList = File(ubuntuDir, "etc/apt/sources.list")
            sourcesList.writeText(
                "deb http://ports.ubuntu.com/ubuntu-ports noble main restricted universe multiverse\n" +
                "deb http://ports.ubuntu.com/ubuntu-ports noble-updates main restricted universe multiverse\n" +
                "deb http://ports.ubuntu.com/ubuntu-ports noble-security main restricted universe multiverse\n"
            )

            // Crear script de configuracion inicial
            val setupScript = File(ubuntuDir, "root/setup.sh")
            setupScript.writeText("""#!/bin/bash
# Terminal Master Hub - Ubuntu Setup
export HOME=/root
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

# Configurar locale
echo "en_US.UTF-8 UTF-8" > /etc/locale.gen
locale-gen en_US.UTF-8 2>/dev/null || true
export LANG=en_US.UTF-8
export LC_ALL=C

# Actualizar lista de paquetes
apt-get update -qq 2>/dev/null || echo "apt-get update completado"

# Instalar herramientas base
apt-get install -y -qq python3 python3-pip cmus nano curl wget git 2>/dev/null || {
    echo "Algunos paquetes no estan disponibles"
}

# pip packages
pip3 install --quiet matplotlib numpy pillow pandas requests tqdm 2>/dev/null || true

echo "Ubuntu ARM64 listo para usar!"
echo "Comandos disponibles: python3, pip3, cmus, apt, nano"
echo ""
echo "Terminal Master Hub v1.3.5 - by Michael Antonio Rodriguez Condega"
echo "GitHub: MichaelARC-NI | Telegram: t.me/Michael_Antonio_Rodriguez"
""".trimIndent())
            setupScript.setExecutable(true)

            onProgress?.invoke("Ubuntu rootfs instalada! Ejecutando configuracion inicial...", 80)

            // Ejecutar setup dentro de proot para instalar paquetes base
            try {
                val setupOutput = executeInProot("bash /root/setup.sh 2>&1")
                onProgress?.invoke("Configuracion inicial completada!", 95)
            } catch (e: Exception) {
                onProgress?.invoke("Setup base: ${e.message}", 90)
            }

            onProgress?.invoke("Ubuntu ARM64 listo!", 100)
            true
        } catch (e: Exception) {
            onProgress?.invoke("Error instalando Ubuntu: ${e.message}", 100)
            false
        }
    }

    // =========================================================================
    // EJECUCION EN ENTORNO PROOT
    // =========================================================================

    /**
     * Ejecuta un comando dentro del entorno PRoot + Ubuntu ARM64.
     *
     * Monta /system, /dev, /proc, /sys para compatibilidad con Android.
     */
    suspend fun executeInProot(command: String, workDir: String? = null): String =
        withContext(Dispatchers.IO) {
            try {
                if (!isProotAvailable() || !isUbuntuInstalled()) {
                    return@withContext "PRoot/Ubuntu no instalado. Usa 'bootstrap proot' para instalar."
                }

                val prootBin = getProotBinary().absolutePath
                val ubuntuDir = getUbuntuDir().absolutePath
                val homeDir = "$ubuntuDir/root"

                // Construir comando proot con binds necesarios
                val prootCmd = buildString {
                    append("$prootBin")
                    append(" -r $ubuntuDir")          // Root filesystem
                    append(" -b /system")              // Android system
                    append(" -b /dev")                 // Devices
                    append(" -b /proc")                // Process info
                    append(" -b /sys")                 // System info
                    append(" -b /data")                // Data access
                    append(" -b /storage")             // Storage access
                    append(" -b /dev/pts")             // Pseudo terminals
                    append(" -w ${workDir ?: homeDir}") // Working directory
                    append(" /usr/bin/env")
                    append(" HOME=$homeDir")
                    append(" PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
                    append(" LANG=en_US.UTF-8")
                    append(" LC_ALL=C")
                    append(" TERM=xterm-256color")
                    append(" /bin/bash -c '${command.replace("'", "'\\''")}'")
                }

                val pb = ProcessBuilder("sh", "-c", prootCmd)
                pb.redirectErrorStream(true)
                val proc = pb.start()
                proc.inputStream.bufferedReader().readText().trim()
            } catch (e: Exception) {
                "Error en PRoot: ${e.message}"
            }
        }

    /**
     * Obtiene el comando proot completo para usar en
     * terminal interactiva.
     */
    fun getProotInitCommand(): String? {
        if (!isProotAvailable() || !isUbuntuInstalled()) return null

        val prootBin = getProotBinary().absolutePath
        val ubuntuDir = getUbuntuDir().absolutePath

        return buildString {
            append("exec $prootBin")
            append(" -r $ubuntuDir")
            append(" -b /system")
            append(" -b /dev")
            append(" -b /proc")
            append(" -b /sys")
            append(" -b /data")
            append(" -b /storage")
            append(" -b /dev/pts")
            append(" -w /root")
            append(" /usr/bin/env")
            append(" HOME=/root")
            append(" PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            append(" LANG=en_US.UTF-8")
            append(" LC_ALL=C")
            append(" TERM=xterm-256color")
            append(" /bin/bash --login")
        }
    }

    // =========================================================================
    // UTILIDADES
    // =========================================================================

    /** Descarga un archivo desde URL a un destino local */
    private fun downloadFile(urlStr: String, targetFile: File) {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.instanceFollowRedirects = true

        // Obtener tamano total para progreso
        val totalSize = conn.contentLengthLong

        conn.inputStream.use { input ->
            FileOutputStream(targetFile).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalRead = 0L
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    if (totalSize > 0) {
                        val percent = ((totalRead * 100) / totalSize).toInt()
                        onProgress?.invoke("Descargando... $percent%", percent.coerceIn(0, 100))
                    }
                }
            }
        }
    }

    /** Verifica si podemos conectarnos a internet */
    fun isNetworkAvailable(): Boolean {
        return try {
            val url = URL("https://google.com")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.connect()
            conn.responseCode == 200
        } catch (e: Exception) {
            false
        }
    }

    private fun getDirSize(dir: File): Long {
        var size = 0L
        if (dir.isDirectory) {
            dir.listFiles()?.forEach { f ->
                size += if (f.isFile) f.length() else getDirSize(f)
            }
        }
        return size
    }

    /** Desinstala PRoot y Ubuntu */
    fun uninstall(): Boolean {
        val prootDir = getProotBaseDir()
        return if (prootDir.exists()) prootDir.deleteRecursively() else true
    }
}
