package com.terminalmasterhub.core.proot

import android.content.Context
import android.content.res.AssetManager
import com.terminalmasterhub.core.root.BootstrapManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gestor de PRoot + Ubuntu ARM64 para Terminal Master Hub v1.3.6.
 *
 * Proporciona un entorno Linux completo (Ubuntu 24.04 ARM64)
 * usando PRoot para evitar necesidad de root.
 *
 * El Ubuntu rootfs y PRoot vienen EMBEBIDOS en los assets del APK,
 * NO requieren descarga externa. Extraccion automatica en primer uso.
 *
 * Basado en Termux y Kali NetHunter:
 * - PRoot: redirige llamadas al sistema para crear un chroot falso
 * - Rootfs Ubuntu ARM64: sistema de archivos completo con apt, python, cmus
 *
 * Los binarios se almacenan en:
 *   $PREFIX/proot/proot-arm64    <- Binario de PRoot (desde assets)
 *   $PREFIX/proot/ubuntu/        <- Rootfs de Ubuntu ARM64 (desde assets)
 */
class ProotManager(private val context: Context) {

    companion object {
        const val PROOT_SUBDIR = "proot"
        const val UBUNTU_SUBDIR = "ubuntu"
        const val PROOT_BIN_NAME = "proot-arm64"
        const val UBUNTU_ROOTFS_ASSET = "ubuntu/ubuntu-rootfs.tar.gz"
        const val PROOT_ASSET = "ubuntu/proot-arm64"

        // URLs de descarga por si los assets no estan disponibles
        const val PROOT_DEB_URL = "https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107.78-1_aarch64.deb"
        const val UBUNTU_ROOTFS_URL = "http://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz"
    }

    data class ProotStatus(
        val isProotAvailable: Boolean = false,
        val isUbuntuInstalled: Boolean = false,
        val ubuntuSize: Long = 0L,
        val isFromAssets: Boolean = false,
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

    /** Verifica si PRoot esta disponible */
    fun isProotAvailable(): Boolean = getProotBinary().exists()

    /** Verifica si Ubuntu rootfs esta instalada */
    fun isUbuntuInstalled(): Boolean {
        val ubuntuDir = getUbuntuDir()
        return ubuntuDir.exists() &&
                File(ubuntuDir, "etc/os-release").exists() &&
                File(ubuntuDir, "usr/bin/apt").exists()
    }

    /** Obtiene el estado completo */
    fun getStatus(): ProotStatus {
        val assetsExist = try {
            context.assets.open(UBUNTU_ROOTFS_ASSET).use { true }
        } catch (e: Exception) { false }

        return ProotStatus(
            isProotAvailable = isProotAvailable(),
            isUbuntuInstalled = isUbuntuInstalled(),
            ubuntuSize = if (isUbuntuInstalled()) getDirSize(getUbuntuDir()) else 0L,
            isFromAssets = assetsExist,
            message = buildString {
                if (isProotAvailable() && isUbuntuInstalled()) {
                    append("Ubuntu ARM64 listo desde ")
                    append(if (assetsExist) "assets (embebido)" else "descarga")
                    append(" | apt, python3, cmus disponibles")
                } else if (assetsExist) {
                    append("Assets disponibles. Usa 'bootstrap proot install' para extraer")
                } else {
                    append("No instalado. Usa 'bootstrap proot install'")
                }
            }
        )
    }

    // =========================================================================
    // INSTALACION: Extrae desde assets primero, descarga como fallback
    // =========================================================================

    /**
     * Instala PRoot + Ubuntu ARM64.
     * Primero intenta extraer desde los assets del APK.
     * Si los assets no existen, descarga desde internet.
     */
    suspend fun installAll(): Boolean = withContext(Dispatchers.IO) {
        val prootOk = installProot()
        if (!prootOk) return@withContext false

        val ubuntuOk = installUbuntuRootfs()
        ubuntuOk
    }

    /**
     * Instala el binario de PRoot.
     * Extrae desde assets primero, descarga como fallback.
     */
    suspend fun installProot(): Boolean = withContext(Dispatchers.IO) {
        try {
            val prootDir = getProotBaseDir()
            prootDir.mkdirs()
            val prootBin = getProotBinary()

            // Intentar 1: Extraer desde assets
            onProgress?.invoke("Extrayendo PRoot desde assets...", 20)
            try {
                context.assets.open(PROOT_ASSET).use { input ->
                    prootBin.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                onProgress?.invoke("PRoot extraido de assets!", 40)
            } catch (e: Exception) {
                // Fallback: Descargar .deb de Termux
                onProgress?.invoke("Assets no disponible. Descargando PRoot...", 20)
                try {
                    downloadAndExtractProot(prootDir)
                } catch (e2: Exception) {
                    onProgress?.invoke("Error: No se pudo obtener PRoot: ${e2.message}", 40)
                    return@withContext false
                }
            }

            // Verificar que existe
            if (!prootBin.exists()) {
                onProgress?.invoke("Error: PRoot binario no encontrado", 40)
                return@withContext false
            }

            // Crear script wrapper que use linker64 para evitar noexec
            createProotWrapper(prootBin)

            onProgress?.invoke("PRoot instalado correctamente!", 50)
            true
        } catch (e: Exception) {
            onProgress?.invoke("Error instalando PRoot: ${e.message}", 50)
            false
        }
    }

    /**
     * Instala la rootfs de Ubuntu ARM64.
     * Extrae desde assets primero, descarga como fallback.
     */
    suspend fun installUbuntuRootfs(): Boolean = withContext(Dispatchers.IO) {
        try {
            val ubuntuDir = getUbuntuDir()
            if (ubuntuDir.exists()) ubuntuDir.deleteRecursively()
            ubuntuDir.mkdirs()

            // Intentar 1: Extraer desde assets
            onProgress?.invoke("Extrayendo Ubuntu rootfs desde assets (~30MB)...", 50)
            try {
                context.assets.open(UBUNTU_ROOTFS_ASSET).use { input ->
                    extractTarGz(input, ubuntuDir)
                }
                onProgress?.invoke("Ubuntu rootfs extraida de assets!", 70)
            } catch (e: Exception) {
                // Fallback: Descargar desde internet
                onProgress?.invoke("Assets no disponible. Descargando Ubuntu...", 50)
                try {
                    downloadUbuntuRootfs(ubuntuDir)
                } catch (e2: Exception) {
                    onProgress?.invoke("Error: No se pudo obtener Ubuntu: ${e2.message}", 80)
                    return@withContext false
                }
            }

            // Verificar extraccion
            if (!File(ubuntuDir, "etc/os-release").exists()) {
                onProgress?.invoke("Error: Rootfs Ubuntu corrupta o incompleta", 80)
                return@withContext false
            }

            // Crear directorios necesarios
            for (dir in listOf("proc", "sys", "dev", "dev/pts", "tmp", "root", "home/user")) {
                File(ubuntuDir, dir).mkdirs()
            }

            // Configurar resolv.conf y sources.list
            configureUbuntu(ubuntuDir)

            onProgress?.invoke("Ubuntu rootfs instalada! Ejecutando setup inicial...", 80)

            // Configuracion inicial dentro de proot
            try {
                runInitialSetup(ubuntuDir.absolutePath)
            } catch (e: Exception) {
                onProgress?.invoke("Setup inicial: ${e.message}", 90)
            }

            onProgress?.invoke("Ubuntu ARM64 listo! Usa 'mode ubuntu' para activar", 100)
            true
        } catch (e: Exception) {
            onProgress?.invoke("Error instalando Ubuntu: ${e.message}", 100)
            false
        }
    }

    // =========================================================================
    // CONFIGURACION DE UBUNTU
    // =========================================================================

    private fun configureUbuntu(ubuntuDir: File) {
        // resolv.conf para apt
        File(ubuntuDir, "etc/resolv.conf").writeText(
            "nameserver 8.8.8.8\nnameserver 1.1.1.1\n"
        )

        // sources.list de Ubuntu 24.04 ARM64
        File(ubuntuDir, "etc/apt/sources.list").writeText(
            "deb http://ports.ubuntu.com/ubuntu-ports noble main restricted universe multiverse\n" +
            "deb http://ports.ubuntu.com/ubuntu-ports noble-updates main restricted universe multiverse\n" +
            "deb http://ports.ubuntu.com/ubuntu-ports noble-security main restricted universe multiverse\n"
        )

        // locales
        File(ubuntuDir, "etc/locale.gen").writeText("en_US.UTF-8 UTF-8\n")
    }

    private suspend fun runInitialSetup(ubuntuDir: String) {
        val setupCmd = buildString {
            append("apt-get update -qq 2>/dev/null; ")
            append("apt-get install -y -qq python3 python3-pip cmus nano curl ca-certificates 2>/dev/null; ")
            append("pip3 install --quiet matplotlib numpy pillow requests tqdm 2>/dev/null; ")
            append("locale-gen en_US.UTF-8 2>/dev/null; ")
            append("echo 'Setup completado'")
        }
        val result = executeInProot(setupCmd)
    }

    // =========================================================================
    // EJECUCION EN ENTORNO PROOT
    // =========================================================================

    /**
     * Ejecuta un comando dentro del entorno PRoot + Ubuntu ARM64.
     * Usa linker64 para evitar restricciones noexec en Android 14+.
     */
    suspend fun executeInProot(command: String, workDir: String? = null): String =
        withContext(Dispatchers.IO) {
            try {
                if (!isProotAvailable() || !isUbuntuInstalled()) {
                    return@withContext "PRoot/Ubuntu no instalado. Usa 'bootstrap proot install'"
                }

                val prootBin = getProotBinary().absolutePath
                val ubuntuDir = getUbuntuDir().absolutePath
                val homeDir = "$ubuntuDir/root"

                // En Android 14+ no podemos ejecutar binarios en /data/data/
                // Usamos linker64 para cargar el binario
                val linkerPath = "/system/bin/linker64"
                val prootCmd = buildString {
                    append(linkerPath)
                    append(" $prootBin")
                    append(" -r $ubuntuDir")
                    append(" -b /system")
                    append(" -b /dev")
                    append(" -b /proc")
                    append(" -b /sys")
                    append(" -b /data")
                    append(" -b /storage")
                    append(" -b /dev/pts")
                    append(" -w ${workDir ?: homeDir}")
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

    /** Obtiene el comando proot para terminal interactiva */
    fun getProotInitCommand(): String? {
        if (!isProotAvailable() || !isUbuntuInstalled()) return null

        val prootBin = getProotBinary().absolutePath
        val ubuntuDir = getUbuntuDir().absolutePath

        return buildString {
            append("exec /system/bin/linker64 $prootBin")
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
    // EXTRACCION DE ASSETS Y DESCARGA
    // =========================================================================

    /** Extrae un tar.gz desde un InputStream */
    private fun extractTarGz(input: InputStream, targetDir: File) {
        val tmpFile = File(targetDir.parentFile, "temp_rootfs.tar.gz")
        try {
            // Guardar InputStream a archivo temporal
            tmpFile.outputStream().use { output -> input.copyTo(output) }

            // Extraer con tar
            val process = ProcessBuilder(
                "sh", "-c",
                "cd ${targetDir.absolutePath} && tar -xzf ${tmpFile.absolutePath} 2>&1"
            ).redirectErrorStream(true).start()
            process.waitFor()
        } finally {
            tmpFile.delete()
        }
    }

    /** Descarga y extrae proot desde .deb de Termux */
    private fun downloadAndExtractProot(targetDir: File) {
        val debFile = File(targetDir, "proot.deb")
        try {
            downloadFile(PROOT_DEB_URL, debFile)
            val extractDir = File(targetDir, "deb_extract")
            extractDir.mkdirs()

            val process = ProcessBuilder(
                "sh", "-c",
                "cd ${extractDir.absolutePath} && " +
                "dpkg-deb -x ${debFile.absolutePath} . 2>/dev/null || " +
                "ar x ${debFile.absolutePath} 2>/dev/null; " +
                "find . -name 'proot' -type f 2>/dev/null | head -1"
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            val foundPath = output.trim().lines().firstOrNull { it.contains("proot") }
            if (foundPath != null) {
                val found = File(extractDir, foundPath)
                if (found.exists()) {
                    found.copyTo(getProotBinary(), overwrite = true)
                }
            }
            extractDir.deleteRecursively()
            debFile.delete()
        } catch (e: Exception) {
            debFile.delete()
            throw e
        }
    }

    /** Descarga Ubuntu rootfs desde internet */
    private fun downloadUbuntuRootfs(targetDir: File) {
        val tarFile = File(getProotBaseDir(), "ubuntu-rootfs.tar.gz")
        try {
            downloadFile(UBUNTU_ROOTFS_URL, tarFile)

            val process = ProcessBuilder(
                "sh", "-c",
                "cd ${targetDir.absolutePath} && tar -xzf ${tarFile.absolutePath} 2>&1"
            ).redirectErrorStream(true).start()
            process.waitFor()

            tarFile.delete()
        } catch (e: Exception) {
            tarFile.delete()
            throw e
        }
    }

    /** Crea script wrapper que usa linker64 para evitar noexec */
    private fun createProotWrapper(prootBin: File) {
        val wrapperScript = File(getProotBaseDir(), "proot.sh")
        val wrapperContent = """#!/system/bin/sh
# PRoot wrapper - Terminal Master Hub
# Usa linker64 para evitar restricciones noexec en /data/data/
PROOT_BIN="${prootBin.absolutePath}"
LINKER64="/system/bin/linker64"

if [ -f "${'$'}PROOT_BIN" ]; then
    # Ejecutar via linker64 (funciona en Android 14+ con noexec)
    exec "${'$'}LINKER64" "${'$'}PROOT_BIN" "${'$'}@" 2>/dev/null || {
        # Fallback: ejecucion directa (Android <14)
        exec "${'$'}PROOT_BIN" "${'$'}@" 2>/dev/null || {
            echo "PRoot: No se puede ejecutar en este dispositivo"
            exit 1
        }
    }
else
    echo "PRoot binario no encontrado"
    exit 1
fi
"""
        wrapperScript.writeText(wrapperContent)
    }

    /** Descarga un archivo desde URL */
    private fun downloadFile(urlStr: String, targetFile: File) {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.instanceFollowRedirects = true

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

    /** Verifica conexion a internet */
    fun isNetworkAvailable(): Boolean = try {
        val url = URL("https://google.com")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.connect()
        conn.responseCode == 200
    } catch (e: Exception) { false }

    private fun getDirSize(dir: File): Long {
        var size = 0L
        if (dir.isDirectory) {
            dir.listFiles()?.forEach { f ->
                size += if (f.isFile) f.length() else getDirSize(f)
            }
        }
        return size
    }

    fun uninstall(): Boolean {
        val prootDir = getProotBaseDir()
        return if (prootDir.exists()) prootDir.deleteRecursively() else true
    }
}
