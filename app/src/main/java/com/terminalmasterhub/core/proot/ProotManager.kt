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
 * Gestor de PRoot + Ubuntu ARM64 para Terminal Master Hub v1.5.1.
 *
 * ARQUITECTURA (v1.5.1):
 * Usamos PRoot para ejecutar binarios de Ubuntu (glibc) en Android.
 * PRoot usa ptrace para interceptar syscalls y traducir rutas.
 *
 * Cadena de ejecucion:
 *   PROOT_TMP_DIR=<dir> LD_LIBRARY_PATH=<dir> /system/bin/linker64
 *     -> /prefix/proot/proot-arm64 (Android/bionic binary)
 *       -> ptrace -> /prefix/proot/ubuntu/bin/bash (glibc)
 *         -> apt, python3, cmus, etc.
 *
 * PRoot binary (proot-arm64) esta compilado para Android/bionic
 * (interpreter: /system/bin/linker64), por lo que el linker de Android
 * PUEDE cargarlo. Las librerias glibc de Ubuntu se ejecutan via ptrace.
 *
 * Librerias necesarias para PRoot:
 * - libtalloc.so.2 (en assets)
 * - libandroid-shmem.so (en assets)
 *
 * El rootfs (ubuntu_rootfs, 29MB) contiene Ubuntu 24.04 ARM64 base.
 * Al ejecutar 'bootstrap proot install' se extrae y configura.
 */
class ProotManager(private val context: Context) {

    companion object {
        const val PROOT_SUBDIR = "proot"
        const val UBUNTU_SUBDIR = "ubuntu"
        const val PROOT_BIN_NAME = "proot-arm64"
        const val LIBTALLOC_NAME = "libtalloc.so.2"
        const val LIBANDROID_SHMEM_NAME = "libandroid-shmem.so"

        const val UBUNTU_ROOTFS_ASSET = "ubuntu/ubuntu_rootfs"
        const val PROOT_ASSET = "ubuntu/proot-arm64"
        const val LIBTALLOC_ASSET = "ubuntu/libtalloc.so.2"
        const val LIBANDROID_SHMEM_ASSET = "ubuntu/libandroid-shmem.so"

        const val GITHUB_RELEASE = "https://github.com/MichaelARC-NI/TerminalMasterHub/releases/download/v1.4.1"
        const val GITHUB_PROOT_URL = "$GITHUB_RELEASE/proot-arm64"
        const val GITHUB_LIBTALLOC_URL = "$GITHUB_RELEASE/libtalloc.so.2"
        const val GITHUB_LIBANDROID_SHMEM_URL = "$GITHUB_RELEASE/libandroid-shmem.so"

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

    /** PRoot esta disponible si el binario existe */
    fun isProotAvailable(): Boolean = getProotBinary().exists()

    /** Ubuntu esta instalado si /etc/os-release existe */
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
    // INSTALACION
    // =========================================================================

    suspend fun installAll(): Boolean = withContext(Dispatchers.IO) {
        val prootOk = installProot()
        if (!prootOk) return@withContext false
        val ubuntuOk = installUbuntuRootfs()
        ubuntuOk
    }

    /**
     * Instala PRoot binary + librerias desde assets o descarga.
     */
    suspend fun installProot(): Boolean = withContext(Dispatchers.IO) {
        try {
            val prootDir = getProotBaseDir()
            prootDir.mkdirs()
            val prootBin = getProotBinary()

            // 1. Instalar librerias PRoot
            installProotLibrary(LIBTALLOC_ASSET, LIBTALLOC_NAME, GITHUB_LIBTALLOC_URL)
            installProotLibrary(LIBANDROID_SHMEM_ASSET, LIBANDROID_SHMEM_NAME, GITHUB_LIBANDROID_SHMEM_URL)

            // 2. Instalar binario PRoot
            var installed = false

            // Intentar desde assets
            try {
                context.assets.open(PROOT_ASSET).use { input ->
                    prootBin.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                if (prootBin.exists() && prootBin.length() > 10000) {
                    installed = true
                    onProgress?.invoke("PRoot extraido de assets!", 30)
                }
            } catch (e: Exception) {
                onProgress?.invoke("Assets de PRoot no disponibles.", 15)
            }

            // Fallback: descargar
            if (!installed) {
                try {
                    onProgress?.invoke("Descargando PRoot desde GitHub...", 20)
                    downloadFile(GITHUB_PROOT_URL, prootBin)
                    if (prootBin.exists() && prootBin.length() > 10000) {
                        installed = true
                        onProgress?.invoke("PRoot descargado de GitHub!", 30)
                    }
                } catch (e: Exception) {
                    onProgress?.invoke("GitHub no disponible: ${e.message}", 22)
                }
            }

            // Fallback 2: extraer desde .deb de Termux
            if (!installed) {
                try {
                    onProgress?.invoke("Descargando PRoot desde Termux repo...", 25)
                    downloadAndExtractProot(prootDir)
                    if (prootBin.exists() && prootBin.length() > 10000) {
                        installed = true
                        onProgress?.invoke("PRoot instalado desde Termux!", 30)
                    }
                } catch (e: Exception) {
                    onProgress?.invoke("Termux no disponible: ${e.message}", 25)
                }
            }

            return@withContext installed
        } catch (e: Exception) {
            onProgress?.invoke("Error instalando PRoot: ${e.message}", 0)
            return@withContext false
        }
    }

    private fun installProotLibrary(asset: String, libName: String, fallbackUrl: String) {
        val prootDir = getProotBaseDir()
        val libFile = File(prootDir, libName)

        // Intentar desde assets
        try {
            context.assets.open(asset).use { input ->
                libFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            // Fallback: descargar
            try {
                downloadFile(fallbackUrl, libFile)
            } catch (e2: Exception) {
                // Ignorar, puede que no sea necesaria
            }
        }
    }

    /**
     * Instala Ubuntu rootfs desde assets o descarga.
     */
    suspend fun installUbuntuRootfs(): Boolean = withContext(Dispatchers.IO) {
        try {
            val ubuntuDir = getUbuntuDir()
            if (isUbuntuInstalled()) {
                onProgress?.invoke("Ubuntu ya instalado!", 100)
                return@withContext true
            }

            ubuntuDir.mkdirs()
            onProgress?.invoke("Buscando Ubuntu rootfs en assets...", 10)

            var source: String? = null

            // 1. Intentar desde assets embebidos
            try {
                context.assets.open(UBUNTU_ROOTFS_ASSET).use {
                    onProgress?.invoke("Rootfs encontrado en assets! Extrayendo...", 20)
                    extractTarGzWithCommons(it, ubuntuDir)
                    source = "assets"
                }
            } catch (e: Exception) {
                onProgress?.invoke("Assets no encontrados.", 15)
            }

            // 2. Fallback: archivo en /sdcard/ubuntu-rootfs.tar.gz
            if (source == null) {
                val sdcardFile = File("/sdcard/ubuntu-rootfs.tar.gz")
                if (sdcardFile.exists()) {
                    onProgress?.invoke("Rootfs encontrado en /sdcard! Extrayendo...", 25)
                    FileInputStream(sdcardFile).use { fis ->
                        extractTarGzWithCommons(fis, ubuntuDir)
                    }
                    source = "sdcard"
                }
            }

            // 3. Fallback: descargar desde internet
            if (source == null) {
                if (!isNetworkAvailable()) {
                    onProgress?.invoke("Sin assets y sin internet. Descarga el APK completo.", 0)
                    return@withContext false
                }
                onProgress?.invoke("Descargando Ubuntu rootfs (~30MB)...", 20)
                val rootfsFile = File(ubuntuDir.parentFile, "ubuntu-rootfs.tar.gz")
                try {
                    downloadFile(UBUNTU_ROOTFS_URL, rootfsFile)
                    FileInputStream(rootfsFile).use { fis ->
                        extractTarGzWithCommons(fis, ubuntuDir)
                    }
                    rootfsFile.delete()
                    source = "download"
                } catch (e: Exception) {
                    onProgress?.invoke("Error descargando: ${e.message}", 0)
                    return@withContext false
                }
            }

            if (!isUbuntuInstalled() && !File(ubuntuDir, "etc/os-release").exists()) {
                onProgress?.invoke("Error: rootfs no extraido correctamente", 0)
                return@withContext false
            }

            onProgress?.invoke("Ubuntu rootfs instalada! Configurando...", 60)
            configureUbuntu(ubuntuDir)

            onProgress?.invoke("Ubuntu ARM64 instalado!", 70)
            return@withContext true
        } catch (e: Exception) {
            onProgress?.invoke("Error: ${e.message}", 0)
            return@withContext false
        }
    }

    // =========================================================================
    // CONFIGURACION DE UBUNTU
    // =========================================================================

    private fun configureUbuntu(ubuntuDir: File) {
        try {
            // Create essential directories (including bin/)
            listOf("root", "tmp", "dev", "proc", "sys", "dev/pts", "etc/apt", "usr/bin", "bin", "usr/local/bin", "usr/local/sbin").forEach { dir ->
                File(ubuntuDir, dir).mkdirs()
            }

            // /etc/resolv.conf para DNS
            File(ubuntuDir, "etc/resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")

            // /etc/apt/sources.list con repositorios Ubuntu
            File(ubuntuDir, "etc/apt/sources.list").writeText(
                "deb http://ports.ubuntu.com/ubuntu-ports noble main restricted universe multiverse\n" +
                "deb http://ports.ubuntu.com/ubuntu-ports noble-updates main restricted universe multiverse\n" +
                "deb http://ports.ubuntu.com/ubuntu-ports noble-security main restricted universe multiverse\n"
            )

            // Create a minimal /usr/bin/env script that works on noexec
            // Since Android 14+ mounts /data/data as noexec, we can't use the real /usr/bin/env
            // Instead we use a shell script that just exec's the command
            // PRoot will handle the execution via ptrace
            val envScript = File(ubuntuDir, "usr/bin/env")
            if (!envScript.exists() || envScript.length() < 10) {
                envScript.writeText("#!/bin/bash\n/usr/bin/bash -c \"\$@\"\n")
            }

            // Ensure /bin/bash exists (may be at /usr/bin/bash)
            // In Ubuntu 24.04, /bin is a symlink to /usr/bin
            // PRoot may not follow symlinks correctly, so ensure both exist
            val binBash = File(ubuntuDir, "bin/bash")
            val usrBinBash = File(ubuntuDir, "usr/bin/bash")
            if (!binBash.exists() && usrBinBash.exists()) {
                binBash.parentFile?.mkdirs()
                // Create hardlink or copy instead of symlink (symlinks may fail on some FS)
                usrBinBash.copyTo(binBash, overwrite = true)
                binBash.setExecutable(true)
            }
            // Also ensure /bin/sh exists
            val binSh = File(ubuntuDir, "bin/sh")
            val usrBinSh = File(ubuntuDir, "usr/bin/sh")
            if (!binSh.exists() && usrBinSh.exists()) {
                binSh.parentFile?.mkdirs()
                usrBinSh.copyTo(binSh, overwrite = true)
                binSh.setExecutable(true)
            }

            // /.bashrc para el prompt
            val bashrc = File(ubuntuDir, "root/.bashrc")
            bashrc.parentFile?.mkdirs()
            bashrc.writeText(
                "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n" +
                "export HOME=/root\n" +
                "export LANG=en_US.UTF-8\n" +
                "export LC_ALL=C\n" +
                "export TERM=xterm-256color\n" +
                "export TMPDIR=/tmp\n" +
                "export PAGER=cat\n" +
                "alias ll='ls -la'\n" +
                "alias la='ls -A'\n" +
                "alias l='ls -CF'\n" +
                "if command -v python3 &>/dev/null; then\n" +
                "  alias python=python3\n" +
                "  alias pip=pip3\n" +
                "fi\n" +
                "PS1='\\[\\033[1;32m\\]TerminalMaster\\[\\033[0m\\]:\\[\\033[1;34m\\]\\w\\[\\033[0m\\]$ '\n"
            )

        } catch (e: Exception) {
            onProgress?.invoke("Configuracion: ${e.message}", 55)
        }
    }

    // =========================================================================
    // EJECUCION VIA PROOT + linker64 (noexec safe)
    // =========================================================================

    /**
     * Ejecuta un comando dentro del entorno PRoot + Ubuntu ARM64.
     * Usa linker64 + PRoot para evitar restricciones noexec en Android 14+.
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
                val tmpDir = "$ubuntuDir/tmp"
                File(tmpDir).mkdirs()
                File(homeDir).mkdirs()

                // LD_LIBRARY_PATH: librerias de PRoot (libtalloc, libandroid-shmem)
                val ldLibPath = "$prootDir:/system/lib64:/vendor/lib64"
                // PROOT_TMP_DIR: PRoot necesita directorio temporal escribible
                val prootTmpDir = tmpDir

                // Comando bash escapado
                val escapedCmd = command.replace("'", "'\\''")

                // Construir comando PRoot completo
                // El primer binario que ejecuta PRoot es /bin/bash (glibc)
                // PRoot intercepta execve via ptrace, por lo que no necesita exec permissions
                val prootCmd = buildString {
                    append("PROOT_TMP_DIR=$prootTmpDir ")
                    append("LD_LIBRARY_PATH=$ldLibPath ")
                    append("/system/bin/linker64")
                    append(" $prootBin")
                    append(" -r $ubuntuDir")
                    append(" -b /system")
                    append(" -b /dev")
                    append(" -b /proc")
                    append(" -b /sys")
                    append(" -b /storage")
                    append(" -b /dev/pts")
                    val adbNativeDir = File(context.filesDir, "adb-native")
                    if (adbNativeDir.exists()) {
                        append(" -b ${adbNativeDir.absolutePath}:/adb-native")
                    }
                    // Use PROOT_TMP_DIR env var for PRoot temp directory
                    append(" -w ${workDir ?: homeDir}")
                    append(" /bin/bash --login -c '")
                    append("export PROOT_TMP_DIR=$prootTmpDir TMPDIR=$tmpDir; ")
                    append("$escapedCmd'")
                }

                val pb = ProcessBuilder("sh", "-c", prootCmd)
                pb.redirectErrorStream(true)
                val proc = pb.start()
                val output = proc.inputStream.bufferedReader().readText().trim()
                output.ifEmpty { "" }
            } catch (e: Exception) {
                "Error en PRoot: ${e.message}"
            }
        }

    /**
     * Obtiene el comando proot para terminal interactiva (usado en 'mode ubuntu').
     */
    fun getProotInitCommand(): String? {
        if (!isProotAvailable() || !isUbuntuInstalled()) return null
        val prootBin = getProotBinary().absolutePath
        val prootDir = getProotBaseDir().absolutePath
        val ubuntuDir = getUbuntuDir().absolutePath
        val tmpDir = "$ubuntuDir/tmp"
        val ldLibPath = "$prootDir:/system/lib64:/vendor/lib64"
        File(tmpDir).mkdirs()
        File("$ubuntuDir/root").mkdirs()

        // FIX v1.5.2: NO usamos /usr/bin/env
        // usamos bash --login con export de variables de entorno
        return buildString {
            append("exec env PROOT_TMP_DIR=$tmpDir LD_LIBRARY_PATH=$ldLibPath /system/bin/linker64 $prootBin")
            append(" -r $ubuntuDir")
            append(" -b /system")
            append(" -b /dev")
            append(" -b /proc")
            append(" -b /sys")
            append(" -b /storage")
            append(" -b /dev/pts")
            val adbNativeDir = File(context.filesDir, "adb-native")
            if (adbNativeDir.exists()) {
                append(" -b ${adbNativeDir.absolutePath}:/adb-native")
            }
            append(" -w /root")
            append(" /bin/bash --login")
        }
    }

    /**
     * Ejecuta un binario especifico de Ubuntu via PRoot.
     */
    suspend fun executeUbuntuBinary(binary: String, args: String = ""): String =
        executeInProot("$binary $args")

    // =========================================================================
    // DESCARGA Y EXTRACCION DE PRoot DESDE DEB DE TERMUX
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

            // Buscar el binario extraido
            val extracted = File(extractDir, "data/data/com.termux/files/usr/bin/proot")
            val ext2 = File(extractDir, "usr/bin/proot")
            if (extracted.exists()) {
                extracted.copyTo(getProotBinary(), overwrite = true)
            } else if (ext2.exists()) {
                ext2.copyTo(getProotBinary(), overwrite = true)
            }
            debFile.delete()
            extractDir.deleteRecursively()
        } catch (e: Exception) {
            onProgress?.invoke("Extraccion DEB: ${e.message}", 25)
        }
    }

    // =========================================================================
    // SETUP INICIAL (paquetes base)
    // =========================================================================

    /**
     * Configura el entorno Ubuntu con paquetes iniciales y herramientas.
     * Se ejecuta automaticamente despues de 'bootstrap proot install'.
     */
    suspend fun runInitialSetup() {
        val ubuntuDir = getUbuntuDir().absolutePath
        try {
            onProgress?.invoke("Configurando repositorios...", 65)

            // Paso 1: apt update
            onProgress?.invoke("Actualizando repositorios (apt update)...", 68)
            executeInProot("apt-get update -qq 2>&1 | tail -3 || true")

            // Paso 2: Instalar herramientas base
            onProgress?.invoke("Instalando bash y herramientas basicas...", 75)
            val pkgs = arrayOf(
                "bash", "python3", "python3-pip", "python3-venv",
                "cmus", "nano", "curl", "wget", "git",
                "ca-certificates", "openssl", "tar", "gzip",
                "zstd", "unzip", "adb", "fastboot",
                "build-essential", "make"
            )
            val pkgList = pkgs.joinToString(" ")
            executeInProot(
                "DEBIAN_FRONTEND=noninteractive apt-get install -y -qq $pkgList 2>&1 | tail -5 || " +
                "echo 'install parcial: algunos paquetes no disponibles'"
            )

            // Paso 3: Paquetes Python
            onProgress?.invoke("Instalando paquetes Python...", 85)
            executeInProot(
                "pip3 install --quiet --no-cache-dir matplotlib numpy pillow requests tqdm " +
                "beautifulsoup4 flask scipy pandas pyyaml rich psutil 2>&1 | tail -3 || " +
                "echo 'pip parcial'"
            )

            // Paso 4: Configurar locale
            onProgress?.invoke("Configurando locale...", 90)
            executeInProot(
                "apt-get install -y -qq locales 2>/dev/null; " +
                "locale-gen en_US.UTF-8 2>/dev/null || true"
            )

            onProgress?.invoke("Paquetes instalados!", 95)
        } catch (e: Exception) {
            onProgress?.invoke("Setup inicial: ${e.message}", 80)
        }
    }

    // =========================================================================
    // EXTRACCION TAR.GZ
    // =========================================================================

    private fun extractTarGzWithCommons(input: InputStream, targetDir: File) {
        val tmpFile = File(targetDir.parentFile, "temp_ubuntu_rootfs.tar.gz")
        try {
            tmpFile.outputStream().use { output ->
                input.copyTo(output)
            }
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
