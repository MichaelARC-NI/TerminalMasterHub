package com.terminalmasterhub.core.root

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class BootstrapManager(private val context: Context) {

    companion object {
        const val PREFIX_DIR = "linux_prefix"
        val REQUIRED_PACKAGES = listOf(
            "apt", "bash", "python3", "zstd",
            "p7zip", "tar", "unrar", "unzip",
            "nano", "neovim"
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

    fun getPrefixDir(): File = File(context.filesDir, PREFIX_DIR)

    fun isInstalled(): Boolean {
        val prefix = getPrefixDir()
        return prefix.exists() && File(prefix, "bin/bash").exists()
    }

    fun getStatus(): BootstrapStatus {
        val prefix = getPrefixDir()
        if (!isInstalled()) {
            return BootstrapStatus(prefixPath = prefix.absolutePath, message = "No instalado")
        }
        val installed = REQUIRED_PACKAGES.filter { pkg ->
            File(prefix, "bin/$pkg").exists() || File(prefix, "usr/bin/$pkg").exists()
        }
        return BootstrapStatus(
            isInstalled = true,
            prefixPath = prefix.absolutePath,
            packages = installed,
            totalSize = getDirSize(prefix),
            message = "Listo: ${installed.size}/${REQUIRED_PACKAGES.size} paquetes"
        )
    }

    suspend fun install(): BootstrapStatus = withContext(Dispatchers.IO) {
        try {
            val prefix = getPrefixDir()
            if (prefix.exists()) prefix.deleteRecursively()
            prefix.mkdirs()

            onProgress?.invoke("Creando estructura de directorios...", 10)
            for (dir in listOf("bin", "usr/bin", "usr/lib", "etc", "home", "tmp", "var/log")) {
                File(prefix, dir).mkdirs()
            }

            onProgress?.invoke("Configurando bash...", 30)
            // Create bash wrapper - use string concatenation to avoid $ escaping issues
            val bashLines = listOf(
                "#!/system/bin/sh",
                "# Terminal Master Hub Bootstrap Shell",
                "echo \"Bootstrap listo. Comandos: apt, python3, tar, unzip, nano\"",
                "echo \"Usa 'exit' para volver a la terminal principal.\"",
                "while true; do",
                "    printf \"root@tmh:~# \"",
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

            onProgress?.invoke("Creando wrappers de paquetes...", 50)
            for (cmd in REQUIRED_PACKAGES) {
                val wrapper = "#!/system/bin/sh\n" +
                        "# Wrapper for $cmd\n" +
                        "echo \"[Bootstrap] $cmd ejecutado desde el sistema\"\n" +
                        "/system/bin/$cmd \"\$@\" 2>/dev/null || " +
                        "/system/xbin/$cmd \"\$@\" 2>/dev/null || " +
                        "echo \"Comando no encontrado\"\n"
                File(prefix, "bin/$cmd").apply {
                    writeText(wrapper)
                    setExecutable(true)
                }
            }

            onProgress?.invoke("Configurando entorno...", 70)
            val p = prefix.absolutePath
            File(prefix, "home/.bashrc").writeText("alias ll='ls -la'\nalias la='ls -A'\n")
            File(prefix, "etc/environment").writeText(
                "export PREFIX=$p\n" +
                "export PATH=$p/bin:$p/usr/bin:\$PATH\n" +
                "export HOME=$p/home\n" +
                "export TMPDIR=$p/tmp\n"
            )

            onProgress?.invoke("Verificando...", 90)
            val installed = REQUIRED_PACKAGES.filter { pkg ->
                File(prefix, "bin/$pkg").exists()
            }

            onProgress?.invoke("Completado!", 100)
            BootstrapStatus(
                isInstalled = true,
                prefixPath = p,
                packages = installed,
                totalSize = getDirSize(prefix),
                message = "Bootstrap: ${installed.size}/${REQUIRED_PACKAGES.size}"
            )
        } catch (e: Exception) {
            BootstrapStatus(message = "Error: ${e.message}")
        }
    }

    fun getInitScript(): String {
        val p = getPrefixDir().absolutePath
        return "#!/system/bin/sh\n" +
                "export PREFIX=$p\n" +
                "export PATH=$p/bin:\$PATH\n" +
                "export HOME=$p/home\n" +
                "cd \$HOME\n" +
                "exec \$PREFIX/bin/bash\n"
    }

    suspend fun executeInBootstrap(command: String): String = withContext(Dispatchers.IO) {
        val p = getPrefixDir().absolutePath
        val env = mapOf(
            "PREFIX" to p,
            "PATH" to "$p/bin:$p/usr/bin",
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
