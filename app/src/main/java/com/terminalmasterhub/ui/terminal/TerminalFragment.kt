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
        // Banner limpio sin bordes ASCII — solo texto con MONOSPACE
        val rootBadge = if (hasRootAccess) " [ROOT]" else ""
        val bootstrapBadge = if (bootstrapManager.isInstalled()) " [LINUX]" else ""

        val welcome = """
Terminal Master Hub v1.3.5
Terminal Linux + Python IDE + Root Tools
by $DEV_NAME

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
  ubuntu     — Gestionar Ubuntu ARM64 (PRoot)
  proot      — Estado de PRoot
  mode       — Cambiar modo (ubuntu/local)

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

Terminal Master Hub v1.3.5

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
        appendOutput("=== Instalando PRoot + Ubuntu ARM64 ===")
        appendOutput("")
        appendOutput("Esto descargara e instalara un entorno Ubuntu 24.04")
        appendOutput("completo para ARM64 usando PRoot (sin necesidad de root).")
        appendOutput("Basado en el enfoque de Termux y Kali NetHunter.")
        appendOutput("")

        if (!prootManager.isNetworkAvailable()) {
            appendOutput("Error: No hay conexion a internet")
            appendOutput("  Se necesita internet para descargar PRoot y Ubuntu")
            return
        }

        appendOutput("Paso 1/2: Descargando e instalando PRoot...")
        appendOutput("  (Binario de Termux para ARM64)")
        prootManager.onProgress = { msg, pct ->
            appendOutput("  [$pct%] $msg")
        }
        val prootOk = prootManager.installProot()
        if (!prootOk) {
            appendOutput("  Error: No se pudo instalar PRoot")
            appendOutput("  Verifica tu conexion a internet y reintenta")
            appendOutput("  Compatible solo con ARM64 (aarch64)")
            return
        }
        appendOutput("  PRoot instalado correctamente!")
        appendOutput("")
        appendOutput("Paso 2/2: Descargando Ubuntu 24.04 ARM64 rootfs (~30MB)...")
        appendOutput("  Esto puede tomar varios minutos segun tu conexion.")
        val ubuntuOk = prootManager.installUbuntuRootfs()
        if (!ubuntuOk) {
            appendOutput("  Error: No se pudo instalar Ubuntu rootfs")
            return
        }
        appendOutput("  Ubuntu ARM64 instalado y configurado!")
        appendOutput("")
        appendOutput("✅ Instalacion completada exitosamente!")
        appendOutput("")
        appendOutput("Comandos disponibles ahora:")
        appendOutput("  mode ubuntu  - Activar modo Ubuntu (PRoot)")
        appendOutput("  mode local   - Volver al modo Bootstrap local")
        appendOutput("  apt update   - Actualizar paquetes Ubuntu")
        appendOutput("  apt install python3 - Instalar Python")
        appendOutput("  apt install cmus    - Instalar reproductor de musica")
        appendOutput("  python3      - Python en Ubuntu")
        appendOutput("  cmus         - Reproductor de musica en terminal")
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
            cmd == "linux" || cmd == "bootstrap" -> handleLinuxSetup(cmd)
            cmd.startsWith("ubuntu") || cmd.startsWith("proot") -> handleProotSetup()
            cmd == "mode ubuntu" || cmd == "mode proot" -> { useProotMode = true; appendOutput("Modo Ubuntu ARM64 activado. Los comandos se ejecutaran dentro de PRoot.") }
            cmd == "mode local" || cmd == "mode bootstrap" -> { useProotMode = false; appendOutput("Modo Bootstrap local activado.") }
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
            cmd == "apt" || cmd == "apt-get" -> {
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
            // Si estamos en modo PRoot/Ubuntu, ejecutar dentro del entorno Ubuntu
            if (useProotMode && prootManager.isProotAvailable() && prootManager.isUbuntuInstalled()) {
                val result = prootManager.executeInProot(cmd)
                if (result.isNotEmpty()) appendOutput(result)
                appendOutput("")
                return
            }

            // Inyectar entorno PREFIX con HOME, PATH y locale
            val prefixPath = bootstrapManager.getPrefixDir().absolutePath
            val homePath = "$prefixPath/${BootstrapManager.HOME_DIR}"

            // En Android 14+, los archivos en /data/data/ no son ejecutables
            // debido a noexec. Verificamos si el comando usa un wrapper
            // y si es asi, lo ejecutamos via 'sh' explicitamente.
            val resolvedCmd = resolveShellCommand(cmd, prefixPath)

            val envMap = mapOf(
                "PREFIX" to prefixPath,
                "HOME" to homePath,
                "PATH" to "$prefixPath/bin:/system/bin:/system/xbin",
                "TMPDIR" to "$prefixPath/tmp",
                "LANG" to "en_US.UTF-8",
                "LC_ALL" to "C",
                "PYTHONPATH" to "$prefixPath/lib/python3/site-packages"
            )
            val pb = ProcessBuilder("sh", "-c", resolvedCmd)
            pb.environment().putAll(envMap)
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

    /**
     * Resuelve el comando shell para manejar noexec en Android 14+.
     * Si el primer token coincide con un wrapper en \$PREFIX/bin/,
     * antepone 'sh' para ejecutarlo como script.
     */
    private fun resolveShellCommand(cmd: String, prefixPath: String): String {
        val firstWord = cmd.split("\\s+".toRegex()).firstOrNull() ?: return cmd
        val wrapper = File("$prefixPath/bin/$firstWord")
        if (wrapper.exists() && !wrapper.canExecute()) {
            return cmd.replaceFirst(firstWord, "sh ${wrapper.absolutePath}")
        }
        return cmd
    }

    private fun appendOutput(text: String, isInput: Boolean = false, isWelcome: Boolean = false) {
        requireActivity().runOnUiThread {
            terminalOutput.append(if (isWelcome) text else "\n$text")
            scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }
}
