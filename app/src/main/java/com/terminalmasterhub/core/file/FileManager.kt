package com.terminalmasterhub.core.file

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * Gestor centralizado de archivos.
 *
 * Proporciona operaciones de lectura, escritura, navegación
 * y utilidades para todos los módulos de la app.
 */
object FileManager {

    /**
     * Obtiene la ruta base de almacenamiento.
     */
    fun getStoragePath(): String {
        return Environment.getExternalStorageDirectory().absolutePath
    }

    /**
     * Obtiene el directorio de descargas.
     */
    fun getDownloadPath(): String {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
    }

    /**
     * Obtiene una carpeta temporal para extracciones.
     */
    fun getTempDir(context: Context): File {
        // Usar almacenamiento compartido para archivos grandes (ROMs de 8+ GB)
        // El cacheDir de la app esta en /data/ con espacio limitado
        val basePath = File(getStoragePath(), ".TerminalMasterHub/tmp")
        val dir = File(basePath, "terminal_master_hub_tmp")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Busca archivos .tgz en el almacenamiento (para Xiaomi ROMs).
     */
    fun findTgzFiles(rootPath: String = getStoragePath()): List<File> {
        val results = mutableListOf<File>()
        val root = File(rootPath)
        if (!root.exists()) return results

        root.walkTopDown()
            .maxDepth(4)
            .filter { it.name.endsWith(".tgz") && !it.path.contains("/Android/") }
            .forEach { results.add(it) }

        return results
    }

    /**
     * Busca directorios con scripts flash_all.sh (ROMs ya extraídas).
     */
    fun findFlashScriptDirectories(rootPath: String = getStoragePath()): List<File> {
        val results = mutableListOf<File>()
        val root = File(rootPath)
        if (!root.exists()) return results

        root.walkTopDown()
            .maxDepth(4)
            .filter { dir ->
                dir.isDirectory &&
                        !dir.path.contains("/Android/") &&
                        dir.listFiles()?.any { f ->
                            f.name in FLASH_SCRIPTS && File(f.parentFile, "images").exists()
                        } == true
            }
            .forEach { results.add(it) }

        return results
    }

    /**
     * Lee el contenido de un archivo flash_all.sh y extrae comandos fastboot.
     */
    fun parseFlashScript(file: File): List<FastbootCommand> {
        val commands = mutableListOf<FastbootCommand>()
        if (!file.exists()) return commands

        val lines = file.readLines()
        for (line in lines) {
            val trimmed = line.trim()
            // Ignorar comentarios, variables, eco, cd, etc.
            if (trimmed.isEmpty() || trimmed.startsWith("#") ||
                trimmed.startsWith("export") || trimmed.startsWith("cd ") ||
                trimmed.startsWith("echo") || trimmed.startsWith("if ") ||
                trimmed.startsWith("then") || trimmed.startsWith("fi") ||
                trimmed.startsWith("return") || trimmed.startsWith("set -")
            ) continue

            // Extraer comandos fastboot flash/getvar/erase/reboot
            if (trimmed.startsWith("fastboot ")) {
                commands.add(FastbootCommand(trimmed, file.parentFile))
            }
        }
        return commands
    }

    /**
     * Calcula el hash MD5 de un archivo (para verificación .tar.md5).
     */
    fun calculateMd5(file: File): String? {
        return try {
            val digest = MessageDigest.getInstance("MD5")
            val buffer = ByteArray(8192)
            FileInputStream(file).use { input ->
                BufferedInputStream(input).use { bis ->
                    var bytesRead: Int
                    while (bis.read(buffer).also { bytesRead = it } != -1) {
                        digest.update(buffer, 0, bytesRead)
                    }
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extrae la suma MD5 del nombre de un archivo .tar.md5.
     * Formato: nombre_archivo.tar.md5 donde la appends la suma.
     */
    fun extractMd5FromFilename(file: File): String? {
        val name = file.nameWithoutExtension // quita .md5
            .substringBeforeLast(".")
        // El MD5 suele ir al final del nombre separado por guiones
        val parts = name.split("-")
        return if (parts.size >= 2) {
            parts.last().take(32).lowercase()
        } else null
    }

    /**
     * Copia un archivo por partes para manejar archivos grandes.
     */
    fun copyFileInChunks(source: File, dest: File, chunkSize: Int = 1024 * 1024): Boolean {
        return try {
            FileInputStream(source).use { input ->
                BufferedInputStream(input).use { bis ->
                    FileOutputStream(dest).use { output ->
                        BufferedOutputStream(output).use { bos ->
                            val buffer = ByteArray(chunkSize)
                            var bytesRead: Int
                            while (bis.read(buffer).also { bytesRead = it } != -1) {
                                bos.write(buffer, 0, bytesRead)
                                bos.flush()
                            }
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Elimina un directorio recursivamente.
     */
    fun deleteRecursive(file: File): Boolean {
        return if (file.isDirectory) {
            file.listFiles()?.all { deleteRecursive(it) } == true && file.delete()
        } else {
            file.delete()
        }
    }

    /**
     * Obtiene el tamaño legible de un archivo.
     */
    fun getFileSizeString(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
            else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
        }
    }

    data class FastbootCommand(
        val rawCommand: String,
        val workingDir: File
    ) {
        /**
         * Parsea el tipo de operación fastboot.
         */
        val operation: String get() {
            return when {
                rawCommand.contains("flash ") -> "flash"
                rawCommand.contains("getvar ") -> "getvar"
                rawCommand.contains("erase ") -> "erase"
                rawCommand.contains("reboot") -> "reboot"
                rawCommand.contains("boot ") -> "boot"
                rawCommand.contains("oem ") -> "oem"
                else -> "other"
            }
        }

        /**
         * Extrae la partición destino (ej. "boot", "system", "vendor").
         */
        val partition: String? get() {
            val flashMatch = Regex("flash\\s+(\\w+)").find(rawCommand)
            return flashMatch?.groupValues?.get(1)
        }

        /**
         * Extrae la ruta del archivo de imagen.
         */
        val imageFile: File? get() {
            // fastboot flash boot boot.img -> busca "boot.img" o similar
            val parts = rawCommand.split("\\s+".toRegex())
            val lastPart = parts.lastOrNull()
            if (lastPart != null && !lastPart.startsWith("-") &&
                !lastPart.startsWith("{") && lastPart != operation
            ) {
                val imgFile = File(workingDir, "images/$lastPart")
                if (imgFile.exists()) return imgFile
                val imgFile2 = File(workingDir, lastPart)
                if (imgFile2.exists()) return imgFile2
            }
            return null
        }
    }

    private val FLASH_SCRIPTS = setOf(
        "flash_all.sh", "flash_all_lock.sh",
        "flash_all_except_data_storage.sh", "flash_all_except_storage.sh"
    )
}
