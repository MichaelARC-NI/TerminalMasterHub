package com.terminalmasterhub.core.mitool

import com.terminalmasterhub.core.file.FileManager
import java.io.File

/**
 * Parser del script flash_all.sh de Xiaomi.
 *
 * Traduce el script bash de una ROM Fastboot de Xiaomi
 * en comandos fastboot ejecutables por nuestro FastbootClient.
 *
 * Basado en el análisis de MiTool (miflashf.py) y el
 * formato estándar de ROMs Fastboot de Xiaomi.
 */
object MiToolParser {

    data class RomInfo(
        val rootDir: File,
        val flashScripts: List<FlashScript>,
        val images: List<ImageInfo>,
        val imagesDir: File?,
        val isValid: Boolean = false
    )

    data class FlashScript(
        val file: File,
        val type: FlashType,
        val commands: List<FileManager.FastbootCommand> = emptyList()
    )

    enum class FlashType(val description: String) {
        FLASH_ALL("Flash all sin bloquear bootloader"),
        FLASH_ALL_LOCK("Flash all BLOQUEANDO bootloader"),
        FLASH_ALL_EXCEPT_DATA("Flash all excepto datos"),
        FLASH_ALL_EXCEPT_STORAGE("Flash all excepto almacenamiento"),
        UNKNOWN("Desconocido")
    }

    data class ImageInfo(
        val name: String,
        val file: File,
        val size: Long,
        val partition: String,
        val isSparse: Boolean = false
    )

    private val FLASH_SCRIPT_NAMES = mapOf(
        "flash_all.sh" to FlashType.FLASH_ALL,
        "flash_all_lock.sh" to FlashType.FLASH_ALL_LOCK,
        "flash_all_except_data_storage.sh" to FlashType.FLASH_ALL_EXCEPT_DATA,
        "flash_all_except_storage.sh" to FlashType.FLASH_ALL_EXCEPT_STORAGE
    )

    /**
     * Analiza un directorio de ROM extraída.
     */
    fun analyzeRom(romDir: File): RomInfo {
        val imagesDir = File(romDir, "images")

        // Buscar scripts flash
        val flashScripts = mutableListOf<FlashScript>()
        for (file in romDir.listFiles().orEmpty()) {
            FLASH_SCRIPT_NAMES[file.name]?.let { type ->
                val commands = FileManager.parseFlashScript(file)
                flashScripts.add(FlashScript(file, type, commands))
            }
        }

        // Listar imágenes disponibles
        val images = mutableListOf<ImageInfo>()
        if (imagesDir.exists()) {
            for (imgFile in imagesDir.listFiles().orEmpty()) {
                if (imgFile.isFile && imgFile.name.endsWith(".img")) {
                    images.add(ImageInfo(
                        name = imgFile.name,
                        file = imgFile,
                        size = imgFile.length(),
                        partition = imgFile.name.removeSuffix(".img"),
                        isSparse = imgFile.name.contains("sparse")
                    ))
                }
            }
        }

        return RomInfo(
            rootDir = romDir,
            flashScripts = flashScripts,
            images = images,
            imagesDir = imagesDir,
            isValid = flashScripts.isNotEmpty() && imagesDir.exists()
        )
    }

    /**
     * Obtiene el tipo de script recomendado para flasheo.
     * Por defecto, el que no lockea ni borra datos.
     */
    fun getRecommendedScript(romInfo: RomInfo): FlashScript? {
        // Prioridad: flash_all_except_data_storage > flash_all > flash_all_lock
        val priority = listOf(
            FlashType.FLASH_ALL_EXCEPT_DATA,
            FlashType.FLASH_ALL,
            FlashType.FLASH_ALL_EXCEPT_STORAGE,
            FlashType.FLASH_ALL_LOCK
        )
        for (type in priority) {
            romInfo.flashScripts.find { it.type == type }?.let { return it }
        }
        return romInfo.flashScripts.firstOrNull()
    }

    /**
     * Obtiene el nombre de partición correcto para imágenes.
     * Maneja diferencias entre particiones A/B y dinámicas.
     */
    fun getPartitionName(partition: String, slot: String? = null): String {
        // Particiones dinámicas (super)
        val dynamicPartitions = setOf("system", "product", "vendor", "odm", "system_ext")

        return if (slot != null && partition !in dynamicPartitions) {
            "${partition}_$slot"
        } else {
            partition
        }
    }

    /**
     * Verifica si una ROM es válida (tiene las imágenes mínimas).
     */
    fun validateRom(romInfo: RomInfo): Pair<Boolean, String> {
        if (!romInfo.isValid) {
            return false to "No se encontraron scripts flash_all.sh ni directorio de imágenes"
        }

        val requiredImages = setOf("boot", "dtbo", "vbmeta")
        val foundImages = romInfo.images.map { it.name }.toSet()

        val missing = requiredImages - foundImages
        return if (missing.isEmpty()) {
            true to "ROM válida: ${romInfo.flashScripts.size} script(s), ${romInfo.images.size} imagen(es)"
        } else {
            true to "ROM válida (faltan: $missing, pero puede funcionar)"
        }
    }
}
