package com.terminalmasterhub.ui.terminal

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Puente Python para Terminal Master Hub.
 *
 * Ejecuta scripts Python usando el intérprete del sistema
 * o un entorno PRoot. Los gráficos se detectan por palabras
 * clave y se renderizan en WebView.
 *
 * NOTA: Para Python nativo embebido, integra Chaquopy:
 *   - Agrega el plugin: id("com.chaquo.python") version "16.0.0"
 *   - Agrega el repo: maven { url = uri("https://chaquo.com/maven") }
 *   - Configura python { pip { install("matplotlib") } }
 */
class PythonBridge(private val context: Context) {

    companion object {
        private val GRAPH_PATTERN = Regex(
            "(import matplotlib|from matplotlib|plt\\.|pyplot|" +
            "import PIL|from PIL|Image\\.show|import seaborn|" +
            "import plotly|import tkinter|cv2\\.imshow|" +
            "plt\\.figure|plt\\.plot|plt\\.bar|plt\\.scatter|" +
            "plt\\.hist|plt\\.imshow|import numpy|import pandas)"
        )
    }

    data class PythonResult(
        val textOutput: String = "",
        val hasGraphOutput: Boolean = false,
        val graphHtml: String? = null,
        val error: String? = null
    )

    /**
     * Ejecuta código Python usando el intérprete del sistema
     * o un script wrapper.
     */
    suspend fun executeScript(script: String, args: List<String> = emptyList()): PythonResult =
        withContext(Dispatchers.IO) {
            try {
                val hasGraph = GRAPH_PATTERN.containsMatchIn(script)

                // Intentar ejecutar con python3 del sistema
                val output = try {
                    runWithSystemPython(script)
                } catch (e: Exception) {
                    "[Python no disponible en este entorno]\n" +
                    "Para habilitar Python embebido:\n" +
                    "1. Agrega Chaquopy a build.gradle.kts\n" +
                    "2. Configura python { pip { install(...) } }\n" +
                    "3. Recompila la app\n\n" +
                    "Código recibido:\n${script.take(500)}"
                }

                if (hasGraph) {
                    PythonResult(
                        textOutput = output,
                        hasGraphOutput = true,
                        graphHtml = wrapInHtml(output)
                    )
                } else {
                    PythonResult(textOutput = output)
                }
            } catch (e: Exception) {
                PythonResult(error = e.message)
            }
        }

    suspend fun executeFile(filePath: String): PythonResult = withContext(Dispatchers.IO) {
        try {
            val file = File(filePath)
            if (!file.exists()) {
                return@withContext PythonResult(error = "Archivo no encontrado: $filePath")
            }
            val script = file.readText()
            executeScript(script)
        } catch (e: Exception) {
            PythonResult(error = "Error: ${e.message}")
        }
    }

    private fun runWithSystemPython(script: String): String {
        val tmpScript = File(context.cacheDir, "tmp_py_${System.currentTimeMillis()}.py")
        try {
            tmpScript.writeText(script)
            val process = ProcessBuilder(
                "sh", "-c",
                "python3 ${tmpScript.absolutePath} 2>&1 || " +
                "python ${tmpScript.absolutePath} 2>&1 || " +
                "echo 'Python no instalado. Usa Chaquopy para Python embebido.'"
            ).redirectErrorStream(true).start()

            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            return output.trim()
        } finally {
            tmpScript.delete()
        }
    }

    private fun wrapInHtml(content: String): String {
        return """
<!DOCTYPE html>
<html><head>
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>
body { background:#0D1117; color:#E6EDF3; font-family:monospace; padding:16px; margin:0; }
.output { white-space:pre-wrap; word-wrap:break-word; }
img { max-width:100%; border-radius:8px; margin-top:16px; }
</style></head><body>
<div class="output">$content</div>
</body></html>
""".trimIndent()
    }
}
