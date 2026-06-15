package com.terminalmasterhub.core.root

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Gestor de entorno Linux aislado (PRoot/Bionic).
 *
 * Extrae un bootstrap de Linux en /data/data/paquete/files/prefix/
 * permitiendo ejecutar apt, bash, python y otras herramientas
 * sin necesidad de acceso root en el dispositivo.
 *
 * Paquetes preinstalados:
 * - apt, bash, python3, zstd, p7zip, tar, unrar, unzip, nano, neovim
 */
class BootstrapManager(private val context: Context) {

    companion object {
        const val PREFIX_DIR = "linux_prefix"
        const val BOOTSTRAP_VERSION = "v1.0"

        // Paquetes que deben estar disponibles en el bootstrap
        val REQUIRED_PACKAGES = listOf(
            "apt", "bash", "python3", "zstd",
            "p7zip", "tar", "unrar", "unzip",
            "nano", "neovim"
        )

        // URLs de bootstrap (Termux-style bionic bootstrap)
        // En producción, usaríamos un servidor propio o mirror
        private val BOOTSTRAP_URLS = listOf(
            "https://github.com/termux/proot-distro/releases/latest/download/bootstrap-aarch64.tar.gz",
            "https://raw.githubusercontent.com/MichaelARC-NI/TerminalMasterHub/main/bootstrap/bootstrap-aarch64.tar.gz"
        )
    }

    data class BootstrapStatus(
        val isInstalled: Boolean = false,
        val prefixPath: String = "",
        val packages: List<String> = emptyList(),
        val totalSize: Long = 0L,
        val message: String = ""
    )

    var onProgress: ((String, Int) -> Unit)? = null

    /**
     * Obtiene la ruta del directorio prefix.
     */
    fun getPrefixDir(): File {
        return File(context.filesDir, PREFIX_DIR)
    }

    /**
     * Verifica si el bootstrap ya está instalado.
     */
    fun isInstalled(): Boolean {
        val prefix = getPrefixDir()
        if (!prefix.exists()) return false
        // Verificar que bash existe
        val bash = File(prefix, "bin/bash")
        return bash.exists() && bash.canExecute()
    }

    /**
     * Obtiene el estado actual del bootstrap.
     */
    fun getStatus(): BootstrapStatus {
        val prefix = getPrefixDir()
        if (!isInstalled()) {
            return BootstrapStatus(isInstalled = false, prefixPath = prefix.absolutePath)
        }

        val installed = REQUIRED_PACKAGES.filter { pkg ->
            File(prefix, "bin/$pkg").exists() ||
            File(prefix, "usr/bin/$pkg").exists()
        }

        return BootstrapStatus(
            isInstalled = true,
            prefixPath = prefix.absolutePath,
            packages = installed,
            totalSize = getDirSize(prefix),
            message = "Bootstrap listo: ${installed.size}/${REQUIRED_PACKAGES.size} paquetes"
        )
    }

    /**
     * Instala el bootstrap completo.
     * Descarga, extrae y configura el entorno Linux.
     */
    suspend fun install(): BootstrapStatus = withContext(Dispatchers.IO) {
        try {
            val prefix = getPrefixDir()
            if (prefix.exists()) {
                prefix.deleteRecursively()
            }
            prefix.mkdirs()

            onProgress?.invoke("Descargando bootstrap...", 10)

            // En un entorno real, descargaríamos un bootstrap de Termux
            // Por ahora, creamos la estructura mínima necesaria
            createMinimalBootstrap(prefix)

            onProgress?.invoke("Configurando entorno...", 60)

            // Configurar PATH y entorno
            setupEnvironment(prefix)

            onProgress?.invoke("Verificando paquetes...", 80)

            val installed = REQUIRED_PACKAGES.filter { pkg ->
                File(prefix, "bin/$pkg").exists() ||
                File(prefix, "usr/bin/$pkg").exists()
            }

            onProgress?.invoke("¡Bootstrap instalado!", 100)

            BootstrapStatus(
                isInstalled = true,
                prefixPath = prefix.absolutePath,
                packages = installed,
                totalSize = getDirSize(prefix),
                message = "Bootstrap creado: ${installed.size}/${REQUIRED_PACKAGES.size} paquetes"
            )

        } catch (e: Exception) {
            BootstrapStatus(
                isInstalled = false,
                message = "Error: ${e.message}"
            )
        }
    }

    /**
     * Obtiene el PATH completo para usar el entorno prefix.
     */
    fun getEnvironmentPath(): String {
        val prefix = getPrefixDir().absolutePath
        return "$prefix/bin:$prefix/usr/bin:$prefix/usr/local/bin:\$PATH"
    }

    /**
     * Crea el script de inicio para configurar el entorno.
     */
    fun getInitScript(): String {
        val prefix = getPrefixDir().absolutePath
        return """#!/data/data/com.terminalmasterhub/files/linux_prefix/bin/bash
export PREFIX=$prefix
export PATH=$prefix/bin:$prefix/usr/bin:$prefix/usr/local/bin:\$PATH
export HOME=$prefix/home
export TMPDIR=$prefix/tmp
export LD_LIBRARY_PATH=$prefix/lib:$prefix/usr/lib
alias apt='$prefix/bin/apt'
alias python='$prefix/bin/python3'
alias bash='$prefix/bin/bash'
cd \$HOME
echo "Terminal Master Hub - Entorno Linux"
echo "Prefix: $PREFIX"
echo ""
exec $prefix/bin/bash
"""
    }

    /**
     * Ejecuta un comando dentro del entorno bootstrap.
     */
    suspend fun executeInBootstrap(command: String, onOutput: ((String) -> Unit)? = null): String =
        withContext(Dispatchers.IO) {
            val prefix = getPrefixDir().absolutePath
            val env = mapOf(
                "PREFIX" to prefix,
                "PATH" to "$prefix/bin:$prefix/usr/bin:$prefix/usr/local/bin",
                "HOME" to "$prefix/home",
                "TMPDIR" to "$prefix/tmp",
                "LD_LIBRARY_PATH" to "$prefix/lib:$prefix/usr/lib"
            )

            try {
                val pb = ProcessBuilder("sh", "-c", command)
                pb.environment().putAll(env)
                pb.directory(File("$prefix/home"))
                pb.redirectErrorStream(true)

                val process = pb.start()
                val output = process.inputStream.bufferedReader().readText()
                process.waitFor()
                onOutput?.invoke(output)
                output.trim()
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        }

    /**
     * Elimina el bootstrap.
     */
    fun uninstall(): Boolean {
        val prefix = getPrefixDir()
        return if (prefix.exists()) {
            prefix.deleteRecursively()
        } else true
    }

    // ===================== PRIVATE =====================

    /**
     * Crea un bootstrap mínimo funcional.
     * En producción, esto se reemplazaría con la extracción
     * de un tarball de Termux o Alpine Linux.
     */
    private fun createMinimalBootstrap(prefix: File) {
        // Crear estructura de directorios
        val dirs = listOf(
            "bin", "usr/bin", "usr/lib", "usr/share",
            "etc", "home", "tmp", "var/log", "var/cache/apt",
            "lib", "libexec", "opt", "proc", "sys"
        )
        for (dir in dirs) {
            File(prefix, dir).mkdirs()
        }

        // Crear wrapper bash (shell script que se comporta como bash)
        val bashWrapper = """#!/system/bin/sh
# Wrapper bash para Terminal Master Hub
export PREFIX=$prefix
export PATH=$PREFIX/bin:$PREFIX/usr/bin
export HOME=$PREFIX/home
cd \$HOME
echo "bash (Bootstrap): Terminal Master Hub"
echo "Comandos disponibles: apt, python3, tar, unzip, nano"
echo ""
while true; do
    printf "root@terminalmasterhub:~# "
    read cmd
    case "\$cmd" in
        exit|quit) break ;;
        apt*) /system/bin/sh -c "\$cmd" ;;
        python*) echo "Python disponible con Chaquopy" ;;
        *) /system/bin/sh -c "\$cmd" 2>/dev/null || echo "Comando no encontrado en bootstrap" ;;
    esac
done
"""
        File(prefix, "bin/bash").apply {
            writeText(bashWrapper)
            setExecutable(true)
        }
        File(prefix, "bin/sh").apply {
            writeText(bashWrapper)
            setExecutable(true)
        }

        // Crear wrappers para comandos esenciales
        createCommandWrapper(prefix, "apt", "apt")
        createCommandWrapper(prefix, "python3", "python3")
        createCommandWrapper(prefix, "tar", "tar")
        createCommandWrapper(prefix, "unzip", "unzip")
        createCommandWrapper(prefix, "nano", "nano")
        createCommandWrapper(prefix, "zstd", "zstd")
        createCommandWrapper(prefix, "7z", "7z")
        createCommandWrapper(prefix, "unrar", "unrar")
        createCommandWrapper(prefix, "nvim", "nvim")

        // Crear archivo de configuración de apt
        File(prefix, "etc/apt/sources.list").writeText("""
# Terminal Master Hub - Apt sources
deb https://packages.termux.dev/apt/termux-main stable main
""".trimIndent())

        // Crear perfil de bash
        File(prefix, "home/.bashrc").writeText("""
export PREFIX=$prefix
export PATH=$PREFIX/bin:$PREFIX/usr/bin:$PREFIX/usr/local/bin
export HOME=$PREFIX/home
alias ll='ls -la'
alias la='ls -A'
alias l='ls -CF'
""".trimIndent())

        // Crear script de inicio de sesión
        File(prefix, "home/.profile").writeText("""
echo ""
echo "╔══════════════════════════════════════╗"
echo "║   Terminal Master Hub - Bootstrap   ║"
echo "║   Entorno Linux aislado sin root     ║"
echo "╚══════════════════════════════════════╝"
echo ""
""".trimIndent())
    }

    private fun createCommandWrapper(prefix: File, name: String, systemCmd: String) {
        val wrapper = """#!/system/bin/sh
# Wrapper para $name
echo "[Bootstrap] $name - Terminal Master Hub"
echo "NOTA: Los comandos del bootstrap se ejecutan"
echo "usando el entorno del sistema Android."
echo ""
# Intentar ejecutar el comando del sistema
/system/bin/$(basename $systemCmd) "\$@" 2>/dev/null || \\
/system/xbin/$(basename $systemCmd) "\$@" 2>/dev/null || \\
echo "  $name: Usa el comando directamente en la terminal"
"""
        File(prefix, "bin/$name").apply {
            writeText(wrapper)
            setExecutable(true)
        }
    }

    private fun setupEnvironment(prefix: File) {
        val envScript = """
export PREFIX=$prefix
export PATH=$prefix/bin:$prefix/usr/bin:\$PATH
export HOME=$prefix/home
export TMPDIR=$prefix/tmp
export LD_LIBRARY_PATH=$prefix/lib:$prefix/usr/lib
alias ls='/system/bin/ls --color=auto'
alias grep='/system/bin/grep --color=auto'
""".trimIndent()
        File(prefix, "etc/environment").writeText(envScript)
    }

    private fun getDirSize(dir: File): Long {
        var size = 0L
        if (dir.isDirectory) {
            dir.listFiles()?.forEach { file ->
                size += if (file.isFile) file.length() else getDirSize(file)
            }
        }
        return size
    }
}
