package com.terminalmasterhub.core.mitool

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

/**
 * Extractor de archivos .tgz (tar.gz) para ROMs Xiaomi Fastboot.
 *
 * Maneja archivos grandes (ROMs de 4-8GB) con chunks
 * para evitar OOM (Out Of Memory).
 */
class TgzExtractor(private val context: Context) {

    companion object {
        private const val BUFFER_SIZE = 1024 * 1024 // 1MB buffer
        private const val MIN_FREE_SPACE_MB = 1024 // 1GB minimum free space
    }

    data class ExtractionProgress(
        val currentFile: String = "",
        val bytesExtracted: Long = 0,
        val totalBytes: Long = 0,
        val filesExtracted: Int = 0,
        val isComplete: Boolean = false
    )

    /**
     * Extrae un archivo .tgz a un directorio destino.
     *
     * @param tgzFile Archivo .tgz de la ROM
     * @param outputDir Directorio destino para la extracción
     * @param onProgress Callback de progreso
     * @return Directorio con los archivos extraídos, o null si falla
     */
    suspend fun extract(
        tgzFile: File,
        outputDir: File,
        onProgress: ((ExtractionProgress) -> Unit)? = null
    ): File? = withContext(Dispatchers.IO) {
        try {
            if (!tgzFile.exists()) {
                onProgress?.invoke(ExtractionProgress(isComplete = false))
                return@withContext null
            }

            // Verificar espacio en disco
            val freeSpace = outputDir.freeSpace
            val neededSpace = tgzFile.length() * 2 // ROMs comprimidas ~2x factor
            if (freeSpace < neededSpace) {
                onProgress?.invoke(ExtractionProgress(
                    currentFile = "ERROR: Espacio insuficiente. Necesario: ${neededSpace / (1024*1024)}MB, Disponible: ${freeSpace / (1024*1024)}MB"
                ))
                // No retornar null - intentar extraer de todas formas si hay al menos 1GB
                if (freeSpace < (1024 * 1024 * 1024)) {
                    return@withContext null
                }
                onProgress?.invoke(ExtractionProgress(
                    currentFile = "Espacio ajustado, intentando extraer..."
                ))
            }

            if (!outputDir.exists()) outputDir.mkdirs()

            var filesExtracted = 0
            var totalBytesExtracted = 0L
            val totalSize = tgzFile.length()

            onProgress?.invoke(ExtractionProgress(
                currentFile = "Iniciando extracción...",
                totalBytes = totalSize
            ))

            val fis = FileInputStream(tgzFile)
            val bis = BufferedInputStream(fis)
            val gzis = GZIPInputStream(bis)
            val tarIn = TarArchiveInputStream(gzis)

            var entry: TarArchiveEntry? = tarIn.nextTarEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val outputFile = File(outputDir, entry.name)
                    outputFile.parentFile?.mkdirs()

                    val fos = FileOutputStream(outputFile)
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    var fileBytesWritten = 0L

                    while (tarIn.read(buffer).also { bytesRead = it } != -1) {
                        fos.write(buffer, 0, bytesRead)
                        fileBytesWritten += bytesRead
                        totalBytesExtracted += bytesRead

                        onProgress?.invoke(ExtractionProgress(
                            currentFile = entry.name,
                            bytesExtracted = totalBytesExtracted,
                            totalBytes = totalSize,
                            filesExtracted = filesExtracted
                        ))
                    }
                    fos.close()
                    filesExtracted++

                    // Forzar garbage collection periódicamente
                    if (filesExtracted % 10 == 0) {
                        System.gc()
                    }
                }
                entry = tarIn.nextTarEntry
            }

            tarIn.close()
            gzis.close()
            bis.close()
            fis.close()

            onProgress?.invoke(ExtractionProgress(
                bytesExtracted = totalBytesExtracted,
                totalBytes = totalSize,
                filesExtracted = filesExtracted,
                isComplete = true
            ))

            return@withContext outputDir

        } catch (e: Exception) {
            onProgress?.invoke(ExtractionProgress(
                currentFile = "Error: ${e.message}",
                isComplete = false
            ))
            return@withContext null
        }
    }
}
