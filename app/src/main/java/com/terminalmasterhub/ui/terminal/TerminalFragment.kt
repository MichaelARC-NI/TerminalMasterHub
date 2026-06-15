package com.terminalmasterhub.ui.terminal

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
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
import com.terminalmasterhub.core.root.BootstrapManager
import com.terminalmasterhub.core.root.RootChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Terminal Linux + Python IDE embebido con:
 * - Banner adaptable a resolución
 * - Root checker
 * - Bootstrap Linux sin root
 * - Selector de salida Python (Terminal/Graphics)
 * - Explorador de archivos visual
 * - Redes sociales actualizadas
 */
class TerminalFragment : Fragment() {

    companion object {
        private const val DEV_NAME = "Michael Antonio Rodriguez Condega"
        private const val DEV_GITHUB = "MichaelARC-NI"
        private const val DEV_EMAIL = "androidmovil@proton.me"
        private const val DEV_TELEGRAM = "t.me/Michael_Antonio_Rodriguez"
        private const val DEV_FACEBOOK = "facebook.com/share/1D1pfVdbXE"
    }

    private lateinit var terminalOutput: TextView
    private lateinit var terminalInput: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnClear: ImageButton
    private lateinit var btnRunPython: ImageButton
    private lateinit var btnFileManager: ImageButton
    private lateinit var scrollView: ScrollView

    private val pythonBridge by lazy { PythonBridge(requireContext()) }
    private val bootstrapManager by lazy { BootstrapManager(requireContext()) }
    private val commandHistory = mutableListOf<String>()
    private var historyIndex = -1
    private var hasRootAccess = false

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

        // Asegurar MONOSPACE en toda la terminal (Feature 1)
        terminalOutput.typeface = android.graphics.Typeface.MONOSPACE
        terminalInput.typeface = android.graphics.Typeface.MONOSPACE
        terminalOutput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        terminalInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)

        // Verificar root al inicio (Feature 3)
        lifecycleScope.launch {
            val rootStatus = RootChecker.checkRoot(requireContext())
            hasRootAccess = rootStatus.hasRoot
        }

        printWelcome()
        setupListeners()
    }

    private fun setupListeners() {
        btnSend.setOnClickListener { executeCommand() }
        btnClear.setOnClickListener { terminalOutput.text = ""; printWelcome() }
        btnRunPython.setOnClickListener { showPythonDialog() }
        btnFileManager.setOnClickListener {
            lifecycleScope.launch { showFileExplorer() }
        }
        terminalInput.setOnEditorActionListener { _, _, _ -> executeCommand(); true }
    }

    // ===================== FEATURE 1: BANNER DINÁMICO =====================

    private fun printWelcome() {
        val columns = calculateColumns()
        val safeWidth = columns.coerceIn(30, 80)
        val innerWidth = safeWidth - 4

        val title1 = "Terminal Master Hub v1.1"
        val title2 = "Terminal Linux + Python IDE + Root Tools"
        val title3 = "by $DEV_NAME"

        val line = "═".repeat(safeWidth - 2)
        val pad1 = " ".repeat((innerWidth - title1.length).coerceAtLeast(0) / 2)
        val pad2 = " ".repeat((innerWidth - title2.length).coerceAtLeast(0) / 2)
        val pad3 = " ".repeat((innerWidth - title3.length).coerceAtLeast(0) / 2)

        val rootBadge = if (hasRootAccess) " [ROOT]" else ""
        val bootstrapBadge = if (bootstrapManager.isInstalled()) " [LINUX]" else ""

        val welcome = """
╔${line}╗
║ ${pad1}${title1}${" ".repeat((innerWidth - title1.length - pad1.length).coerceAtLeast(0))} ║
║ ${pad2}${title2}${" ".repeat((innerWidth - title2.length - pad2.length).coerceAtLeast(0))} ║
║ ${pad3}${title3}${" ".repeat((innerWidth - title3.length - pad3.length).coerceAtLeast(0))} ║
╚${line}╝

Estado: Terminal lista$rootBadge$bootstrapBadge

Comandos:
  help       — Ayuda general
  clear      — Limpiar terminal
  py <code>  — Ejecuta código Python inline
  python     — Abre editor Python
  run <file> — Ejecuta script .py o .sh
  ls, pwd    — Comandos shell
  root       — Verificar estado de root
  linux      — Gestionar entorno Linux
  explorer   — Explorador de archivos
  social     — Redes sociales
  about      — Acerca de

""".trimIndent()
        appendOutput(welcome, isWelcome = true)
    }

    private fun calculateColumns(): Int {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "echo \$COLUMNS"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val cols = reader.readLine()?.trim()?.toIntOrNull()
            reader.close()
            process.waitFor()
            if (cols != null && cols > 0) cols else 50
        } catch (e: Exception) {
            // Fallback: calcular por densidad de pantalla
            val metrics = resources.displayMetrics
            (metrics.widthPixels / (metrics.densityDpi / 160f * 7)).toInt().coerceAtLeast(40)
        }
    }

    // ===================== FEATURE 2: SOCIAL MEDIA (ACTUALIZADO) =====================

    private fun showSocialMedia() {
        val social = """
🌐 REDES SOCIALES

👤 Nombre:    $DEV_NAME
🐙 GitHub:    github.com/$DEV_GITHUB
📧 Email:     $DEV_EMAIL
📱 Telegram:  https://$DEV_TELEGRAM
📘 Facebook:  https://$DEV_FACEBOOK

💻 Proyectos:
  • Sombra - Sombra lateral Android
  • Terminal Master Hub - App todo-en-uno
  • ErosFlashTool - Flasheo Samsung

⭐ ¡Sígueme y contribuye!
""".trimIndent()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Redes Sociales")
            .setMessage(social)
            .setPositiveButton("OK", null)
            .setNeutralButton("GitHub") { _, _ ->
                try {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://github.com/MichaelARC-NI")
                    )
                    startActivity(intent)
                } catch (e: Exception) { }
            }
            .show()
    }

    private fun showAbout() {
        val about = """
$DEV_NAME

Terminal Master Hub v1.1

App Android todo-en-uno:
✓ Terminal Linux (sin root)
✓ IDE Python con gráficos
✓ ADB & Fastboot por OTG
✓ Samsung Odin3 Flasheo
✓ Xiaomi Auto-Flasher
✓ Explorador de archivos
✓ Root Checker nativo

📧 $DEV_EMAIL
📱 https://$DEV_TELEGRAM
🐙 github.com/$DEV_GITHUB
""".trimIndent()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Acerca de")
            .setMessage(about)
            .setPositiveButton("OK", null)
            .show()
    }

    // ===================== FEATURE 3: ROOT CHECK =====================

    private suspend fun showRootStatus() {
        appendOutput("🔍 Verificando acceso root...")
        val status = RootChecker.checkRoot(requireContext())
        appendOutput("")
        appendOutput("📊 Estado de Root:")
        appendOutput("  • Acceso: ${if (status.hasRoot) "✅ CONCEDIDO" else "❌ NO DISPONIBLE"}")
        appendOutput("  • Método: ${status.method.displayName}")
        if (status.binaries.isNotEmpty()) {
            appendOutput("  • Binarios: ${status.binaries.joinToString(", ")}")
        }
        appendOutput("  • Detalle: ${status.detail}")
        appendOutput("")

        if (!status.hasRoot) {
            appendOutput("⚠️  Modo limitado: No tienes acceso root.")
            appendOutput("   Las funciones de flasheo requieren USB OTG.")
            appendOutput("   El explorador usará solo almacenamiento interno.")
            appendOutput("")
        }
    }

    // ===================== FEATURE 4: BOOTSTRAP LINUX =====================

    private suspend fun handleLinuxSetup() {
        if (bootstrapManager.isInstalled()) {
            val status = bootstrapManager.getStatus()
            appendOutput("✅ Entorno Linux ya instalado")
            appendOutput("  • Prefix: ${status.prefixPath}")
            appendOutput("  • Paquetes: ${status.packages.size}/${BootstrapManager.REQUIRED_PACKAGES.size}")
            appendOutput("  • Tamaño: ${FileManager.getFileSizeString(status.totalSize)}")
            appendOutput("")
            appendOutput("  Usa 'apt update' para actualizar paquetes")
            return
        }

        appendOutput("📦 Instalando entorno Linux...")
        appendOutput("  Esto puede tomar varios minutos en la primera ejecución.")
        appendOutput("")

        bootstrapManager.onProgress = { msg, pct ->
            appendOutput("  [$pct%] $msg")
        }

        val result = bootstrapManager.install()
        if (result.isInstalled) {
            appendOutput("✅ Entorno Linux instalado correctamente")
            appendOutput("  • Paquetes: ${result.packages.size}/${BootstrapManager.REQUIRED_PACKAGES.size}")
        } else {
            appendOutput("❌ Error: ${result.message}")
        }
    }

    // ===================== FEATURE 5: PYTHON OUTPUT SELECTOR =====================

    private fun showPythonDialog() {
        val input = EditText(requireContext()).apply {
            hint = "# Escribe tu código Python..."
            setTextColor(0xFFE6EDF3.toInt())
            setHintTextColor(0xFF555555.toInt())
            setBackgroundColor(0xFF0D1117.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 13f
            minLines = 8
            maxLines = 20
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("🐍 Python Editor")
            .setView(input)
            .setPositiveButton("En Terminal") { _, _ ->
                val code = input.text.toString()
                lifecycleScope.launch { runPythonCode(code, false) }
            }
            .setNeutralButton("🎨 Ventana Gráfica") { _, _ ->
                val code = input.text.toString()
                lifecycleScope.launch { runPythonCode(code, true) }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private suspend fun runPythonCode(code: String, forceGraphic: Boolean = false) {
        val preview = code.take(50).replace("\n", " ").trim()
        appendOutput(">>> Ejecutando Python: $preview...")

        val result = pythonBridge.executeScript(code)
        if (result.error != null) {
            appendOutput("Error: ${result.error}")
            return
        }

        if (forceGraphic || result.hasGraphOutput) {
            // Forzar salida gráfica si el usuario lo eligió o si hay gráficos detectados
            if (result.graphHtml != null) {
                withContext(Dispatchers.Main) {
                    startActivity(Intent(requireContext(), PythonGraphActivity::class.java).apply {
                        putExtra(PythonGraphActivity.EXTRA_HTML_CONTENT, result.graphHtml)
                        putExtra(PythonGraphActivity.EXTRA_TEXT_OUTPUT, result.textOutput)
                    })
                }
                appendOutput("[✔] Abriendo ventana gráfica...")
            } else {
                // Sin salida gráfica, mostrar en terminal
                appendOutput(result.textOutput)
                appendOutput("[ℹ] El script no generó gráficos. Se muestra en terminal.")
            }
        } else {
            appendOutput(result.textOutput)
        }
    }

    private suspend fun runPythonFile(path: String) {
        val file = File(path)
        if (!file.exists()) {
            appendOutput("Archivo no encontrado: $path")
            return
        }
        val code = file.readText()

        // Mostrar diálogo de selector de salida
        withContext(Dispatchers.Main) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("🐍 Ejecutar: ${file.name}")
                .setMessage("¿Dónde quieres ver la salida?")
                .setPositiveButton("💻 Terminal") { _, _ ->
                    lifecycleScope.launch { runPythonCode(code, false) }
                }
                .setNeutralButton("🎨 Ventana Gráfica") { _, _ ->
                    lifecycleScope.launch { runPythonCode(code, true) }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    // ===================== FEATURE 6: FILE EXPLORER =====================

    private suspend fun showFileExplorer() {
        if (!PermissionManager.hasStoragePermission(requireContext())) {
            appendOutput("Permiso de almacenamiento requerido")
            PermissionManager.requestStoragePermission(requireActivity(), null)
            return
        }

        // Verificar root
        val rootStatus = RootChecker.checkRoot(requireContext())
        val rootPath = if (rootStatus.hasRoot) "/" else "/storage/emulated/0"

        appendOutput("📂 Explorador de Archivos")
        appendOutput("  Modo: ${if (rootStatus.hasRoot) "🔓 ROOT ($rootPath)" else "📱 No-Root ($rootPath)"}")
        appendOutput("  (Usa 'explorer <ruta>' para abrir una carpeta)")

        // Abrir explorador visual como diálogo
        withContext(Dispatchers.Main) {
            val explorerDialog = com.terminalmasterhub.ui.explorer.FileExplorerDialog(
                requireContext(),
                rootPath = rootPath,
                hasRoot = rootStatus.hasRoot,
                onFileSelected = { path ->
                    lifecycleScope.launch {
                        appendOutput("Seleccionado: $path")
                        if (path.endsWith(".py")) {
                            runPythonFile(path)
                        } else if (path.endsWith(".sh")) {
                            runShell("sh \"$path\"")
                        } else {
                            // Mostrar información del archivo
                            val file = File(path)
                            appendOutput("  Tamaño: ${FileManager.getFileSizeString(file.length())}")
                            if (!file.isDirectory) {
                                appendOutput("  (Abrir con 'run $path' si es ejecutable)")
                            }
                        }
                    }
                }
            )
            explorerDialog.show()
        }
    }

    // ===================== COMMAND HANDLER =====================

    private fun executeCommand(): Boolean {
        val input = terminalInput.text.toString().trim()
        if (input.isEmpty()) return false
        terminalInput.text.clear()
        appendOutput("\$ $input", isInput = true)
        commandHistory.add(input)
        historyIndex = commandHistory.size
        lifecycleScope.launch { handleCommand(input) }
        return true
    }

    private suspend fun handleCommand(cmd: String) {
        when {
            cmd == "clear" -> withContext(Dispatchers.Main) { terminalOutput.text = ""; printWelcome() }
            cmd == "help" -> printWelcome()
            cmd == "root" || cmd == "su" -> showRootStatus()
            cmd == "linux" || cmd == "bootstrap" -> handleLinuxSetup()
            cmd == "explorer" || cmd == "files" -> showFileExplorer()
            cmd.startsWith("explorer ") -> openExplorerPath(cmd.substringAfter(" "))
            cmd.startsWith("social") || cmd.startsWith("redes") -> showSocialMedia()
            cmd.startsWith("about") || cmd.startsWith("info") || cmd == "acerca" -> showAbout()
            cmd.startsWith("py ") || cmd.startsWith("python ") -> {
                val code = cmd.substringAfter(" ")
                runPythonCode(code, forceGraphic = false)
            }
            cmd == "python" || cmd == "py" -> withContext(Dispatchers.Main) { showPythonDialog() }
            cmd.startsWith("run ") -> {
                val path = cmd.substringAfter(" ")
                val file = File(path)
                if (file.name.endsWith(".py")) runPythonFile(path)
                else runShell("sh \"$path\"")
            }
            else -> runShell(cmd)
        }
    }

    private suspend fun openExplorerPath(path: String) {
        val file = File(path)
        if (!file.exists()) {
            appendOutput("Ruta no encontrada: $path")
            return
        }
        if (file.isDirectory) {
            appendOutput("📂 Contenido de: $path")
            file.listFiles()?.sortedBy { it.name }?.forEach { f ->
                val icon = if (f.isDirectory) "📁" else "📄"
                val size = if (f.isFile) " (${FileManager.getFileSizeString(f.length())})" else ""
                appendOutput("  $icon ${f.name}$size")
            }
        } else {
            appendOutput("${file.name} - ${FileManager.getFileSizeString(file.length())}")
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

    private fun appendOutput(text: String, isInput: Boolean = false, isWelcome: Boolean = false) {
        requireActivity().runOnUiThread {
            terminalOutput.append(if (isWelcome) text else "\n$text")
            scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }
}
