package com.terminalmasterhub.core.root

import android.content.Context
import com.terminalmasterhub.core.proot.ProotManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class BootstrapManager(private val context: Context) {

    companion object {
        /** Directorio PREFIX: apunta a /data/data/com.terminalmasterhub/files/usr
         *  Los binarios se instalan en $PREFIX/bin/
         *  PREFIX y PATH se configuran como variables de entorno para que
         *  apt, python, tar y las herramientas funcionen sin root. */
        const val PREFIX_DIR = "usr"
        const val HOME_DIR = "home"

        /** Paquetes del sistema (wrappers) que se instalan en $PREFIX/bin/ */
        val REQUIRED_PACKAGES = listOf(
            "apt", "bash", "python3", "zstd",
            "p7zip", "tar", "unrar", "unzip",
            "nano", "neovim", "cmus"
        )

        /** Paquetes Python (pip) instalables en $PREFIX/lib/python3/site-packages/ */
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

        /** Ruta completa PREFIX usada en scripts de entorno */
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
        return prefix.exists() && File(prefix, "bin/bash").exists()
    }

    fun getStatus(): BootstrapStatus {
        val prefix = getPrefixDir()
        val p = prefix.absolutePath
        if (!isInstalled()) {
            return BootstrapStatus(prefixPath = p, message = "No instalado")
        }
        val installed = REQUIRED_PACKAGES.filter { pkg ->
            File(prefix, "bin/$pkg").exists()
        }
        // Contar módulos Python instalados
        val pySitePkgs = File(prefix, "lib/python3/site-packages")
        val pyCount = if (pySitePkgs.exists()) {
            pySitePkgs.listFiles()?.count { it.isDirectory || it.name.endsWith(".py") || it.name.endsWith(".dist-info") } ?: 0
        } else 0
        return BootstrapStatus(
            isInstalled = true,
            prefixPath = p,
            packages = installed,
            pythonPackagesInstalled = pyCount,
            totalSize = getDirSize(prefix),
            message = "PREFIX=$p ${installed.size}/${REQUIRED_PACKAGES.size} paquetes | Python: $pyCount modulos"
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
            for (dir in listOf("bin", "etc", "lib", "lib/python3", "lib/python3/site-packages",
                              HOME_DIR, "home/.config/cmus", "tmp", "var/log")) {
                File(prefix, dir).mkdirs()
            }

            onProgress?.invoke("Configurando bash...", 30)
            // Script bash wrapper con entorno completo
            val bashLines = listOf(
                "#!/system/bin/sh",
                "#",
                "# Terminal Master Hub Bootstrap Shell",
                "# Entorno aislado tipo Termux",
                "# Incluye: cmus, Python con pip, herramientas de compresion",
                "#",
                "export PREFIX=$prefixPath",
                "export PATH=$prefixPath/bin:/system/bin:/system/xbin",
                "export HOME=$homePath",
                "export TMPDIR=$prefixPath/tmp",
                "export LANG=en_US.UTF-8",
                "export LC_ALL=C",
                "export PYTHONPATH=$prefixPath/lib/python3/site-packages:\${PYTHONPATH:-}",
                "",
                "# Fuentear .bashrc si existe",
                "if [ -f \"\$HOME/.bashrc\" ]; then",
                "    . \"\$HOME/.bashrc\"",
                "fi",
                "",
                "echo \"Bootstrap listo. Comandos: apt, python3, pip, cmus, tar, zstd, unzip, nano\"",
                "while true; do",
                "    printf \"\\033[1;32mTerminalMaster\\033[0m:\\033[1;34m\\w\\033[0m\\$ \"",
                "    read cmd_input",
                "    case \"\${cmd_input}\" in",
                "        exit|quit) break;;",
                "        *) eval \"\${cmd_input}\" 2>/dev/null || echo \"Comando no disponible en bootstrap\";;",
                "    esac",
                "done"
            )
            File(prefix, "bin/bash").writeText(bashLines.joinToString("\n") + "\n")
            File(prefix, "bin/bash").setExecutable(true)
            File(prefix, "bin/sh").writeText(bashLines.joinToString("\n") + "\n")
            File(prefix, "bin/sh").setExecutable(true)

            onProgress?.invoke("Creando wrappers de paquetes en \$PREFIX/bin...", 50)
            for (cmd in REQUIRED_PACKAGES) {
                val wrapper = "#!/system/bin/sh\n" +
                        "# Wrapper for $cmd - PREFIX=$prefixPath\n" +
                        "export PREFIX=$prefixPath\n" +
                        "export HOME=$homePath\n" +
                        "export PATH=$prefixPath/bin:/system/bin:/system/xbin\n" +
                        "export LANG=en_US.UTF-8\n" +
                        "export LC_ALL=C\n" +
                        "export PYTHONPATH=$prefixPath/lib/python3/site-packages:\${PYTHONPATH:-}\n" +
                        "if command -v $cmd >/dev/null 2>&1; then\n" +
                        "    exec $cmd \"\$@\"\n" +
                        "elif [ -f \"$prefixPath/bin/$cmd\" ]; then\n" +
                        "    exec $prefixPath/bin/$cmd \"\$@\"\n" +
                        "else\n" +
                        "    echo \"$cmd: command not found (wrapper)\"\n" +
                        "    exit 127\n" +
                        "fi\n"
                File(prefix, "bin/$cmd").apply {
                    writeText(wrapper)
                    setExecutable(true)
                }
            }

            // --- Configuración de cmus ---
            onProgress?.invoke("Configurando cmus...", 55)
            val cmusConfigFile = File(prefix, "home/.config/cmus/autosave")
            cmusConfigFile.writeText(
                "# cmus autosave - Terminal Master Hub\n" +
                "# https://github.com/MichaelARC-NI/TerminalMasterHub\n" +
                "set status_display=yes\n" +
                "set repeat=false\n" +
                "set shuffle=false\n" +
                "set softvol=true\n" +
                "set volume_left=50\n" +
                "set volume_right=50\n"
            )
            // Crear dir para playlists
            File(prefix, "home/.cmus").mkdirs()

            // --- Configuración de Python site-packages ---
            onProgress?.invoke("Configurando entorno Python...", 56)
            val pythonSitePackages = File(prefix, "lib/python3/site-packages")

            // Crear requirements.txt con todos los paquetes Python
            val requirementsContent = PYTHON_PACKAGES.joinToString("\n")
            File(prefix, "etc/requirements.txt").writeText(requirementsContent + "\n")

            // Intentar instalar paquetes Python con pip si está disponible
            onProgress?.invoke("Instalando paquetes Python con pip...", 58)
            try {
                val pipTarget = pythonSitePackages.absolutePath
                // Usar sh para evitar noexec en Android 14+
                val pipCmd = "sh $prefixPath/bin/pip3 install --target=\"$pipTarget\" -r \"$prefixPath/etc/requirements.txt\" 2>&1 || " +
                        "sh $prefixPath/bin/python3 -m pip install --target=\"$pipTarget\" -r \"$prefixPath/etc/requirements.txt\" 2>&1 || " +
                        "echo '[pip] No disponible - instala Python3 con apt update && apt install python3 python3-pip'"
                val pb = ProcessBuilder("sh", "-c", pipCmd)
                pb.environment().putAll(mapOf(
                    "PREFIX" to prefixPath,
                    "HOME" to homePath,
                    "PATH" to "$prefixPath/bin:/system/bin:/system/xbin",
                    "TMPDIR" to "$prefixPath/tmp",
                    "PYTHONPATH" to pipTarget
                ))
                pb.redirectErrorStream(true)
                val pipProcess = pb.start()
                val pipOutput = pipProcess.inputStream.bufferedReader().readText()
                pipProcess.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: Exception) {
                // Ignorar errores de pip - los paquetes Python son opcionales
            }

            // Contar módulos instalados
            val pyCount = if (pythonSitePackages.exists()) {
                pythonSitePackages.listFiles()?.count {
                    it.isDirectory || it.name.endsWith(".dist-info")
                } ?: 0
            } else 0

            onProgress?.invoke("Configurando variables de entorno y locales...", 70)
            // Variables de entorno completa
            val envContent = listOf(
                "export PREFIX=$prefixPath",
                "export HOME=$homePath",
                "export PATH=$prefixPath/bin:/system/bin:/system/xbin",
                "export TMPDIR=$prefixPath/tmp",
                "export LANG=en_US.UTF-8",
                "export LC_ALL=C",
                "export PYTHONPATH=$prefixPath/lib/python3/site-packages",
                "export CMUS_HOME=$homePath/.config/cmus"
            ).joinToString("\n") + "\n"

            // .bashrc completo con PS1, alias, cmus, python, pip
            onProgress?.invoke("Generando .bashrc...", 75)
            val bashrcContent = """
# ============================================================================
# Terminal Master Hub .bashrc
# Version: 1.3.4
# ============================================================================

# Prompt profesional con colores
PS1='\[\033[1;32m\]TerminalMaster\[\033[0m\]:\[\033[1;34m\]\w\[\033[0m\]\$ '

# ============================================================================
# ALIASES
# ============================================================================
alias ll='ls -la --color=auto'
alias la='ls -A --color=auto'
alias l='ls -CF --color=auto'
alias ..='cd ..'
alias ...='cd ../..'
alias cls='clear'
alias py='python3 -i'
alias py3='python3'
alias pip='pip3 2>/dev/null || python3 -m pip 2>/dev/null || echo "pip no disponible"'
alias pip3='pip3 2>/dev/null || python3 -m pip 2>/dev/null || echo "pip3 no disponible"'
alias cmus='if command -v cmus >/dev/null 2>&1; then cmus 2>/dev/null; else echo "cmus no instalado - usa: apt update && apt install cmus"; fi'
alias music='cmus'
alias python-packages='pip3 list --format=columns 2>/dev/null || echo "pip no disponible"'
alias py-install='pip3 install --user'
alias py-list='pip3 list --format=columns'
alias py-freeze='pip3 freeze'

# ============================================================================
# VARIABLES DE ENTORNO
# ============================================================================
export LANG=en_US.UTF-8
export LC_ALL=C
export HOME=$homePath
export PREFIX=$prefixPath
export PATH=$prefixPath/bin:/system/bin:/system/xbin
export TMPDIR=$prefixPath/tmp
export PYTHONPATH=$prefixPath/lib/python3/site-packages:${'$'}{PYTHONPATH:-}
export CMUS_HOME=${'$'}HOME/.config/cmus

# ============================================================================
# PYTHON SETUP — Symlink + site-packages check
# ============================================================================

# Crear symlink python -> python3 si no existe
if command -v python3 >/dev/null 2>&1 && ! command -v python >/dev/null 2>&1; then
    if [ -f ${prefixPath}/bin/python3 ] && [ ! -f ${prefixPath}/bin/python ]; then
        ln -sf ${prefixPath}/bin/python3 ${prefixPath}/bin/python 2>/dev/null
        hash -r 2>/dev/null
    fi
fi

# Contar módulos Python instalados en site-packages
if [ -d "${prefixPath}/lib/python3/site-packages" ]; then
    PKG_COUNT=${'$'}(ls -d "${prefixPath}/lib/python3/site-packages/"*/ 2>/dev/null | wc -l)
    if [ "${'$'}PKG_COUNT" -gt 0 ]; then
        echo "  Python: ${'$'}PKG_COUNT paquetes instalados en site-packages"
    fi
fi

# Verificar si pip está disponible
if command -v pip3 >/dev/null 2>&1; then
    echo "  pip3 listo — usa 'pip install <paquete>' para instalar mas"
fi

# ============================================================================
# CMUS SETUP
# ============================================================================
if command -v cmus >/dev/null 2>&1; then
    echo "  cmus disponible — usa 'music' o 'cmus' para el reproductor"
else
    echo "  cmus no disponible — instalalo con: apt update && apt install cmus"
fi

# ============================================================================
# PYTHON AUTO-INSTALL
# ============================================================================
if ! command -v python3 >/dev/null 2>&1; then
    echo "  Python no encontrado. Intentando instalar..."
    apt update 2>/dev/null && apt install python3 python3-pip -y 2>/dev/null || true
fi

# ============================================================================
# APT CHECK
# ============================================================================
if ! command -v apt >/dev/null 2>&1; then
    echo "  Nota: apt no disponible. Instala Termux para acceso completo."
fi

# ============================================================================
# BIENVENIDA
# ============================================================================
echo ""
echo "  Terminal Master Hub v1.3.5 — by Michael Antonio Rodriguez Condega"
echo "  GitHub: MichaelARC-NI | Telegram: t.me/Michael_Antonio_Rodriguez"
echo "  Escribe 'help' para comandos disponibles"
echo ""
""".trimIndent()

            File(prefix, "home/.bashrc").writeText(bashrcContent + "\n")
            File(prefix, "etc/environment").writeText(envContent)

            onProgress?.invoke("Verificando...", 90)
            val installed = REQUIRED_PACKAGES.filter { pkg ->
                File(prefixPath, "bin/$pkg").exists()
            }

            // Crear script init.sh para fuentear variables
            val initContent = """#!/system/bin/sh
# Terminal Master Hub - Init script v1.3.5
export PREFIX=$prefixPath
export HOME=$homePath
export PATH=$prefixPath/bin:/system/bin:/system/xbin
export TMPDIR=$prefixPath/tmp
export PYTHONPATH=$prefixPath/lib/python3/site-packages:${'$'}PYTHONPATH
export LANG=en_US.UTF-8
export LC_ALL=C
cd ${'$'}HOME
if [ -f "${'$'}HOME/.bashrc" ]; then
    . "${'$'}HOME/.bashrc"
fi
exec ${'$'}PREFIX/bin/bash
"""
            File(prefix, "etc/init.sh").writeText(initContent)
            File(prefix, "etc/init.sh").setExecutable(true)

            onProgress?.invoke("Completado!", 100)
            BootstrapStatus(
                isInstalled = true,
                prefixPath = prefixPath,
                packages = installed,
                pythonPackagesInstalled = pyCount,
                totalSize = getDirSize(prefix),
                message = "PREFIX=$prefixPath ${installed.size}/${REQUIRED_PACKAGES.size} paquetes | Python: ${pyCount} modulos"
            )
        } catch (e: Exception) {
            BootstrapStatus(message = "Error: ${e.message}")
        }
    }

    /** Genera script init.sh que exporta todas las variables y lanza bash */
    fun getInitScript(): String {
        val p = getPrefixDir().absolutePath
        val h = "$p/$HOME_DIR"
        val py = "$p/lib/python3/site-packages"
        return """#!/system/bin/sh
# Terminal Master Hub - Init script v1.3.5
export PREFIX=$p
export HOME=$h
export PATH=$p/bin:/system/bin:/system/xbin
export TMPDIR=$p/tmp
export PYTHONPATH=$py:${'$'}PYTHONPATH
export LANG=en_US.UTF-8
export LC_ALL=C
cd ${'$'}HOME
if [ -f "${'$'}HOME/.bashrc" ]; then
    . "${'$'}HOME/.bashrc"
fi
exec ${'$'}PREFIX/bin/bash
"""
    }

    /** Ejecuta un comando dentro del entorno PREFIX con todas las variables */
    suspend fun executeInBootstrap(command: String, useProot: Boolean = false): String = withContext(Dispatchers.IO) {
        val p = getPrefixDir().absolutePath
        val h = "$p/$HOME_DIR"
        val py = "$p/lib/python3/site-packages"

        // Intentar usar PRoot/Ubuntu si esta disponible y se solicita
        if (useProot) {
            try {
                val pm = ProotManager(context)
                if (pm.isProotAvailable() && pm.isUbuntuInstalled()) {
                    val result = pm.executeInProot(command, h)
                    return result
                }
            } catch (_: Exception) {}
        }

        val env = mapOf(
            "PREFIX" to p,
            "HOME" to h,
            "PATH" to "$p/bin:/system/bin:/system/xbin",
            "TMPDIR" to "$p/tmp",
            "LANG" to "en_US.UTF-8",
            "LC_ALL" to "C",
            "PYTHONPATH" to py
        )
        try {
            // En Android 14+, los archivos en /data/data/ no pueden ejecutarse
            // directamente debido a noexec. Usamos 'sh' para ejecutar wrappers.
            val resolvedCmd = resolveCommand(command, p)
            val pb = ProcessBuilder("sh", "-c", resolvedCmd)
            pb.environment().putAll(env)
            pb.directory(File(h))
            pb.redirectErrorStream(true)
            val proc = pb.start()
            proc.inputStream.bufferedReader().readText().trim()
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    /**
     * Resuelve un comando para manejar restricciones noexec en Android 14+.
     * Si el comando es un wrapper en \$PREFIX/bin/, lo ejecuta via 'sh'.
     */
    private fun resolveCommand(cmd: String, prefixPath: String): String {
        val firstWord = cmd.split("\s+".toRegex()).firstOrNull() ?: return cmd
        val wrapper = File("$prefixPath/bin/$firstWord")
        if (wrapper.exists() && !wrapper.canExecute()) {
            // Wrapper existe pero no es ejecutable -> ejecutar via sh
            return cmd.replaceFirst(firstWord, "sh ${wrapper.absolutePath}")
        }
        return cmd
    }

    fun uninstall(): Boolean = getPrefixDir().let { if (it.exists()) it.deleteRecursively() else true }

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
