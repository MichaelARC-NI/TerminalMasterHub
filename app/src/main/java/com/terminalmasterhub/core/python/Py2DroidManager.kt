package com.terminalmasterhub.core.python

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream

class Py2DroidManager(private val context: Context) {

    companion object {
        const val CPYTHON_DIR = "cpython"
        const val CPYTHON_ASSET = "cpython/cpython-arm64.tar.xz"
        const val CACERT_ASSET = "cpython/cacert.pem"
        const val HOME_DIR = "cpython-home"
    }

    private val cpythonDir: File get() = File(context.filesDir, CPYTHON_DIR)
    private val homeDir: File get() = File(context.filesDir, HOME_DIR)

    data class PythonStatus(
        val isInstalled: Boolean = false,
        val pythonVersion: String = "",
        val pythonPath: String = "",
        val size: Long = 0L
    )

    var onProgress: ((String, Int) -> Unit)? = null

    fun isInstalled(): Boolean {
        return File(cpythonDir, "prefix/bin/python3.14").exists() &&
               File(cpythonDir, "prefix/lib/libpython3.14.so").exists()
    }

    fun getPythonBin(): File = File(cpythonDir, "prefix/bin/python3.14")

    suspend fun install(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (isInstalled()) {
                onProgress?.invoke("Python ya instalado!", 100)
                return@withContext true
            }
            cpythonDir.mkdirs()
            homeDir.mkdirs()
            onProgress?.invoke("Extrayendo CPython ARM64...", 20)
            try {
                context.assets.open(CPYTHON_ASSET).use { input ->
                    BufferedInputStream(input).use { bis ->
                        XZCompressorInputStream(bis).use { xzis ->
                            TarArchiveInputStream(xzis).use { taris ->
                                var entry = taris.nextTarEntry
                                var count = 0
                                while (entry != null) {
                                    val outputFile = File(cpythonDir, entry.name)
                                    if (entry.isDirectory) {
                                        outputFile.mkdirs()
                                    } else {
                                        outputFile.parentFile?.mkdirs()
                                        FileOutputStream(outputFile).use { fos ->
                                            taris.transferTo(fos)
                                        }
                                    }
                                    count++
                                    if (count % 500 == 0) {
                                        val pct = (count / 25).coerceIn(0, 80)
                                        onProgress?.invoke("Extrayendo CPython... ($count archivos)", pct)
                                    }
                                    entry = taris.nextTarEntry
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                onProgress?.invoke("Error extrayendo CPython: ${e.message}", 0)
                return@withContext false
            }
            try {
                val certDir = File(cpythonDir, "prefix/etc/ssl")
                certDir.mkdirs()
                context.assets.open(CACERT_ASSET).use { input ->
                    File(certDir, "cacert.pem").outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                onProgress?.invoke("cacert.pem no disponible", 80)
            }
            onProgress?.invoke("CPython ARM64 instalado!", 100)
            return@withContext true
        } catch (e: Exception) {
            onProgress?.invoke("Error: ${e.message}", 0)
            return@withContext false
        }
    }

    suspend fun executePython(command: String): String = withContext(Dispatchers.IO) {
        try {
            if (!isInstalled()) return@withContext "Python no instalado. Usa 'python install'"
            val cpythonPrefix = cpythonDir.absolutePath + "/prefix"
            val pyBin = getPythonBin().absolutePath
            val libPath = File(cpythonDir, "prefix/lib").absolutePath
            val homePath = homeDir.absolutePath
            File(homePath, "tmp").mkdirs()

            val env = mapOf(
                "HOME" to homePath,
                "PYTHONHOME" to cpythonPrefix,
                "LD_LIBRARY_PATH" to "$libPath:/system/lib64:/vendor/lib64",
                "SSL_CERT_FILE" to "$cpythonPrefix/etc/ssl/cacert.pem",
                "TMPDIR" to "$homePath/tmp",
                "PIP_ROOT_USER_ACTION" to "ignore"
            )

            val escapedCmd = command.replace("'", "'\\''")
            val cmd = "/system/bin/linker64 $pyBin -c '$escapedCmd'"
            val pb = ProcessBuilder("sh", "-c", cmd)
            pb.environment().putAll(env)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            proc.inputStream.bufferedReader().readText().trim()
        } catch (e: Exception) {
            "Error Python: ${e.message}"
        }
    }

    suspend fun executePythonFile(filePath: String): String = withContext(Dispatchers.IO) {
        try {
            if (!isInstalled()) return@withContext "Python no instalado"
            val cpythonPrefix = cpythonDir.absolutePath + "/prefix"
            val pyBin = getPythonBin().absolutePath
            val libPath = File(cpythonDir, "prefix/lib").absolutePath
            val env = mapOf(
                "PYTHONHOME" to cpythonPrefix,
                "LD_LIBRARY_PATH" to "$libPath:/system/lib64:/vendor/lib64",
                "SSL_CERT_FILE" to "$cpythonPrefix/etc/ssl/cacert.pem",
                "PIP_ROOT_USER_ACTION" to "ignore"
            )
            val cmd = "/system/bin/linker64 $pyBin \"$filePath\""
            val pb = ProcessBuilder("sh", "-c", cmd)
            pb.environment().putAll(env)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            proc.inputStream.bufferedReader().readText().trim()
        } catch (e: Exception) {
            "Error Python: ${e.message}"
        }
    }

    fun getPythonInitCommand(): String? {
        if (!isInstalled()) return null
        val cpythonPrefix = cpythonDir.absolutePath + "/prefix"
        val pyBin = getPythonBin().absolutePath
        val libPath = File(cpythonDir, "prefix/lib").absolutePath
        return "exec env PYTHONHOME=$cpythonPrefix LD_LIBRARY_PATH=$libPath:/system/lib64:/vendor/lib64 SSL_CERT_FILE=$cpythonPrefix/etc/ssl/cacert.pem PIP_ROOT_USER_ACTION=ignore /system/bin/linker64 $pyBin"
    }

    fun getStatus(): PythonStatus {
        if (!isInstalled()) return PythonStatus()
        return PythonStatus(
            isInstalled = true,
            pythonVersion = "3.14",
            pythonPath = getPythonBin().absolutePath,
            size = getDirSize(cpythonDir)
        )
    }

    fun uninstall(): Boolean {
        return if (cpythonDir.exists()) cpythonDir.deleteRecursively() else true
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
