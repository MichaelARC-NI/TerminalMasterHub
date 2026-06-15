package com.terminalmasterhub.core.proot

import android.content.Context
import com.terminalmasterhub.core.root.BootstrapManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.*
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gestor de PRoot + Ubuntu ARM64 para Terminal Master Hub v1.4.1.
 *
 * Proporciona un entorno Linux completo (Ubuntu 24.04 ARM64)
 * usando PRoot para evitar necesidad de root.
 *
 * El Ubuntu rootfs y PRoot vienen EMBEBIDOS en los assets del APK,
 * NO requieren descarga externa. Extraccion automatica en primer uso.
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
        const val UBUNTU_ROOTFS_ASSET = "ubuntu/ubuntu_rootfs"
        const val PROOT_ASSET = "ubuntu/proot-arm64"
        const val LIBTALLOC_ASSET = "ubuntu/libtalloc.so.2"
        const val LIBANDROID_SHMEM_ASSET = "ubuntu/libandroid-shmem.so"
        const val LIBTALLOC_NAME = "libtalloc.so.2"
        const val LIBANDROID_SHMEM_NAME = "libandroid-shmem.so"

        // URLs de descarga por si los assets no estan disponibles
        const val GITHUB_RELEASE = "https://github.com/MichaelARC-NI/TerminalMasterHub/releases/download/v1.4.1"
        const val GITHUB_ROOTFS_URL = "$GITHUB_RELEASE/ubuntu-base-24.04.4-base-arm64.tar.gz"
        const val GITHUB_PROOT_URL = "$GITHUB_RELEASE/proot-arm64"

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

    fun getProotBaseDir(): File = File(
        context.filesDir,
        "${BootstrapManager.PREFIX_DIR}/$PROOT_SUBDIR"
    )

    fun getProotBinary(): File = File(getProotBaseDir(), PROOT_BIN_NAME)
    fun getUbuntuDir(): File = File(getProotBaseDir(), UBUNTU_SUBDIR)

    fun isProotAvailable(): Boolean = getProotBinary().exists()
    fun isUbuntuInstalled(): Boolean {
        val ubuntuDir = getUbuntuDir()
        return ubuntuDir.exists() &&
                File(ubuntuDir, "etc/os-release").exists() &&
                File(ubuntuDir, "usr/bin/apt").exists()
    }

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
            // Instalar librerias necesarias (libtalloc.so.2, libandroid-shmem.so)
            installProotLibraries()

            // Intentar 1: Extraer desde assets
            try {
                context.assets.open(PROOT_ASSET).use { input ->
                    prootBin.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                if (prootBin.exists() && prootBin.length() > 10000) {
                    onProgress?.invoke("PRoot extraido de assets!", 30)
                    return@withContext true
                }
            } catch (e: Exception) {
                onProgress?.invoke("Assets de PRoot no disponibles.", 20)
            }

            // Intentar 2: Descargar desde GitHub
            try {
                onProgress?.invoke("Descargando PRoot desde GitHub...", 20)
                downloadFile(GITHUB_PROOT_URL, prootBin)
                if (prootBin.exists() && prootBin.length() > 10000) {
                    onProgress?.invoke("PRoot descargado de GitHub!", 30)
                    return@withContext true
                }
            } catch (e: Exception) {
                onProgress?.invoke("GitHub no disponible: ${e.message}", 22)
            }

            // Intentar 3: Descargar desde .deb de Termux
            try {
                onProgress?.invoke("Descargando PRoot desde Termux...", 24)
                downloadAndExtractProot(prootDir)
                if (getProotBinary().exists()) {
                    onProgress?.invoke("PRoot instalado desde Termux!", 30)
                    return@withContext true
                }
            } catch (e: Exception) {
                onProgress?.invoke("Termux fallo: ${e.message}", 26)
            }

            onProgress?.invoke("Error: No se pudo obtener PRoot", 40)
            false
        } catch (e: Exception) {
            onProgress?.invoke("Error instalando PRoot: ${e.message}", 50)
            false
        }
    }

    /**
     * Instala las librerias necesarias para PRoot (libtalloc.so.2, libandroid-shmem.so).
     */
    private suspend fun installProotLibraries(): Boolean = withContext(Dispatchers.IO) {
        try {
            val prootDir = getProotBaseDir()
            val libs = listOf(
                LIBTALLOC_ASSET to LIBTALLOC_NAME,
                LIBANDROID_SHMEM_ASSET to LIBANDROID_SHMEM_NAME
            )
            var allOk = true
            for ((asset, name) in libs) {
                val targetFile = File(prootDir, name)
                try {
                    context.assets.open(asset).use { input ->
                        targetFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: Exception) {
                    // If assets not available, try downloading
                    try {
                        val urlMap = mapOf(
                            LIBTALLOC_NAME to "https://github.com/MichaelARC-NI/TerminalMasterHub/releases/download/v1.3.8/libtalloc.so.2",
                            LIBANDROID_SHMEM_NAME to "https://github.com/MichaelARC-NI/TerminalMasterHub/releases/download/v1.3.8/libandroid-shmem.so"
                        )
                        urlMap[name]?.let { url ->
                            downloadFile(url, targetFile)
                        }
                    } catch (e2: Exception) {
                        allOk = false
                    }
                }
                if (!targetFile.exists() || targetFile.length() == 0L) {
                    allOk = false
                }
            }
            allOk
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Instala la rootfs de Ubuntu ARM64.
     * Extrae desde assets primero, descarga como fallback.
     * Usa Apache Commons Compress para extraccion (no necesita tar del sistema).
     */
    suspend fun installUbuntuRootfs(): Boolean = withContext(Dispatchers.IO) {
        try {
            val ubuntuDir = getUbuntuDir()
            if (ubuntuDir.exists()) ubuntuDir.deleteRecursively()
            ubuntuDir.mkdirs()

            var extracted = false

            // Intentar 1: Extraer desde assets del APK
            onProgress?.invoke("Buscando Ubuntu rootfs en assets...", 40)
            try {
                context.assets.open(UBUNTU_ROOTFS_ASSET).use { input ->
                    extractTarGzWithCommons(input, ubuntuDir)
                }
                extracted = true
                onProgress?.invoke("Ubuntu rootfs extraida de assets!", 60)
            } catch (e: Exception) {
                onProgress?.invoke("Assets no disponibles: ${e.message}", 45)
            }

            // Intentar 2: Buscar archivo manual en almacenamiento externo
            if (!extracted) {
                onProgress?.invoke("Buscando rootfs en almacenamiento...", 45)
                try {
                    val storageDirs = listOf(
                        File("/storage/emulated/0/ubuntu-rootfs.tar.gz"),
                        File("/storage/emulated/0/Download/ubuntu-rootfs.tar.gz"),
                        File("/storage/emulated/0/ubuntu_rootfs"),
                        File(context.getExternalFilesDir(null), "ubuntu-rootfs.tar.gz"),
                    )
                    for (f in storageDirs) {
                        if (f.exists() && f.length() > 1000000) {
                            onProgress?.invoke("Rootfs encontrada en: ${f.absolutePath}", 50)
                            f.inputStream().use { input ->
                                extractTarGzWithCommons(input, ubuntuDir)
                            }
                            extracted = true
                            onProgress?.invoke("Ubuntu rootfs extraida de almacenamiento!", 60)
                            break
                        }
                    }
                } catch (_: Exception) {}
            }

            // Intentar 3: Descargar desde internet
            if (!extracted) {
                onProgress?.invoke("Descargando Ubuntu 24.04 ARM64 desde internet...", 50)
                val tarFile = File(getProotBaseDir(), "ubuntu-rootfs.tar.gz")
                try {
                    downloadFile(UBUNTU_ROOTFS_URL, tarFile)
                    if (tarFile.exists() && tarFile.length() > 1000000) {
                        tarFile.inputStream().use { input ->
                            extractTarGzWithCommons(input, ubuntuDir)
                        }
                        extracted = true
                        onProgress?.invoke("Ubuntu descargado e instalado!", 65)
                    }
                } catch (e: Exception) {
                    onProgress?.invoke("Descarga fallo: ${e.message}", 55)
                } finally {
                    tarFile.delete()
                }
            }

            if (!extracted) {
                onProgress?.invoke("No se pudo obtener Ubuntu rootfs", 80)
                return@withContext false
            }

            // Verificar extraccion
            if (!File(ubuntuDir, "etc/os-release").exists()) {
                onProgress?.invoke("Error: Rootfs Ubuntu corrupta o incompleta", 80)
                return@withContext false
            }

            // Crear directorios necesarios
            for (dir in listOf("proc", "sys", "dev", "dev/pts", "tmp", "root", "home/user", "run", "var/lock", "dev/shm")) {
                File(ubuntuDir, dir).mkdirs()
            }

            // Fijar permisos de ejecucion en binarios ELF y scripts
            onProgress?.invoke("Configurando permisos de ejecucion...", 75)
            fixRootfsPermissions(ubuntuDir)

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
    // EXTRACCION TAR.GZ USANDO APACHE COMMONS COMPRESS
    // =========================================================================

    /**
     * Extrae un archivo tar.gz usando Apache Commons Compress.
     * No necesita tar del sistema (noexec safe).
     */
    private fun extractTarGzWithCommons(input: InputStream, targetDir: File) {
        // Guardar a archivo temporal para preservar el stream
        val tmpFile = File(targetDir.parentFile, "temp_ubuntu_rootfs.tar.gz")
        try {
            tmpFile.outputStream().use { output ->
                input.copyTo(output)
            }
            // Extraer usando Commons Compress (no necesita tar del sistema)
            FileInputStream(tmpFile).use { fis ->
                BufferedInputStream(fis).use { bis ->
                    GzipCompressorInputStream(bis).use { gzis ->
                        TarArchiveInputStream(gzis).use { taris ->
                            var entry: TarArchiveEntry? = taris.nextTarEntry
                            while (entry != null) {
                                val outputFile = File(targetDir, entry.name)
                                if (entry.isDirectory) {
                                    outputFile.mkdirs()
                                } else {
                                    outputFile.parentFile?.mkdirs()
                                    FileOutputStream(outputFile).use { fos ->
                                        taris.transferTo(fos)
                                    }
                                }
                                entry = taris.nextTarEntry
                            }
                        }
                    }
                }
            }
        } finally {
            tmpFile.delete()
        }
    }

    private fun configureUbuntu(ubuntuDir: File) {
        File(ubuntuDir, "etc/resolv.conf").writeText(
            "nameserver 8.8.8.8\nnameserver 1.1.1.1\n"
        )
        File(ubuntuDir, "etc/apt/sources.list").writeText(
            "deb http://ports.ubuntu.com/ubuntu-ports noble main restricted universe multiverse\n" +
            "deb http://ports.ubuntu.com/ubuntu-ports noble-updates main restricted universe multiverse\n" +
            "deb http://ports.ubuntu.com/ubuntu-ports noble-security main restricted universe multiverse\n"
        )
        File(ubuntuDir, "etc/locale.gen").writeText("en_US.UTF-8 UTF-8\n")

        // Ensure /tmp exists and has correct permissions
        File(ubuntuDir, "tmp").mkdirs()
        File(ubuntuDir, "dev/shm").mkdirs()
        File(ubuntuDir, "run").mkdirs()
        File(ubuntuDir, "var/lock").mkdirs()

        // Set execute permissions on critical binaries in the rootfs
        // (they may have been lost during extraction)
        fixRootfsPermissions(ubuntuDir)
    }

    /**
     * Recursively sets execute permission on ELF binaries and scripts in the rootfs.
     * This is necessary because extraction via Commons Compress may not preserve
     * executable bits, and Android's noexec mount prevents using chmod.
     * PRoot needs the execute bit to be set even though the kernel blocks execve
     * on noexec filesystems - PRoot uses ptrace to work around this.
     */
    private fun fixRootfsPermissions(ubuntuDir: File) {
        val binDirs = listOf("bin", "sbin", "usr/bin", "usr/sbin", "usr/local/bin", "usr/local/sbin", "usr/libexec")
        for (relDir in binDirs) {
            val dir = File(ubuntuDir, relDir)
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.forEach { f ->
                    if (f.isFile) {
                        try {
                            // Check if it's an ELF binary or script (starts with ELF or #!)
                            val fis = java.io.FileInputStream(f)
                            val magic = ByteArray(4)
                            fis.read(magic)
                            fis.close()
                            if (magic[0] == 0x7f && magic[1] == 0x45 && magic[2] == 0x4c && magic[3] == 0x46) {
                                // ELF binary
                                f.setExecutable(true, false)
                            } else if (magic[0] == '#'.code.toByte() && magic[1] == '!'.code.toByte()) {
                                // Script with shebang
                                f.setExecutable(true, false)
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    private suspend fun runInitialSetup(ubuntuDir: String) {
        val setupCmd = buildString {
            append("apt-get update -qq 2>/dev/null; ")
            append("apt-get install -y -qq ")
            append("python3 python3-pip python3-venv cmus nano neovim curl wget git ")
            append("ca-certificates openssl tar gzip zstd p7zip-full unzip unrar ")
            append("adb fastboot android-sdk-platform-tools-common ")
            append("build-essential gcc g++ make cmake pkg-config ")
            append("nodejs npm openjdk-17-jdk-headless 2>/dev/null; ")
            append("pip3 install --quiet matplotlib numpy pillow requests tqdm ")
            append("beautifulsoup4 flask scipy pandas pyyaml rich psutil 2>/dev/null; ")
            append("locale-gen en_US.UTF-8 2>/dev/null; ")
            append("echo 'Setup completado'")
        }
        val result = executeInProot(setupCmd)
    }

    // =========================================================================
    // EJECUCION VIA PROOT + linker64 (noexec safe)
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
                val prootDir = getProotBaseDir().absolutePath
                val ubuntuDir = getUbuntuDir().absolutePath
                val homeDir = "$ubuntuDir/root"
                val linkerPath = "/system/bin/linker64"
                val tmpDir = "$ubuntuDir/tmp"

                // LD_LIBRARY_PATH: librerias de PRoot (libtalloc, libandroid-shmem)
                val ldLibPath = "$prootDir:/system/lib64:/vendor/lib64"
                // PROOT_TMP_DIR: PRoot necesita un directorio temporal escribible
                val prootTmpDir = tmpDir

                val prootCmd = buildString {
                    append("PROOT_TMP_DIR=$prootTmpDir ")
                    append("LD_LIBRARY_PATH=$ldLibPath ")
                    append(linkerPath)
                    append(" $prootBin")
                    append(" -r $ubuntuDir")
                    append(" -b /system")
                    append(" -b /dev")
                    append(" -b /proc")
                    append(" -b /sys")
                    append(" -b /storage")
                    append(" -b /dev/pts")
                    append(" -w ${workDir ?: homeDir}")
                    append(" /usr/bin/env")
                    append(" HOME=$homeDir")
                    append(" PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
                    append(" LANG=en_US.UTF-8")
                    append(" LC_ALL=C")
                    append(" TERM=xterm-256color")
                    append(" PROOT_TMP_DIR=$prootTmpDir")
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
        val prootDir = getProotBaseDir().absolutePath
        val ubuntuDir = getUbuntuDir().absolutePath
        val ldLibPath = "$prootDir:/system/lib64:/vendor/lib64"
        val tmpDir = "$ubuntuDir/tmp"

        return buildString {
            append("exec env PROOT_TMP_DIR=$tmpDir LD_LIBRARY_PATH=$ldLibPath /system/bin/linker64 $prootBin")
            append(" -r $ubuntuDir")
            append(" -b /system")
            append(" -b /dev")
            append(" -b /proc")
            append(" -b /sys")
            append(" -b /storage")
            append(" -b /dev/pts")
            append(" -w /root")
            append(" /usr/bin/env")
            append(" HOME=/root")
            append(" PROOT_TMP_DIR=$tmpDir")
            append(" PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            append(" LANG=en_US.UTF-8")
            append(" LC_ALL=C")
            append(" TERM=xterm-256color")
            append(" /bin/bash --login")
        }
    }

    // =========================================================================
    // DESCARGA Y EXTRACCION DE PRoot DESDE DEB
    // =========================================================================

    private fun downloadAndExtractProot(targetDir: File) {
        val debFile = File(targetDir, "proot.deb")
        try {
            downloadFile(PROOT_DEB_URL, debFile)
            val extractDir = File(targetDir, "deb_extract")
            extractDir.mkdirs()

            val process = ProcessBuilder(
                "sh", "-c",
                "cd ${extractDir.absolutePath} && " +
                "ar x ${debFile.absolutePath} 2>/dev/null; " +
                "if [ -f data.tar.xz ]; then " +
                "  tar -xf data.tar.xz 2>/dev/null; " +
                "elif [ -f data.tar.gz ]; then " +
                "  tar -xzf data.tar.gz 2>/dev/null; " +
                "fi; " +
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

    // =========================================================================
    // DESCARGA DE ARCHIVOS
    // =========================================================================

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

    fun isNetworkAvailable(): Boolean {
        val hosts = listOf(
            "https://google.com",
            "https://github.com",
            "https://cdimage.ubuntu.com",
            "https://packages.termux.dev"
        )
        for (host in hosts) {
            try {
                val url = URL(host)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.connect()
                if (conn.responseCode in 200..499) return true
            } catch (_: Exception) {}
        }
        return false
    }

    fun uninstall(): Boolean {
        val prootDir = getProotBaseDir()
        return if (prootDir.exists()) prootDir.deleteRecursively() else true
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
}
