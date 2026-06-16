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
import com.terminalmasterhub.core.proot.ProotManager
import com.terminalmasterhub.core.adb.AdbNativeManager
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
    private val prootManager by lazy { ProotManager(requireContext()) }
    private val adbNativeManager by lazy { AdbNativeManager(requireContext()) }
    private var useProotMode = false
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
        setupKeyboardToolbar()
        btnSend.setOnClickListener { executeCommand() }
        btnClear.setOnClickListener { terminalOutput.text = ""; printWelcome() }
        btnRunPython.setOnClickListener { showPythonDialog() }
        btnFileManager.setOnClickListener {
            lifecycleScope.launch { showFileExplorer() }
        }
        terminalInput.setOnEditorActionListener { _, _, _ -> executeCommand(); true }
    }

    private fun setupKeyboardToolbar() {
        // Tab
        view?.findViewById<android.widget.Button>(R.id.btnKeyTab)?.setOnClickListener {
            val cp = terminalInput.selectionStart
            terminalInput.text.insert(cp, "\t")
        }
        // ESC
        view?.findViewById<android.widget.Button>(R.id.btnKeyEsc)?.setOnClickListener {
            val cp = terminalInput.selectionStart
            terminalInput.text.insert(cp, "\u001b")
        }
        // Up arrow - history previous
        view?.findViewById<android.widget.Button>(R.id.btnKeyUp)?.setOnClickListener {
            if (commandHistory.isNotEmpty()) {
                historyIndex = if (historyIndex > 0) historyIndex - 1 else 0
                terminalInput.setText(commandHistory.getOrElse(historyIndex) { "" })
                terminalInput.setSelection(terminalInput.text.length)
            }
        }
        // Down arrow - history next
        view?.findViewById<android.widget.Button>(R.id.btnKeyDown)?.setOnClickListener {
            if (commandHistory.isNotEmpty() && historyIndex < commandHistory.size - 1) {
                historyIndex++
                terminalInput.setText(commandHistory.getOrElse(historyIndex) { "" })
                terminalInput.setSelection(terminalInput.text.length)
            }
        }
        // Home - cursor to start
        view?.findViewById<android.widget.Button>(R.id.btnKeyHome)?.setOnClickListener {
            terminalInput.setSelection(0)
        }
        // End - cursor to end
        view?.findViewById<android.widget.Button>(R.id.btnKeyEnd)?.setOnClickListener {
            terminalInput.setSelection(terminalInput.text.length)
        }
        // Del - delete forward
        view?.findViewById<android.widget.Button>(R.id.btnKeyDel)?.setOnClickListener {
            val cp = terminalInput.selectionStart
            if (cp < terminalInput.text.length) {
                val t = terminalInput.text.toString()
                terminalInput.setText(t.substring(0, cp) + t.substring(cp + 1))
                terminalInput.setSelection(cp)
            }
        }
        // Files - open explorer
        view?.findViewById<android.widget.Button>(R.id.btnKeyFiles)?.setOnClickListener {
            lifecycleScope.launch { showFileExplorer() }
        }
        // Ctrl - toggle keyboard toolbar visibility
        view?.findViewById<android.widget.Button>(R.id.btnKeyCtrl)?.setOnClickListener {
            val kb = view?.findViewById<android.view.View>(R.id.keyboardToolbar)
            if (kb != null) {
                kb.visibility = if (kb.visibility == android.view.View.VISIBLE)
                    android.view.View.GONE else android.view.View.VISIBLE
            }
        }
        // Alt - send Ctrl+C (SIGINT)
        view?.findViewById<android.widget.Button>(R.id.btnKeyAlt)?.setOnClickListener {
            executeCommandWithText("\u0003")
        }
    }

    /** Execute Ctrl character as a command (for Alt/Ctrl key combinations) */
    private fun executeCommandWithText(text: String) {
        appendOutput("$ $text")
        lifecycleScope.launch {
            handleCommand(text)
        }
    }

    // ===================== FEATURE 1: BANNER DINÁMICO =====================

    private fun printWelcome() {
        val rootBadge = if (hasRootAccess) " [ROOT]" else ""
        val bootstrapBadge = if (bootstrapManager.isInstalled()) " [LINUX]" else ""

        val welcome = """
Terminal Master Hub v1.5.3
Terminal Linux + Python IDE + Root Tools
by $DEV_NAME

Estado: Terminal lista$rootBadge$bootstrapBadge

Comandos:
  help       - Ayuda general
  clear      - Limpiar terminal
  py <code>  - Ejecuta codigo Python inline
  python     - Abre editor Python
  run <file> - Ejecuta script .py o .sh
  ls, pwd    - Comandos shell
  root       - Verificar estado de root
  linux      - Gestionar entorno Linux
  explorer   - Explorador de archivos
  social     - Redes sociales
  about      - Acerca de
  ubuntu     - Gestionar Ubuntu ARM64
  proot      - Estado de Ubuntu ARM64
  mode       - Cambiar modo (ubuntu/local)
  cmus       - Reproductor de musica

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

    private suspend fun handleProotSetup() {
        val status = prootManager.getStatus()
        appendOutput("=== PRoot / Ubuntu ARM64 ===")
        appendOutput("")
        appendOutput("Estado: ${status.message}")
        appendOutput("")
        if (status.isUbuntuInstalled) {
            appendOutput("Comandos disponibles:")
            appendOutput("  mode ubuntu  - Activar modo Ubuntu (PRoot)")
            appendOutput("  mode local   - Volver al modo Bootstrap local")
            appendOutput("  apt update   - Actualizar paquetes Ubuntu")
            appendOutput("  apt install  - Instalar paquetes")
            appendOutput("  python3      - Python en Ubuntu")
            appendOutput("  cmus         - Reproductor de musica")
            appendOutput("  bash         - Terminal bash completa")
        } else {
            appendOutput("Para instalar Ubuntu ARM64:")
            appendOutput("  bootstrap proot install  - Descarga e instala PRoot + Ubuntu")
            appendOutput("")
            appendOutput("Requisitos:")
            appendOutput("  - Conexion a internet (~30MB de descarga)")
            appendOutput("  - Espacio en almacenamiento (~200MB)")
            appendOutput("  - Dispositivo ARM64 (aarch64)")
        }
        appendOutput("")
        appendOutput("Basado en el enfoque de Termux y Kali NetHunter:")
        appendOutput("- PRoot: entorno aislado sin root")
        appendOutput("- Ubuntu 24.04 ARM64: apt, python3, cmus, etc.")
    }

    private fun showAbout() {
        val about = """
$DEV_NAME

Terminal Master Hub v1.5.3

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

    private suspend fun handleLinuxSetup(cmd: String = "") {
        if (cmd.contains("proot install") || cmd.contains("ubuntu install")) {
            handleProotInstall()
            return
        }
        if (bootstrapManager.isInstalled()) {
            val status = bootstrapManager.getStatus()
            appendOutput("✅ Entorno Linux ya instalado")
            appendOutput("  • Prefix: ${status.prefixPath}")
            appendOutput("  • Paquetes: ${status.packages.size}/${BootstrapManager.REQUIRED_PACKAGES.size}")
            appendOutput("  • Tamaño: ${FileManager.getFileSizeString(status.totalSize)}")
            appendOutput("")
            appendOutput("  Usa 'apt update' para actualizar paquetes")
            appendOutput("  Usa 'bootstrap proot install' para instalar Ubuntu ARM64 completo")
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
            appendOutput("  Usa 'bootstrap proot install' para Ubuntu ARM64 completo")
        } else {
            appendOutput("❌ Error: ${result.message}")
        }
    }

    private suspend fun handleProotInstall() {
        appendOutput("=== Instalando Ubuntu ARM64 ===")
        appendOutput("")
        appendOutput("Entorno Ubuntu 24.04 ARM64 con PRoot + Ubuntu.")
        appendOutput("Basado en el enfoque de Termux y Kali NetHunter.")
        appendOutput("")

        // Verificar assets y conectividad
        val hasAssets = try {
            requireContext().assets.open("ubuntu/ubuntu_rootfs").use { true }
        } catch (e: Exception) { false }

        if (hasAssets) {
            appendOutput("✅ Assets embebidos encontrados! Extrayendo...")
        } else {
            appendOutput("⚠️ Assets embebidos NO encontrados.")
            appendOutput("   Se intentara descargar desde internet.")
            if (!prootManager.isNetworkAvailable()) {
                appendOutput("")
                appendOutput("❌ Sin assets y sin internet.")
                appendOutput("   Descarga la ultima version desde:")
                appendOutput("   https://github.com/MichaelARC-NI/TerminalMasterHub/releases")
                appendOutput("")
                appendOutput("   O instala manualmente copiando los archivos:")
                appendOutput("   1. Descarga ubuntu-base-24.04.4-base-arm64.tar.gz")
                appendOutput("   2. Colocalo en: /sdcard/ubuntu_rootfs")
                appendOutput("   3. Reintenta el comando")
                return
            }
        }
        appendOutput("")

        prootManager.onProgress = { msg, pct ->
            appendOutput("  [$pct%] $msg")
        }

        // Instalar PRoot (assets primero, descarga como fallback)
        appendOutput("Paso 1/2: Instalando PRoot...")
        val prootOk = prootManager.installProot()
        if (!prootOk) {
            appendOutput("  Error instalando PRoot")
            return
        }
        appendOutput("  PRoot instalado!")

        // Instalar Ubuntu (assets primero, descarga como fallback)
        appendOutput("")
        appendOutput("Paso 2/2: Instalando Ubuntu 24.04 ARM64...")
        val ubuntuOk = prootManager.installUbuntuRootfs()
        if (!ubuntuOk) {
            appendOutput("  Error instalando Ubuntu rootfs")
            return
        }
        appendOutput("  Ubuntu ARM64 instalado!")

        appendOutput("")
        appendOutput("✅ Instalacion completada exitosamente!")
        appendOutput("")
        appendOutput("Comandos disponibles ahora:")
        appendOutput("  mode ubuntu  - Activar modo Ubuntu (PRoot")
        appendOutput("  mode local   - Volver al modo Bootstrap local")
        appendOutput("  apt update   - Actualizar paquetes Ubuntu")
        appendOutput("  apt install python3 cmus - Instalar herramientas")
        appendOutput("")
        useProotMode = true
        appendOutput("Modo Ubuntu ARM64 activado automaticamente!")
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
            cmd.startsWith("linux") || cmd.startsWith("bootstrap") -> handleLinuxSetup(cmd)
            cmd.startsWith("ubuntu") && cmd.contains("install") -> handleLinuxSetup(cmd)
            cmd.startsWith("ubuntu") || cmd.startsWith("proot") -> handleProotSetup()
            cmd == "mode ubuntu" || cmd == "mode proot" -> { useProotMode = true; appendOutput("Modo Ubuntu ARM64 activado. Los comandos se ejecutan via PRoot + Ubuntu.") }
            cmd == "mode local" || cmd == "mode bootstrap" -> { useProotMode = false; appendOutput("Modo local activado. Solo comandos basicos del sistema.") }
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
            cmd == "adb" || cmd.startsWith("adb ") || cmd == "fastboot" || cmd.startsWith("fastboot ") -> {
                if (prootManager.isUbuntuInstalled() && useProotMode) {
                    runShell(cmd)
                } else {
                    appendOutput("  ADB/Fastboot nativos disponibles")
                    appendOutput("  Activa modo Ubuntu primero: mode ubuntu")
                    appendOutput("  O ve a la pestana ADB/Fastboot para USB OTG")
                }
            }
            cmd == "apt" || cmd.startsWith("apt ") || cmd == "apt-get" || cmd.startsWith("apt-get ") -> {
                appendOutput("Usa: apt update, apt install <paquete>")
                appendOutput("Si estas en modo local, primero instala Ubuntu:")
                appendOutput("  bootstrap proot install")
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
            // v1.5.1: Usar PRoot + Ubuntu ARM64
            // para comandos que requieren el entorno Linux.
            // PRoot usa ptrace para ejecutar binarios glibc sin root.
            // Compatible con Android 14, 15, 16, 17+.
            if (prootManager.isUbuntuInstalled() && (
                    useProotMode ||
                    cmd.startsWith("apt ") || cmd == "apt" ||
                    cmd.startsWith("apt-get") ||
                    cmd.startsWith("python3") ||
                    cmd.startsWith("pip") ||
                    cmd.startsWith("cmus") ||
                    cmd.startsWith("bash") ||
                    cmd.startsWith("tar ") ||
                    cmd.startsWith("git ") ||
                    cmd.startsWith("make ") ||
                    cmd.startsWith("gcc ") ||
                    cmd.startsWith("g++ ") ||
                    cmd.startsWith("node ") ||
                    cmd.startsWith("npm ") ||
                    cmd.startsWith("adb ") || cmd == "adb" ||
                    cmd.startsWith("fastboot ") || cmd == "fastboot"
                )) {
                val result = prootManager.executeInProot(cmd)
                if (result.isNotEmpty()) appendOutput(result)
                appendOutput("")
                return
            }

            val prefixPath = bootstrapManager.getPrefixDir().absolutePath
            val homePath = "$prefixPath/${BootstrapManager.HOME_DIR}"

            // Comandos basicos del sistema Android (ls, pwd, echo, cd, etc.)
            // Usamos PATH del sistema, NO incluimos PREFIX/bin/ (noexec)
            val pb = ProcessBuilder("sh", "-c", cmd)
            pb.environment()["PREFIX"] = prefixPath
            pb.environment()["HOME"] = homePath
            pb.environment()["PATH"] = "/system/bin:/system/xbin"
            pb.environment()["LANG"] = "en_US.UTF-8"
            pb.environment()["LC_ALL"] = "C"
            pb.directory(File(FileManager.getStoragePath()))
            pb.redirectErrorStream(true)
            val process = pb.start()
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
