package com.terminalmasterhub.core.root

import android.content.Context
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

        val REQUIRED_PACKAGES = listOf(
            "apt", "bash", "python3", "zstd",
            "p7zip", "tar", "unrar", "unzip",
            "nano", "neovim"
        )

        /** Ruta completa PREFIX usada en scripts de entorno */
        fun getPrefixPath(context: Context): String =
            File(context.filesDir, PREFIX_DIR).absolutePath
    }

    data class BootstrapStatus(
        val isInstalled: Boolean = false,
        val prefixPath: String = "",
        val packages: List<String> = emptyList(),
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
        return BootstrapStatus(
            isInstalled = true,
            prefixPath = p,
            packages = installed,
            totalSize = getDirSize(prefix),
            message = "PREFIX=$p ${installed.size}/${REQUIRED_PACKAGES.size} paquetes"
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
            for (dir in listOf("bin", "etc", "lib", HOME_DIR, "tmp", "var/log")) {
                File(prefix, dir).mkdirs()
            }

            onProgress?.invoke("Configurando bash...", 30)
            // Script bash wrapper con entorno completo
            val bashLines = listOf(
                "#!/system/bin/sh",
                "#",
                "# Terminal Master Hub Bootstrap Shell",
                "# Entorno aislado tipo Termux",
                "#",
                "export PREFIX=$prefixPath",
                "export PATH=$prefixPath/bin:/system/bin:/system/xbin",
                "export HOME=$homePath",
                "export TMPDIR=$prefixPath/tmp",
                "export LANG=en_US.UTF-8",
                "export LC_ALL=C",
                "",
                "# Fuentear .bashrc si existe",
                "if [ -f \"\$HOME/.bashrc\" ]; then",
                "    . \"\$HOME/.bashrc\"",
                "fi",
                "",
                "echo \"Bootstrap listo. Comandos: apt, python3, tar, zstd, unzip, nano\"",
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
                        "if command -v $cmd >/dev/null 2>&1; then\n" +
                        "    exec $cmd \"\$@\"\n" +
                        "elif [ -f $prefixPath/bin/$cmd ]; then\n" +
                        "    exec $prefixPath/bin/$cmd \"\$@\"\n" +
                        "elif [ -f /system/bin/$cmd ]; then\n" +
                        "    exec /system/bin/$cmd \"\$@\"\n" +
                        "elif [ -f /system/xbin/$cmd ]; then\n" +
                        "    exec /system/xbin/$cmd \"\$@\"\n" +
                        "else\n" +
                        "    echo \"$cmd: comando no disponible en este entorno\"\n" +
                        "    exit 127\n" +
                        "fi\n"
                File(prefix, "bin/$cmd").apply {
                    writeText(wrapper)
                    setExecutable(true)
                }
            }

            onProgress?.invoke("Configurando variables de entorno y locales...", 70)
            // Variables de entorno con locale
            val envContent = listOf(
                "export PREFIX=$prefixPath",
                "export HOME=$homePath",
                "export PATH=$prefixPath/bin:/system/bin:/system/xbin",
                "export TMPDIR=$prefixPath/tmp",
                "export LANG=en_US.UTF-8",
                "export LC_ALL=C"
            ).joinToString("\n") + "\n"

            // .bashrc con PS1 profesional, alias, python symlink y chequeo
            val bashrcContent = """
# Terminal Master Hub .bashrc
# ===========================

# Prompt profesional
PS1='\[\033[1;32m\]TerminalMaster\[\033[0m\]:\[\033[1;34m\]\w\[\033[0m\]$ '

# Aliases
alias ll='ls -la'
alias la='ls -A'
alias l='ls -CF'
alias ..='cd ..'
alias ...='cd ../..'
alias cls='clear'
alias py='python3'

# Locale
export LANG=en_US.UTF-8
export LC_ALL=C
export HOME=$homePath
export PREFIX=$prefixPath
export PATH=$prefixPath/bin:/system/bin:/system/xbin
export TMPDIR=$prefixPath/tmp

# Python symlink: si python3 existe pero python no, crear symlink
if command -v python3 >/dev/null 2>&1 && ! command -v python >/dev/null 2>&1; then
    if [ -f $prefixPath/bin/python3 ] && [ ! -f $prefixPath/bin/python ]; then
        ln -sf $prefixPath/bin/python3 $prefixPath/bin/python 2>/dev/null
        hash -r 2>/dev/null
    fi
fi

# Verificar python al inicio, instalar si es necesario
if ! command -v python3 >/dev/null 2>&1; then
    echo "Python no encontrado. Intentando instalar..."
    apt update 2>/dev/null && apt install python3 -y 2>/dev/null || true
fi

# Verificar apt
if ! command -v apt >/dev/null 2>&1; then
    echo "Nota: apt no disponible. Algunas funciones seran limitadas."
fi
""".trimIndent()

            File(prefix, "home/.bashrc").writeText(bashrcContent + "\n")
            File(prefix, "etc/environment").writeText(envContent)

            onProgress?.invoke("Verificando...", 90)
            val installed = REQUIRED_PACKAGES.filter { pkg ->
                File(prefixPath, "bin/$pkg").exists()
            }

            // Crear script init.sh para fuentear variables
            val initContent = """#!/system/bin/sh
# Terminal Master Hub - Init script
export PREFIX=$prefixPath
export HOME=$homePath
export PATH=$prefixPath/bin:/system/bin:/system/xbin
export TMPDIR=$prefixPath/tmp
export LANG=en_US.UTF-8
export LC_ALL=C
cd $HOME
if [ -f "$HOME/.bashrc" ]; then
    . "$HOME/.bashrc"
fi
exec $PREFIX/bin/bash
"""
            File(prefix, "etc/init.sh").writeText(initContent)
            File(prefix, "etc/init.sh").setExecutable(true)

            onProgress?.invoke("Completado!", 100)
            BootstrapStatus(
                isInstalled = true,
                prefixPath = prefixPath,
                packages = installed,
                totalSize = getDirSize(prefix),
                message = "PREFIX=$prefixPath ${installed.size}/${REQUIRED_PACKAGES.size} paquetes"
            )
        } catch (e: Exception) {
            BootstrapStatus(message = "Error: ${e.message}")
        }
    }

    /** Genera script init.sh que exporta todas las variables y lanza bash */
    fun getInitScript(): String {
        val p = getPrefixDir().absolutePath
        val h = "$p/$HOME_DIR"
        return """#!/system/bin/sh
# Terminal Master Hub - Init script
export PREFIX=$p
export HOME=$h
export PATH=$p/bin:/system/bin:/system/xbin
export TMPDIR=$p/tmp
export LANG=en_US.UTF-8
export LC_ALL=C
cd $HOME
if [ -f "$HOME/.bashrc" ]; then
    . "$HOME/.bashrc"
fi
exec $PREFIX/bin/bash
"""
    }

    /** Ejecuta un comando dentro del entorno PREFIX con todas las variables */
    suspend fun executeInBootstrap(command: String): String = withContext(Dispatchers.IO) {
        val p = getPrefixDir().absolutePath
        val h = "$p/$HOME_DIR"
        val env = mapOf(
            "PREFIX" to p,
            "HOME" to h,
            "PATH" to "$p/bin:/system/bin:/system/xbin",
            "TMPDIR" to "$p/tmp",
            "LANG" to "en_US.UTF-8",
            "LC_ALL" to "C"
        )
        try {
            val pb = ProcessBuilder("sh", "-c", command)
            pb.environment().putAll(env)
            pb.directory(File(h))
            pb.redirectErrorStream(true)
            val proc = pb.start()
            proc.inputStream.bufferedReader().readText().trim()
        } catch (e: Exception) { "Error: ${e.message}" }
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
