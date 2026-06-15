package com.terminalmasterhub.core.root

import android.content.Context
import com.terminalmasterhub.core.proot.ProotManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Gestor del entorno Linux aislado para Terminal Master Hub v1.4.1.
 *
 * En Android 14+, /data/data/ se monta con noexec, lo que impide
 * ejecutar directamente binarios almacenados ahi.
 *
 * Estrategia actual:
 * 1. NO creamos scripts ejecutables en $PREFIX/bin/ (no funcionan con noexec)
 * 2. Solo creamos archivos de configuracion (.bashrc, etc.)
 * 3. Para ejecutar comandos Linux, usamos PRoot + Ubuntu rootfs via linker64
 * 4. PRoot se ejecuta con /system/bin/linker64 para evitar noexec
 */
class BootstrapManager(private val context: Context) {

    companion object {
        const val PREFIX_DIR = "usr"
        const val HOME_DIR = "home"

        val REQUIRED_PACKAGES = listOf(
            "apt", "bash", "python3", "zstd",
            "p7zip", "tar", "unrar", "unzip",
            "nano", "neovim", "cmus"
        ).sorted()

        val PYTHON_PACKAGES = listOf(
            "matplotlib", "numpy", "pillow", "pandas",
            "seaborn", "plotly", "scipy", "requests",
            "beautifulsoup4", "flask", "tqdm",
            "sympy", "scikit-learn", "jupyter-client",
            "ipykernel", "networkx", "opencv-python-headless",
            "colorama", "rich", "psutil", "pyyaml",
            "click", "jinja2", "markdown", "redis",
            "fastapi", "uvicorn", "httpx", "aiohttp",
            "websockets", "python-dotenv", "pydantic",
            "imageio", "pygame-ce", "numba",
            "pytest", "coverage", "black", "isort",
            "flake8", "mypy", "pre-commit"
        )

        fun getPrefixPath(context: Context): String =
            File(context.filesDir, PREFIX_DIR).absolutePath
    }

    data class BootstrapStatus(
        val isInstalled: Boolean = false,
        val prefixPath: String = "",
        val packages: List<String> = emptyList(),
        val pythonPackagesInstalled: Int = 0,
        val totalSize: Long = 0L,
        val message: String = ""
    )

    var onProgress: ((String, Int) -> Unit)? = null

    fun getPrefixDir(): File = File(context.filesDir, PREFIX_DIR)
    fun getHomeDir(): File = File(getPrefixDir(), HOME_DIR)

    fun isInstalled(): Boolean {
        val prefix = getPrefixDir()
        return prefix.exists() && File(prefix, HOME_DIR).exists()
    }

    fun getStatus(): BootstrapStatus {
        val prefix = getPrefixDir()
        val p = prefix.absolutePath
        if (!isInstalled()) {
            return BootstrapStatus(prefixPath = p, message = "No instalado")
        }
        val ubuntuOk = try {
            val pm = ProotManager(context)
            pm.isUbuntuInstalled()
        } catch (e: Exception) { false }

        val pySitePkgs = File(prefix, "lib/python3/site-packages")
        val pyCount = if (pySitePkgs.exists()) {
            pySitePkgs.listFiles()?.count {
                it.isDirectory || it.name.endsWith(".py") || it.name.endsWith(".dist-info")
            } ?: 0
        } else 0

        return BootstrapStatus(
            isInstalled = true,
            prefixPath = p,
            packages = if (ubuntuOk) REQUIRED_PACKAGES else emptyList(),
            pythonPackagesInstalled = pyCount,
            totalSize = getDirSize(prefix),
            message = buildString {
                append("PREFIX=$p | ")
                if (ubuntuOk) append("Ubuntu listo (${REQUIRED_PACKAGES.size} tools)")
                else append("Usa 'bootstrap proot install' para Ubuntu")
                if (pyCount > 0) append(" | Python: $pyCount modulos")
            }
        )
    }

    suspend fun install(): BootstrapStatus = withContext(Dispatchers.IO) {
        try {
            val prefix = getPrefixDir()
            val prefixPath = prefix.absolutePath
            val homePath = "$prefixPath/$HOME_DIR"
            if (prefix.exists()) prefix.deleteRecursively()
            prefix.mkdirs()

            onProgress?.invoke("Creando estructura de directorios...", 10)
            for (dir in listOf(
                "bin", "etc", "lib", "lib/python3", "lib/python3/site-packages",
                HOME_DIR, "home/.config/cmus", "tmp", "var/log"
            )) {
                File(prefix, dir).mkdirs()
            }

            onProgress?.invoke("Configurando scripts de entorno...", 30)

            val envContent = """
# Terminal Master Hub - Environment Configuration
PREFIX=$prefixPath
HOME=$homePath
PATH=/system/bin:/system/xbin
TMPDIR=$prefixPath/tmp
LANG=en_US.UTF-8
LC_ALL=C
""".trimIndent()
            File(prefix, "etc/environment").writeText(envContent)

            onProgress?.invoke("Creando .bashrc...", 50)
            // Construir .bashrc - los $ se escapan con un replace post-raw-string
            val bashrcContent = """
# ============================================================================
# Terminal Master Hub - .bashrc v1.4.1
# ============================================================================

alias ll='ls -la'
alias la='ls -A'
alias l='ls -CF'
alias ..='cd ..'
alias ...='cd ../..'

# Prompt profesional con color
PS1='\033[1;32mTerminalMaster\033[0m:\033[1;34m\w\033[0m\$ '

export PATH=/system/bin:/system/xbin

# Python startup
export PYTHONSTARTUP=DOLLARHOME/.pythonrc
if [ ! -f DOLLARHOME/.pythonrc ]; then
    cat > DOLLARHOME/.pythonrc << 'PYEOF'
import sys
print(f"Python {sys.version.split()[0]} - Terminal Master Hub")
PYEOF
fi

# ============================================================================
# ALIAS PARA UBUNTU VIA PROOT+linker64 (noexec safe)
# ============================================================================
PROOT_DIR=DOLLARPREFIX/proot
PROOT_BIN=DOLLARPROOT_DIR/proot-arm64
UBUNTU_DIR=DOLLARPROOT_DIR/ubuntu
LINKER64=/system/bin/linker64

if [ -f DOLLARPROOT_BIN ] && [ -d DOLLARUBUNTU_DIR/usr/bin ]; then
    PROOT_CMD=DOLLARLINKER64 DOLLARPROOT_BIN -r DOLLARUBUNTU_DIR -b /system -b /dev -b /proc -b /sys -b /data -b /storage -b /dev/pts

    alias python3='DOLLARPROOT_CMD -w /root /usr/bin/python3'
    alias python=python3
    alias pip3='DOLLARPROOT_CMD -w /root /usr/bin/pip3'
    alias pip=pip3
    alias apt='DOLLARPROOT_CMD -w /root /usr/bin/apt'
    alias apt-get='DOLLARPROOT_CMD -w /root /usr/bin/apt-get'
    alias cmus='DOLLARPROOT_CMD -w /root /usr/bin/cmus'
    alias bash='DOLLARPROOT_CMD -w /root /bin/bash --login'
    alias tar='DOLLARPROOT_CMD -w /root /usr/bin/tar'
    alias zstd='DOLLARPROOT_CMD -w /root /usr/bin/zstd'
    alias 7z='DOLLARPROOT_CMD -w /root /usr/bin/7z'
    alias unzip='DOLLARPROOT_CMD -w /root /usr/bin/unzip'
    alias unrar='DOLLARPROOT_CMD -w /root /usr/bin/unrar'
    alias nano='DOLLARPROOT_CMD -w /root /usr/bin/nano'
    alias nvim='DOLLARPROOT_CMD -w /root /usr/bin/nvim'

    echo ""
    echo "  Ubuntu 24.04 ARM64 listo via PRoot+linker64"
    echo "  Comandos: python3, apt, cmus, tar, nano, etc."
else
    echo ""
    echo "  PRoot/Ubuntu no instalado. Usa: bootstrap proot install"
fi

# ============================================================================
echo ""
echo "  Terminal Master Hub v1.4.1 - by Michael Antonio Rodriguez Condega"
echo "  GitHub: MichaelARC-NI | Telegram: t.me/Michael_Antonio_Rodriguez"
echo "  Email: androidmovil@proton.me | FB: facebook.com/share/1D1pfVdbXE"
echo "  Escribe 'help' para comandos disponibles"
echo ""
""".trimIndent()
                .replace("DOLLAR", "\$")

            File(prefix, "home/.bashrc").writeText(bashrcContent + "\n")

            val initContent = """# Terminal Master Hub - Init configuration
PREFIX=$prefixPath
HOME=$homePath
PATH=/system/bin:/system/xbin
TMPDIR=$prefixPath/tmp
LANG=en_US.UTF-8
LC_ALL=C
"""
            File(prefix, "etc/environment.conf").writeText(initContent)

            onProgress?.invoke("Verificando...", 90)

            val ubuntuInstalled = try {
                val pm = ProotManager(context)
                pm.isUbuntuInstalled()
            } catch (e: Exception) { false }

            onProgress?.invoke("Completado!", 100)
            BootstrapStatus(
                isInstalled = true,
                prefixPath = prefixPath,
                packages = if (ubuntuInstalled) REQUIRED_PACKAGES else emptyList(),
                pythonPackagesInstalled = 0,
                totalSize = getDirSize(prefix),
                message = "Entorno listo. Usa 'bootstrap proot install' para activar Ubuntu."
            )
        } catch (e: Exception) {
            BootstrapStatus(message = "Error: ${e.message}")
        }
    }

    fun getInitConfig(): String = buildString {
        val p = getPrefixDir().absolutePath
        val h = "$p/$HOME_DIR"
        appendLine("# Terminal Master Hub - Init config v1.4.1")
        appendLine("PREFIX=$p")
        appendLine("HOME=$h")
        appendLine("PATH=/system/bin:/system/xbin")
        appendLine("TMPDIR=$p/tmp")
        appendLine("LANG=en_US.UTF-8")
        appendLine("LC_ALL=C")
        appendLine("")
        appendLine("# Usa 'mode ubuntu' para activar PRoot/Ubuntu")
    }

    suspend fun executeInBootstrap(command: String, useProot: Boolean = false): String = withContext(Dispatchers.IO) {
        val p = getPrefixDir().absolutePath
        val h = "$p/$HOME_DIR"

        if (useProot) {
            try {
                val pm = ProotManager(context)
                if (pm.isProotAvailable() && pm.isUbuntuInstalled()) {
                    val result = pm.executeInProot(command, h)
                    return@withContext result
                }
            } catch (_: Exception) {}
        }

        try {
            val envPrefix = "env PREFIX=$p HOME=$h PATH=/system/bin:/system/xbin LANG=en_US.UTF-8 LC_ALL=C"
            val resolvedCmd = "$envPrefix $command"
            val pb = ProcessBuilder("sh", "-c", resolvedCmd)
            pb.environment()["PREFIX"] = p
            pb.environment()["HOME"] = h
            pb.environment()["PATH"] = "/system/bin:/system/xbin"
            pb.environment()["LANG"] = "en_US.UTF-8"
            pb.environment()["LC_ALL"] = "C"
            pb.directory(File(h))
            pb.redirectErrorStream(true)
            val proc = pb.start()
            proc.inputStream.bufferedReader().readText().trim()
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    suspend fun executeWithLinker64(binaryPath: String, args: String = ""): String = withContext(Dispatchers.IO) {
        try {
            val file = File(binaryPath)
            if (!file.exists()) return@withContext "Binario no encontrado: $binaryPath"
            val linker64 = "/system/bin/linker64"
            if (!File(linker64).exists()) return@withContext "linker64 no disponible en este dispositivo"
            val cmd = "$linker64 $binaryPath $args"
            val pb = ProcessBuilder("sh", "-c", cmd)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            proc.inputStream.bufferedReader().readText().trim()
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    fun uninstall(): Boolean = getPrefixDir().let {
        if (it.exists()) it.deleteRecursively() else true
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
