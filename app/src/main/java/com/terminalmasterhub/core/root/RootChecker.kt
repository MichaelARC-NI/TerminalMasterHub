package com.terminalmasterhub.core.root

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Verificador de acceso root nativo.
 *
 * Realiza múltiples comprobaciones para determinar si
 * el dispositivo tiene acceso root disponible:
 *
 * 1. Existencia de binarios su/daemonsu/su
 * 2. Ejecución de "su -c id" y verificación de UID=0
 * 3. Variables de entorno de Magisk/KernelSU/APatch
 *
 * NOTA: libsu (com.github.topjohnwu.libsu) proporciona
 * una API más robusta. Esta clase es un fallback nativo.
 */
object RootChecker {

    data class RootStatus(
        val hasRoot: Boolean = false,
        val method: RootMethod = RootMethod.NONE,
        val binaries: List<String> = emptyList(),
        val detail: String = ""
    )

    enum class RootMethod(val displayName: String) {
        MAGISK("Magisk"),
        KERNELSU("KernelSU"),
        APATCH("APatch"),
        SUPERSU("SuperSU"),
        LEGACY_SU("su tradicional"),
        NONE("Sin root")
    }

    // Rutas comunes de binarios su
    private val SU_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/system/sbin/su",
        "/sbin/su",
        "/vendor/bin/su",
        "/data/adb/magisk",
        "/data/adb/ksu",
        "/data/adb/apatch",
        "/su/bin/su"
    )

    // Variables de entorno de root managers
    private val ROOT_ENV_VARS = mapOf(
        "MAGISKTMP" to RootMethod.MAGISK,
        "KSU" to RootMethod.KERNELSU,
        "APATCH" to RootMethod.APATCH
    )

    /**
     * Verifica si el dispositivo tiene acceso root.
     * Esta función es segura de llamar desde cualquier hilo.
     */
    suspend fun checkRoot(context: Context): RootStatus = withContext(Dispatchers.IO) {
        val foundBinaries = mutableListOf<String>()
        var method = RootMethod.NONE

        // 1. Buscar binarios su en rutas conocidas
        for (path in SU_PATHS) {
            val file = File(path)
            if (file.exists() && file.canExecute()) {
                foundBinaries.add(path)
                method = when {
                    path.contains("magisk") -> RootMethod.MAGISK
                    path.contains("ksu") -> RootMethod.KERNELSU
                    path.contains("apatch") -> RootMethod.APATCH
                    path.contains("su") && method == RootMethod.NONE -> RootMethod.LEGACY_SU
                    else -> method
                }
            }
        }

        // 2. Detectar por variables de entorno
        val env = System.getenv()
        for ((varName, detectedMethod) in ROOT_ENV_VARS) {
            if (env.containsKey(varName)) {
                method = detectedMethod
                foundBinaries.add("env:$varName=${env[varName]}")
            }
        }

        // 3. Intentar ejecución directa de su
        var hasRoot = method != RootMethod.NONE
        var detail = ""

        if (hasRoot) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                val reader = java.io.BufferedReader(
                    java.io.InputStreamReader(process.inputStream)
                )
                val output = reader.readLine()
                process.waitFor()

                if (output != null && output.contains("uid=0")) {
                    detail = "Root confirmado: $output"
                } else {
                    // su existe pero no da root completo
                    hasRoot = false
                    method = RootMethod.NONE
                    detail = "su encontrado pero no otorga uid=0"
                }
            } catch (e: Exception) {
                hasRoot = false
                method = RootMethod.NONE
                detail = "Error ejecutando su: ${e.message}"
            }
        } else {
            detail = "No se encontraron binarios root"
        }

        RootStatus(
            hasRoot = hasRoot,
            method = method,
            binaries = foundBinaries,
            detail = detail
        )
    }

    /**
     * Verificación rápida sin ejecución de comandos.
     * Útil para UI en tiempo real.
     */
    fun quickCheck(): Boolean {
        for (path in SU_PATHS) {
            val file = File(path)
            if (file.exists()) return true
        }
        return System.getenv().keys.any { it in ROOT_ENV_VARS }
    }
}
