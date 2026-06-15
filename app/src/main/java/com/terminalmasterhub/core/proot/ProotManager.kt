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
 * Gestor de Ubuntu ARM64 para Terminal Master Hub v1.5.0.
 *
 * ARQUITECTURA NUEVA (v1.5.0):
 * En lugar de usar PRoot (que requiere libtalloc.so.2 y falla en Android 14+),
 * ejecutamos los binarios de Ubuntu DIRECTAMENTE usando el linker del rootfs.
 *
 * Cadena de ejecucion:
 *   /system/bin/linker64                          <- Android loader (noexec safe)
 *     → /prefix/proot/ubuntu/usr/lib/ld-linux...  <- Ubuntu linker (static, 204KB)
 *       → /prefix/proot/ubuntu/usr/bin/bash       <- Bash real de Ubuntu
 *         → apt, python3, cmus, etc.              <- Todos funcionan!
 *
 * Ventajas:
 * - NO necesita libtalloc.so.2 (se elimina esta dependencia)
 * - NO necesita PRoot (se elimina el binario de 229KB)
 * - NO tiene problemas de PROOT_TMP_DIR
 * - NO tiene problemas de permisos de ejecucion
 * - Los binarios de Ubuntu funcionan con sus librerias nativas (glibc)
 * - apt update, apt install, pip install funcionan correctamente
 *
 * El rootfs (ubuntu_rootfs, 29MB) ya contiene apt y librerias base.
 * Al ejecutar 'bootstrap proot install' se instalan bash, python3, cmus via apt.
 */
class ProotManager(private val context: Context) {

    companion object {
        const val PROOT_SUBDIR = "proot"
        const val UBUNTU_SUBDIR = "ubuntu"
        const val UBUNTU_ROOTFS_ASSET = "ubuntu/ubuntu_rootfs"
        const val PROOT_ASSET = "ubuntu/proot-arm64"
        const val LIBTALLOC_ASSET = "ubuntu/libtalloc.so.2"
        const val LIBANDROID_SHMEM_ASSET = "ubuntu/libandroid-shmem.so"

        // Linker de Ubuntu (estatico, dentro del rootfs)
        const val UBUNTU_LINKER_REL = "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"
        // Librerias de Ubuntu
        const val UBUNTU_LIB_REL = "usr/lib/aarch64-linux-gnu"
        // Shell de Ubuntu
        const val UBUNTU_BASH_REL = "bin/bash"

        // URLs de descarga
        const val GITHUB_RELEASE = "https://github.com/MichaelARC-NI/TerminalMasterHub/releases/download/v1.4.1"
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

    fun getUbuntuDir(): File = File(getProotBaseDir(), UBUNTU_SUBDIR)

    /** Ruta al linker estatico de Ubuntu (ld-linux-aarch64.so.1) */
    fun getUbuntuLinker(): File = File(getUbuntuDir(), UBUNTU_LINKER_REL)

    /** Ruta a bash de Ubuntu */
    fun getUbuntuBash(): File = File(getUbuntuDir(), UBUNTU_BASH_REL)

    /** Ruta a las librerias de Ubuntu */
    fun getUbuntuLibDir(): File = File(getUbuntuDir(), UBUNTU_LIB_REL)

    /**
     * Verifica si Ubuntu rootfs esta instalada y tiene el linker disponible.
     * Esto es suficiente para ejecutar cualquier binario de Ubuntu.
     */
    fun isUbuntuInstalled(): Boolean {
        val ubuntuDir = getUbuntuDir()
        return ubuntuDir.exists() &&
               getUbuntuLinker().exists() &&
               File(ubuntuDir, "etc/os-release").exists()
    }

    /** Compatibilidad hacia atras - ya no necesitamos PRoot binario */
    fun isProotAvailable(): Boolean = isUbuntuInstalled()

    fun getStatus(): ProotStatus {
        val assetsExist = try {
            context.assets.open(UBUNTU_ROOTFS_ASSET).use { true }
        } catch (e: Exception) { false }

        return ProotStatus(
            isProotAvailable = isUbuntuInstalled(),
            isUbuntuInstalled = isUbuntuInstalled(),
            ubuntuSize = if (isUbuntuInstalled()) getDirSize(getUbuntuDir()) else 0L,
            isFromAssets = assetsExist,
            message = buildString {
                if (isUbuntuInstalled()) {
                    append("Ubuntu ARM64 listo via linker directo")
                    append(" | bash, apt, python3 disponibles")
                } else if (assetsExist) {
                    append("Assets disponibles. Usa 'bootstrap proot install'")
                } else {
                    append("No instalado. Usa 'bootstrap proot install'")
                }
            }
        )
    }

    // =========================================================================
    // NUEVO: EJECUCION DIRECTA VIA LINKER DE UBUNTU (v1.5.0)
    // =========================================================================

    /**
     * Ejecuta un comando DENTRO del rootfs de Ubuntu usando su propio linker.
     *
     * Como funciona:
     *   1. /system/bin/linker64 carga ld-linux-aarch64.so.1 (estatico, 204KB)
     *   2. ld-linux-aarch64.so.1 (el linker de glibc) toma el control
     *   3. Busca las librerias en --library-path (glibc .so)
     *   4. Carga y ejecuta el binario objetivo (bash, python3, apt...)
     *
     * Esto funciona en Android 14+ porque linker64 puede leer archivos en /data/data/
     * aunque este montado noexec. linker64 solo necesita poder leer los archivos,
     * no ejecutarlos directamente (eso lo hace el kernel).
     *
     * @param command Comando a ejecutar (ej. "apt update")
     * @param workDir Directorio de trabajo (default: /root en rootfs)
     * @return Salida del comando
     */
    suspend fun executeInProot(command: String, workDir: String? = null): String =
        withContext(Dispatchers.IO) {
            try {
                if (!isUbuntuInstalled()) {
                    return@withContext "Ubuntu no instalado. Usa 'bootstrap proot install'"
                }

                val ubuntuDir = getUbuntuDir().absolutePath
                val linker = getUbuntuLinker().absolutePath
                val libPath = getUbuntuLibDir().absolutePath
                val bashPath = getUbuntuBash().absolutePath
                val homeDir = "$ubuntuDir/root"
                val androidLinker = "/system/bin/linker64"

                // El comando se pasa a bash -c 'comando'
                val bashCmd = "/bin/bash -c '${command.replace("'", "'\\''")}'"

                // Construir comando completo:
                // linker64 ld-linux --library-path <libs> /bin/bash -c 'comando'
                val fullCmd = buildString {
                    append(androidLinker)
                    append(" $linker")
                    append(" --library-path $libPath")
                    append(" $bashPath")
                    append(" -c '${command.replace("'", "'\\''")}'")
                }

                val env = mapOf(
                    "HOME" to homeDir,
                    "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                    "LANG" to "en_US.UTF-8",
                    "LC_ALL" to "C",
                    "TERM" to "xterm-256color",
                    "TMPDIR" to "$ubuntuDir/tmp"
                )

                val pb = ProcessBuilder("sh", "-c", fullCmd)
                pb.environment().putAll(env)
                pb.directory(File(workDir ?: homeDir))
                pb.redirectErrorStream(true)
                val proc = pb.start()

                val output = proc.inputStream.bufferedReader().readText().trim()
                output
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        }

    /**
     * Obtiene el comando para inicializar una terminal interactiva con bash de Ubuntu.
     * Esto se usa en el .bashrc y en 'mode ubuntu'.
     */
    fun getBashInitCommand(): String? {
        if (!isUbuntuInstalled()) return null
        val ubuntuDir = getUbuntuDir().absolutePath
        val linker = getUbuntuLinker().absolutePath
        val libPath = getUbuntuLibDir().absolutePath
        val bashPath = getUbuntuBash().absolutePath
        val androidLinker = "/system/bin/linker64"

        return buildString {
            append("exec $androidLinker $linker")
            append(" --library-path $libPath")
            append(" $bashPath --login")
        }
    }

    /**
     * Ejecuta un binario especifico de Ubuntu (no bash -c).
     * Util para ejecutar binarios directamente como python3 script.py
     */
    suspend fun executeUbuntuBinary(binary: String, args: String = ""): String =
        withContext(Dispatchers.IO) {
            try {
                if (!isUbuntuInstalled()) {
                    return@withContext "Ubuntu no instalado"
                }
                val ubuntuDir = getUbuntuDir().absolutePath
                val linker = getUbuntuLinker().absolutePath
                val libPath = getUbuntuLibDir().absolutePath
                val binPath = File(ubuntuDir, binary)
                val androidLinker = "/system/bin/linker64"

                if (!binPath.exists()) {
                    return@withContext "Binario no encontrado: $binary"
                }

                val fullCmd = "$androidLinker $linker --library-path $libPath ${binPath.absolutePath} $args"
                val pb = ProcessBuilder("sh", "-c", fullCmd)
                pb.redirectErrorStream(true)
                val proc = pb.start()
                proc.inputStream.bufferedReader().readText().trim()
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        }

    // =========================================================================
    // INSTALACION DE UBUNTU ROOTFS
    // =========================================================================

    suspend fun installAll(): Boolean = withContext(Dispatchers.IO) {
        val ubuntuOk = installUbuntuRootfs()
        if (!ubuntuOk) return@withContext false
        // Instalar paquetes basicos via apt
        try {
            runInitialSetup(getUbuntuDir().absolutePath)
        } catch (_: Exception) {}
        true
    }

    /** Compatibilidad hacia atras */
    suspend fun installProot(): Boolean = installUbuntuRootfs()

    /**
     * Instala la rootfs de Ubuntu ARM64.
     * Extrae desde assets, fallback a /sdcard/, fallback a descarga.
     */
    suspend fun installUbuntuRootfs(): Boolean = withContext(Dispatchers.IO) {
        try {
            val ubuntuDir = getUbuntuDir()
            if (ubuntuDir.exists()) ubuntuDir.deleteRecursively()
            ubuntuDir.mkdirs()

            var extracted = false

            // Intentar 1: Extraer desde assets del APK
            onProgress?.invoke("Buscando Ubuntu rootfs en assets...", 10)
            try {
                context.assets.open(UBUNTU_ROOTFS_ASSET).use { input ->
                    extractTarGzWithCommons(input, ubuntuDir)
                }
                extracted = true
                onProgress?.invoke("Ubuntu rootfs extraida de assets!", 40)
            } catch (e: Exception) {
                onProgress?.invoke("Assets no disponibles: ${e.message}", 15)
            }

            // Intentar 2: Buscar archivo manual en almacenamiento externo
            if (!extracted) {
                onProgress?.invoke("Buscando rootfs en almacenamiento...", 20)
                try {
                    val storageDirs = listOf(
                        File("/storage/emulated/0/ubuntu-rootfs.tar.gz"),
                        File("/storage/emulated/0/Download/ubuntu-rootfs.tar.gz"),
                        File("/storage/emulated/0/ubuntu_rootfs"),
                    )
                    for (f in storageDirs) {
                        if (f.exists() && f.length() > 1000000) {
                            f.inputStream().use { input ->
                                extractTarGzWithCommons(input, ubuntuDir)
                            }
                            extracted = true
                            onProgress?.invoke("Ubuntu rootfs extraida de almacenamiento!", 40)
                            break
                        }
                    }
                } catch (_: Exception) {}
            }

            // Intentar 3: Descargar desde internet
            if (!extracted) {
                onProgress?.invoke("Descargando Ubuntu 24.04 ARM64...", 25)
                val tarFile = File(getProotBaseDir(), "ubuntu-rootfs.tar.gz")
                try {
                    downloadFile(UBUNTU_ROOTFS_URL, tarFile)
                    if (tarFile.exists() && tarFile.length() > 1000000) {
                        tarFile.inputStream().use { input ->
                            extractTarGzWithCommons(input, ubuntuDir)
                        }
                        extracted = true
                        onProgress?.invoke("Ubuntu descargado e instalado!", 45)
                    }
                } catch (e: Exception) {
                    onProgress?.invoke("Descarga fallo: ${e.message}", 30)
                } finally {
                    tarFile.delete()
                }
            }

            if (!extracted) {
                onProgress?.invoke("No se pudo obtener Ubuntu rootfs", 50)
                return@withContext false
            }

            // Verificar extraccion
            if (!getUbuntuLinker().exists()) {
                onProgress?.invoke("Error: Rootfs corrupta - linker no encontrado", 50)
                return@withContext false
            }

            // Crear directorios necesarios
            for (dir in listOf("proc", "sys", "dev", "dev/pts", "tmp", "root", "home/user", "run", "var/lock", "dev/shm")) {
                File(ubuntuDir, dir).mkdirs()
            }

            // Configurar red y repositorios
            configureUbuntu(ubuntuDir)

            onProgress?.invoke("Ubuntu rootfs instalada! Instalando paquetes iniciales...", 60)

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
        File(ubuntuDir, "etc/resolv.conf").writeText(
            "nameserver 8.8.8.8\nnameserver 1.1.1.1\n"
        )
        File(ubuntuDir, "etc/apt/sources.list").writeText(
            "deb http://ports.ubuntu.com/ubuntu-ports noble main restricted universe multiverse\n" +
            "deb http://ports.ubuntu.com/ubuntu-ports noble-updates main restricted universe multiverse\n" +
            "deb http://ports.ubuntu.com/ubuntu-ports noble-security main restricted universe multiverse\n"
        )
        File(ubuntuDir, "etc/locale.gen").writeText("en_US.UTF-8 UTF-8\n")
    }

    /**
     * Ejecuta apt-get update e instala paquetes esenciales.
     */
    private suspend fun runInitialSetup(ubuntuDir: String) {
        try {
            // Paso 1: apt-get update
            onProgress?.invoke("Actualizando repositorios apt...", 65)
            var result = executeInProot("apt-get update -qq 2>&1 || echo 'update fallo'")
            
            // Paso 2: Instalar bash (viene en Ubuntu base pero asegurar)
            onProgress?.invoke("Instalando bash y herramientas basicas...", 75)
            result = executeInProot(
                "apt-get install -y -qq bash python3 python3-pip python3-venv " +
                "cmus nano neovim curl wget git ca-certificates openssl " +
                "tar gzip zstd p7zip-full unzip unrar adb fastboot " +
                "build-essential 2>&1 | tail -5 || echo 'install parcial'"
            )

            // Paso 3: Paquetes Python
            onProgress?.invoke("Instalando paquetes Python...", 85)
            result = executeInProot(
                "pip3 install --quiet matplotlib numpy pillow requests tqdm " +
                "beautifulsoup4 flask scipy pandas pyyaml rich psutil 2>&1 | tail -3 || echo 'pip parcial'"
            )

            // Paso 4: Configurar locale
            onProgress?.invoke("Configurando locale...", 90)
            executeInProot("locale-gen en_US.UTF-8 2>/dev/null || true")

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
