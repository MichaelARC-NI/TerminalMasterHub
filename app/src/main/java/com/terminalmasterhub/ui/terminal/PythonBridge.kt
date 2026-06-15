package com.terminalmasterhub.ui.terminal

import android.content.Context
import com.terminalmasterhub.core.root.BootstrapManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Puente Python para Terminal Master Hub v1.3.5.
 *
 * Ejecuta scripts Python usando:
 * 1. El intérprete del bootstrap ($PREFIX/bin/python3) primero
 * 2. El sistema (/system/bin/python3) como fallback
 *
 * Los gráficos se detectan por palabras clave import/miembros de librerías
 * y se renderizan en WebView (PythonGraphActivity).
 *
 * Integración con cmus y paquetes pip:
 * - PYTHONPATH apunta a $PREFIX/lib/python3/site-packages/
 * - Las librerias matplotlib, numpy, pillow, etc. se instalan via pip
 * - Se puede expandir ejecutando: pip install <paquete> en la terminal
 */
class PythonBridge(private val context: Context) {

    companion object {
        /** Patron para detectar scripts que generan salida grafica */
        private val GRAPH_PATTERN = Regex(
            "(import matplotlib|from matplotlib|plt\\.|pyplot|" +
            "import PIL|from PIL|Image\\.show|import seaborn|" +
            "import plotly|import tkinter|cv2\\.imshow|" +
            "plt\\.figure|plt\\.plot|plt\\.bar|plt\\.scatter|" +
            "plt\\.hist|plt\\.imshow|import numpy|import pandas|" +
            "import pillow|from pillow|import imageio|from imageio|" +
            "import pygame|from pygame|turtle\\.|import turtle|" +
            "import rich|from rich|Console\\(\\)|print\\(.*\\\n.*\\\n|" +
            "import networkx|from networkx|nx\\.draw|" +
            "import plotly\\.express|import plotly\\.graph_objs|" +
            "import bokeh|from bokeh|import altair|from altair|" +
            "import folium|from folium|m\\.save|map\\.save|" +
            "import wordcloud|WordCloud|import pillow_avif)"
        )

        /** Detecta si un script usa matplotlib de forma implicita por nombre */
        private val MATPLOTLIB_LIKE = Regex(
            "plt\\.|figure\\(\\)|subplot\\(|imshow\\(|show\\(\\)|" +
            "savefig\\(|plot\\(|bar\\(|scatter\\(|hist\\(|pie\\("
        )
    }

    data class PythonResult(
        val textOutput: String = "",
        val hasGraphOutput: Boolean = false,
        val graphHtml: String? = null,
        val error: String? = null,
        val usedBootstrapPython: Boolean = false
    )

    /**
     * Obtiene la ruta del interprete Python del bootstrap
     * o null si no esta disponible.
     */
    private fun getBootstrapPython(): String? {
        val prefix = File(context.filesDir, BootstrapManager.PREFIX_DIR)
        val candidates = listOf(
            File(prefix, "bin/python3"),
            File(prefix, "bin/python")
        )
        return candidates.firstOrNull { it.exists() }?.absolutePath
    }

    /** Obtiene la ruta del site-packages del bootstrap */
    private fun getBootstrapSitePackages(): String? {
        val prefix = File(context.filesDir, BootstrapManager.PREFIX_DIR)
        val sitePkgs = File(prefix, "lib/python3/site-packages")
        return if (sitePkgs.exists()) sitePkgs.absolutePath else null
    }

    /**
     * Ejecuta codigo Python usando el bootstrap primero,
     * luego fallback al interprete del sistema.
     */
    suspend fun executeScript(script: String, args: List<String> = emptyList()): PythonResult =
        withContext(Dispatchers.IO) {
            try {
                val hasGraph = GRAPH_PATTERN.containsMatchIn(script) ||
                        MATPLOTLIB_LIKE.containsMatchIn(script)

                val bootstrapPython = getBootstrapPython()
                val sitePackages = getBootstrapSitePackages()

                // Intentar ejecutar con python3 del bootstrap
                val (output, usedBootstrap) = if (bootstrapPython != null) {
                    try {
                        runWithBootstrapPython(script, bootstrapPython, sitePackages) to true
                    } catch (e: Exception) {
                        try {
                            runWithSystemPython(script) to false
                        } catch (e2: Exception) {
                            "[Python no disponible]\n" +
                            "Instala python3 con:\n  apt update && apt install python3 python3-pip\n\n" +
                            "Codigo recibido:\n${script.take(500)}" to false
                        }
                    }
                } else {
                    try {
                        runWithSystemPython(script) to false
                    } catch (e: Exception) {
                        "[Python no disponible en este entorno]\n" +
                        "Para habilitar Python embebido:\n" +
                        "1. Instala Termux o bootstrap completo\n" +
                        "2. Ejecuta: apt update && apt install python3\n" +
                        "3. Reintenta\n\n" +
                        "Codigo:\n${script.take(500)}" to false
                    }
                }

                if (hasGraph) {
                    PythonResult(
                        textOutput = output,
                        hasGraphOutput = true,
                        graphHtml = wrapInHtml(output, script),
                        usedBootstrapPython = usedBootstrap
                    )
                } else {
                    PythonResult(
                        textOutput = output,
                        usedBootstrapPython = usedBootstrap
                    )
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

    /**
     * Ejecuta Python usando el interprete del bootstrap con PYTHONPATH
     * configurado para incluir site-packages.
     */
    private fun runWithBootstrapPython(script: String, pythonPath: String, sitePackages: String?): String {
        val tmpScript = File(context.cacheDir, "tmp_py_${System.currentTimeMillis()}.py")
        try {
            tmpScript.writeText(script)
            val env = mutableMapOf(
                "PREFIX" to File(context.filesDir, BootstrapManager.PREFIX_DIR).absolutePath,
                "HOME" to File(context.filesDir, "${BootstrapManager.PREFIX_DIR}/${BootstrapManager.HOME_DIR}").absolutePath,
                "PATH" to "$pythonPath:/system/bin:/system/xbin",
                "TMPDIR" to File(context.cacheDir, "../tmp").absolutePath,
                "LANG" to "en_US.UTF-8",
                "LC_ALL" to "C"
            )
            if (sitePackages != null) {
                env["PYTHONPATH"] = sitePackages
            }

            val cmd = "$pythonPath ${tmpScript.absolutePath} 2>&1"
            val pb = ProcessBuilder("sh", "-c", cmd)
            pb.environment().putAll(env)
            pb.redirectErrorStream(true)
            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            return output.trim()
        } finally {
            tmpScript.delete()
        }
    }

    /**
     * Ejecuta Python usando el interprete del sistema (fallback).
     */
    private fun runWithSystemPython(script: String): String {
        val tmpScript = File(context.cacheDir, "tmp_py_${System.currentTimeMillis()}.py")
        try {
            tmpScript.writeText(script)
            val process = ProcessBuilder(
                "sh", "-c",
                "python3 ${tmpScript.absolutePath} 2>&1 || " +
                "python ${tmpScript.absolutePath} 2>&1 || " +
                "echo 'Python no instalado. Usa: apt install python3'"
            ).redirectErrorStream(true).start()

            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            return output.trim()
        } finally {
            tmpScript.delete()
        }
    }

    /**
     * Envuelve la salida en HTML con soporte para imagenes matplotlib
     * incrustadas como base64.
     */
    private fun wrapInHtml(content: String, sourceScript: String? = null): String {
        val escapedContent = content
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

        return """
<!DOCTYPE html>
<html lang="es">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0">
<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body {
    background: #0D1117;
    color: #E6EDF3;
    font-family: 'Courier New', 'Consolas', monospace;
    padding: 16px;
    line-height: 1.5;
    overflow-x: hidden;
}
.header {
    background: linear-gradient(135deg, #1a1a2e, #16213e);
    padding: 12px 16px;
    border-radius: 8px;
    margin-bottom: 16px;
    border-left: 4px solid #58a6ff;
}
.header h2 {
    color: #58a6ff;
    font-size: 14px;
    font-weight: 600;
}
.header p {
    color: #8b949e;
    font-size: 11px;
    margin-top: 4px;
}
.output {
    background: #161B22;
    padding: 12px 16px;
    border-radius: 6px;
    white-space: pre-wrap;
    word-wrap: break-word;
    font-size: 13px;
    border: 1px solid #30363D;
    min-height: 40px;
}
.output .success { color: #3fb950; }
.output .error { color: #f85149; }
.output .info { color: #58a6ff; }
.output .warning { color: #d29922; }
img {
    max-width: 100%;
    height: auto;
    border-radius: 8px;
    margin-top: 12px;
    border: 1px solid #30363D;
}
.footer {
    margin-top: 16px;
    padding-top: 12px;
    border-top: 1px solid #21262D;
    color: #484f58;
    font-size: 10px;
    text-align: center;
}
</style>
</head>
<body>
<div class="header">
    <h2>🐍 Python Output — Terminal Master Hub</h2>
    <p>Renderizado grafico | ${if (sourceScript != null) "Script ejecutado" else "Salida de consola"}</p>
</div>
<div class="output">$escapedContent</div>
<div class="footer">Terminal Master Hub v1.3.5 — by Michael Antonio Rodriguez Condega</div>
</body>
</html>
""".trimIndent()
    }

    /**
     * Verifica si un script Python deberia ejecutarse en modo grafico.
     * Usado por TerminalFragment para mostrar el dialogo de seleccion.
     */
    fun shouldUseGraphicMode(script: String): Boolean {
        return GRAPH_PATTERN.containsMatchIn(script) ||
                MATPLOTLIB_LIKE.containsMatchIn(script)
    }
}
