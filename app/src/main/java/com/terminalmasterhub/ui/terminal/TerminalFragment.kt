package com.terminalmasterhub.ui.terminal

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.terminalmasterhub.R
import com.terminalmasterhub.core.file.FileManager
import com.terminalmasterhub.core.permissions.PermissionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Terminal Linux + Python IDE embebido.
 * Ejecuta comandos shell y scripts Python sin necesidad de root.
 */
class TerminalFragment : Fragment() {

    private lateinit var terminalOutput: TextView
    private lateinit var terminalInput: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnClear: ImageButton
    private lateinit var btnRunPython: ImageButton
    private lateinit var btnFileManager: ImageButton
    private lateinit var scrollView: ScrollView

    private val pythonBridge by lazy { PythonBridge(requireContext()) }
    private val commandHistory = mutableListOf<String>()
    private var historyIndex = -1

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_terminal, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        terminalOutput = view.findViewById(R.id.terminalView)
        terminalInput = view.findViewById(R.id.terminalInput)
        btnSend = view.findViewById(R.id.btnSendCommand)
        btnClear = view.findViewById(R.id.btnClearTerminal)
        btnRunPython = view.findViewById(R.id.btnRunPython)
        btnFileManager = view.findViewById(R.id.btnFileManager)
        scrollView = view.findViewById(R.id.terminalLogScroll)

        printWelcome()

        btnSend.setOnClickListener { executeCommand() }
        btnClear.setOnClickListener { terminalOutput.text = ""; printWelcome() }
        btnRunPython.setOnClickListener { showPythonDialog() }
        btnFileManager.setOnClickListener { showFileBrowser() }
        terminalInput.setOnEditorActionListener { _, _, _ -> executeCommand(); true }
    }

    private fun printWelcome() {
        appendOutput("""
╔══════════════════════════════════════╗
║     Terminal Master Hub v1.0        ║
║   Terminal Linux + Python IDE        ║
║   by MichaelARC-NI                   ║
╚══════════════════════════════════════╝

Comandos:
  help     — Ayuda
  clear    — Limpiar terminal
  py <c>   — Ejecuta código Python inline
  python   — Abre editor Python
  run <f>  — Ejecuta script .py o .sh
  ls, pwd  — Comandos shell estándar
  social   — Redes sociales del desarrollador
  about    — Acerca de

""".trimIndent(), isWelcome = true)
    }

    private fun executeCommand(): Boolean {
        val input = terminalInput.text.toString().trim()
        if (input.isEmpty()) return false
        terminalInput.text.clear()
        appendOutput("$ $input", isInput = true)
        commandHistory.add(input)
        historyIndex = commandHistory.size
        lifecycleScope.launch { handleCommand(input) }
        return true
    }

    private suspend fun handleCommand(cmd: String) {
        when {
            cmd == "clear" -> { withContext(Dispatchers.Main) { terminalOutput.text = ""; printWelcome() } }
            cmd == "help" -> printWelcome()
            cmd == "social" || cmd == "redes" -> showSocialMedia()
            cmd == "about" || cmd == "info" -> showAbout()
            cmd.startsWith("py ") || cmd.startsWith("python ") -> runPythonCode(cmd.substringAfter(" "))
            cmd == "python" || cmd == "py" -> withContext(Dispatchers.Main) { showPythonDialog() }
            cmd.startsWith("run ") -> runFile(cmd.substringAfter(" "))
            else -> runShell(cmd)
        }
    }

    private suspend fun runShell(cmd: String) {
        try {
            val process = ProcessBuilder("sh", "-c", cmd)
                .directory(File(FileManager.getStoragePath()))
                .redirectErrorStream(true).start()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) output.appendLine(line)
            val result = output.toString().trim()
            if (result.isNotEmpty()) appendOutput(result)
            appendOutput("")
        } catch (e: Exception) {
            appendOutput("Error: ${e.message}")
        }
    }

    private suspend fun runPythonCode(code: String) {
        appendOutput(">>> Ejecutando Python...")
        val result = pythonBridge.executeScript(code)
        if (result.error != null) { appendOutput("Error: ${result.error}"); return }
        appendOutput(result.textOutput)
        if (result.hasGraphOutput && result.graphHtml != null) {
            withContext(Dispatchers.Main) {
                startActivity(Intent(requireContext(), PythonGraphActivity::class.java).apply {
                    putExtra(PythonGraphActivity.EXTRA_HTML_CONTENT, result.graphHtml)
                })
            }
        }
    }

    private suspend fun runFile(path: String) {
        val file = File(path)
        if (!file.exists()) { appendOutput("Archivo no encontrado: $path"); return }
        if (file.name.endsWith(".py")) runPythonCode(file.readText())
        else runShell("sh \"$path\"")
    }

    private fun showPythonDialog() {
        val input = EditText(requireContext()).apply {
            hint = "# Escribe tu código Python..."
            setTextColor(0xFFE6EDF3.toInt())
            setHintTextColor(0xFF555555.toInt())
            setBackgroundColor(0xFF0D1117.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 13f
            minLines = 8; maxLines = 20
        }
        MaterialAlertDialogBuilder(requireContext()).setTitle("Python Editor")
            .setView(input)
            .setPositiveButton("Ejecutar") { _, _ ->
                lifecycleScope.launch { runPythonCode(input.text.toString()) }
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun showSocialMedia() {
        val social = """
🌐 REDES DEL DESARROLLADOR

🐙 GitHub:    github.com/MichaelARC-NI
📱 Telegram:  t.me/ErosMobileTool
📧 Email:     michael@example.com

💻 Proyectos:
  • Sombra - Sombra lateral Android
  • Terminal Master Hub - Esta app
  • ErosFlashTool - Flasheo Samsung
""".trimIndent()
        MaterialAlertDialogBuilder(requireContext()).setTitle("Redes Sociales")
            .setMessage(social).setPositiveButton("OK", null).show()
    }

    private fun showAbout() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Acerca de Terminal Master Hub")
            .setMessage("""
Terminal Master Hub v1.0

Una herramienta todo-en-uno para Android:
✓ Terminal Linux sin root
✓ IDE Python con gráficos
✓ ADB & Fastboot por OTG
✓ Flasheo Samsung Odin3
✓ Flasheo Xiaomi Auto

Desarrollado por MichaelARC-NI
github.com/MichaelARC-NI
            """.trimIndent())
            .setPositiveButton("OK", null).show()
    }

    private fun showFileBrowser() {
        if (!PermissionManager.hasStoragePermission(requireContext())) {
            appendOutput("Permiso de almacenamiento requerido")
            return
        }
        lifecycleScope.launch {
            val files = FileManager.findTgzFiles().take(20)
            val items = files.map { "📦 ${it.name} (${FileManager.getFileSizeString(it.length())})" }
                .ifEmpty { listOf("(No se encontraron archivos)") }
            withContext(Dispatchers.Main) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Almacenamiento")
                    .setItems(items.toTypedArray()) { _, _ -> }
                    .setPositiveButton("Cerrar", null).show()
            }
        }
    }

    private fun appendOutput(text: String, isInput: Boolean = false, isWelcome: Boolean = false) {
        requireActivity().runOnUiThread {
            terminalOutput.append(if (isWelcome) text else "\n$text")
            scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }
}
