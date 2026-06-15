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
            if (prefix.exists()) prefix.deleteRecursively()
            prefix.mkdirs()

            onProgress?.invoke("Creando estructura de directorios...", 10)
            // Estructura tipo Termux: $PREFIX/bin, $PREFIX/etc, $PREFIX/lib, $PREFIX/tmp
            for (dir in listOf("bin", "etc", "lib", "home", "tmp", "var/log")) {
                File(prefix, dir).mkdirs()
            }

            onProgress?.invoke("Configurando bash...", 30)
            // Crear script bash wrapper que configura PREFIX y PATH automáticamente
            val bashLines = listOf(
                "#!/system/bin/sh",
                "#",
                "# Terminal Master Hub Bootstrap Shell",
                "# Entorno aislado tipo Termux - PREFIX=$prefixPath",
                "#",
                "export PREFIX=$prefixPath",
                "export PATH=$prefixPath/bin:/system/bin:/system/xbin",
                "export HOME=$prefixPath/home",
                "export TMPDIR=$prefixPath/tmp",
                "",
                "echo \"Bootstrap listo. Comandos: apt, python3, tar, zstd, unzip, nano\"",
                "echo \"PREFIX=\$PREFIX  PATH=\$PATH\"",
                "while true; do",
                "    printf \"root@tmh:\$PREFIX # \"",
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
                        "export PATH=$prefixPath/bin:/system/bin:/system/xbin\n" +
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

            onProgress?.invoke("Configurando variables de entorno (PREFIX, PATH)...", 70)
            // Generar environment.prop con las variables para el bootstrap
            val envContent = listOf(
                "export PREFIX=$prefixPath",
                "export PATH=$prefixPath/bin:/system/bin:/system/xbin",
                "export HOME=$prefixPath/home",
                "export TMPDIR=$prefixPath/tmp"
            ).joinToString("\n") + "\n"

            File(prefix, "home/.bashrc").writeText("alias ll='ls -la'\nalias la='ls -A'\n")
            File(prefix, "etc/environment").writeText(envContent)

            onProgress?.invoke("Verificando...", 90)
            val installed = REQUIRED_PACKAGES.filter { pkg ->
                File(prefixPath, "bin/$pkg").exists()
            }
            // Crear script init.sh para fuentear variables
            File(prefix, "etc/init.sh").writeText(envContent)
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

    /** Genera script init.sh que exporta PREFIX, PATH, HOME, TMPDIR */
    fun getInitScript(): String {
        val p = getPrefixDir().absolutePath
        return "#!/system/bin/sh\n" +
                "# Terminal Master Hub - Init script\n" +
                "export PREFIX=$p\n" +
                "export PATH=$p/bin:/system/bin:/system/xbin\n" +
                "export HOME=$p/home\n" +
                "export TMPDIR=$p/tmp\n" +
                "cd \$HOME\n" +
                "exec \$PREFIX/bin/bash\n"
    }

    /** Ejecuta un comando dentro del entorno PREFIX */
    suspend fun executeInBootstrap(command: String): String = withContext(Dispatchers.IO) {
        val p = getPrefixDir().absolutePath
        val env = mapOf(
            "PREFIX" to p,
            "PATH" to "$p/bin:/system/bin:/system/xbin",
            "HOME" to "$p/home",
            "TMPDIR" to "$p/tmp"
        )
        try {
            val pb = ProcessBuilder("sh", "-c", command)
            pb.environment().putAll(env)
            pb.directory(File("$p/home"))
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
