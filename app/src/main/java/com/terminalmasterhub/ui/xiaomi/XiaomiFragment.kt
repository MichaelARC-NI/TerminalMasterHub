package com.terminalmasterhub.ui.xiaomi

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.terminalmasterhub.R
import com.terminalmasterhub.TerminalMasterHubApp
import com.terminalmasterhub.core.adb.FastbootClient
import com.terminalmasterhub.core.file.FileManager
import com.terminalmasterhub.core.mitool.MiToolParser
import com.terminalmasterhub.core.mitool.TgzExtractor
import com.terminalmasterhub.core.usb.UsbBroadcastReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Fragmento de la sesión Xiaomi MiTool.
 *
 * Proporciona interfaz gráfica para flashear ROMs Fastboot
 * de Xiaomi/POCO/Redmi automáticamente.
 *
 * Flujo:
 * 1. Seleccionar ROM .tgz
 * 2. Extraer automáticamente
 * 3. Parsear script flash_all.sh
 * 4. Flashear particiones en orden
 * 5. Reboot
 *
 * Basado en el análisis de MiTool de offici5l/MiTool.
 */
class XiaomiFragment : Fragment() {

    private lateinit var deviceStatusText: TextView
    private lateinit var romPathText: TextView
    private lateinit var statusText: TextView
    private lateinit var logView: TextView
    private lateinit var btnBrowse: Button
    private lateinit var btnExtract: Button
    private lateinit var btnFlash: Button
    private lateinit var progressBar: ProgressBar

    private val usbCore get() = TerminalMasterHubApp.instance.usbCore
    private val fastbootClient by lazy { FastbootClient(usbCore) }
    private val tgzExtractor by lazy { TgzExtractor(requireContext()) }

    private var selectedTgzFile: File? = null
    private var extractedRomDir: File? = null
    private var currentRomInfo: MiToolParser.RomInfo? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            uri?.let {
                val path = FileManager.getStoragePath() + "/" +
                        (uri.path?.substringAfter(":") ?: "")
                val file = File(path)
                if (file.exists() && file.name.endsWith(".tgz")) {
                    selectedTgzFile = file
                    romPathText.text = file.name
                    btnExtract.isEnabled = true
                    appendLog("ROM seleccionada: ${file.name}")
                    appendLog("Tamaño: ${FileManager.getFileSizeString(file.length())}")
                } else {
                    appendLog("Error: Selecciona un archivo .tgz válido")
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_xiaomi, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        deviceStatusText = view.findViewById(R.id.xiaomiDeviceStatus)
        romPathText = view.findViewById(R.id.xiaomiRomPath)
        statusText = view.findViewById(R.id.xiaomiStatusText)
        logView = view.findViewById(R.id.xiaomiLog)
        btnBrowse = view.findViewById(R.id.btnXiaomiBrowse)
        btnExtract = view.findViewById(R.id.btnXiaomiExtract)
        btnFlash = view.findViewById(R.id.btnXiaomiFlash)
        progressBar = view.findViewById(R.id.xiaomiProgress)

        setupListeners()
        setupUsbReceiver()
    }

    override fun onResume() {
        super.onResume()
        checkFastbootDevice()
    }

    private fun setupListeners() {
        btnBrowse.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            filePickerLauncher.launch(intent)
        }

        btnExtract.setOnClickListener {
            val tgzFile = selectedTgzFile
            if (tgzFile == null) {
                appendLog("Selecciona una ROM .tgz primero")
                return@setOnClickListener
            }
            lifecycleScope.launch { extractRom(tgzFile) }
        }

        btnFlash.setOnClickListener {
            if (!fastbootClient.isDeviceConnected) {
                appendLog("Conecta el dispositivo en modo Fastboot")
                return@setOnClickListener
            }
            if (currentRomInfo == null || !currentRomInfo!!.isValid) {
                appendLog("Extrae y analiza la ROM primero")
                return@setOnClickListener
            }
            lifecycleScope.launch { flashRom() }
        }
    }

    private fun setupUsbReceiver() {
        UsbBroadcastReceiver.onDeviceDetached = { _ ->
            deviceStatusText.text = getString(R.string.fastboot_no_device)
            deviceStatusText.setTextColor(0xFFFF3333.toInt())
            appendLog("Dispositivo desconectado")
        }

        UsbBroadcastReceiver.onPermissionResult = { granted, device ->
            if (granted && device != null && usbCore.isFastbootMode(device)) {
                lifecycleScope.launch {
                    val connected = fastbootClient.connect()
                    if (connected) {
                        deviceStatusText.text = "${usbCore.getVendorName(device)} - Fastboot"
                        deviceStatusText.setTextColor(0xFF00FF41.toInt())
                        appendLog("✓ Conectado a Fastboot")
                    }
                }
            }
        }
    }

    private fun checkFastbootDevice() {
        val devices = usbCore.getAttachedDevices()
        for ((_, device) in devices) {
            if (usbCore.isFastbootMode(device)) {
                deviceStatusText.text = "${usbCore.getVendorName(device)} - Conectando..."
                lifecycleScope.launch {
                    val connected = fastbootClient.connect()
                    if (connected) {
                        deviceStatusText.text = "${usbCore.getVendorName(device)} - Fastboot"
                        deviceStatusText.setTextColor(0xFF00FF41.toInt())
                        appendLog("✓ Conectado a ${usbCore.getVendorName(device)} en Fastboot")
                    }
                }
            }
        }
    }

    private suspend fun extractRom(tgzFile: File) {
        btnExtract.isEnabled = false
        btnExtract.text = "EXTRAYENDO..."
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0

        val outputDir = FileManager.getTempDir(requireContext())
        val romDir = File(outputDir, tgzFile.nameWithoutExtension)

        // Limpiar extracciones previas
        if (romDir.exists()) {
            FileManager.deleteRecursive(romDir)
        }

        appendLog("Extrayendo ROM: ${tgzFile.name}")
        appendLog("Destino: ${romDir.absolutePath}")

        val result = tgzExtractor.extract(tgzFile, romDir) { progress ->
            lifecycleScope.launch(Dispatchers.Main) {
                val pct = if (progress.totalBytes > 0) {
                    (progress.bytesExtracted * 100 / progress.totalBytes).toInt()
                } else 0
                progressBar.progress = pct.coerceIn(0, 100)
                statusText.text = "Extrayendo: ${progress.currentFile}"
                appendLog(progress.currentFile)
            }
        }

        progressBar.visibility = View.GONE
        btnExtract.isEnabled = true
        btnExtract.text = getString(R.string.xiaomi_extracting).replace("…", "")

        if (result != null) {
            extractedRomDir = result
            appendLog("✅ ROM extraída en: ${result.absolutePath}")

            // Analizar la ROM extraída
            analyzeRom(result)
        } else {
            appendLog("❌ Error extrayendo ROM")
        }
    }

    private fun analyzeRom(romDir: File) {
        appendLog("\nAnalizando ROM extraída...")

        val romInfo = MiToolParser.analyzeRom(romDir)
        currentRomInfo = romInfo

        if (!romInfo.isValid) {
            appendLog("❌ ROM no válida: no se encontraron scripts flash_all.sh")
            btnFlash.isEnabled = false
            return
        }

        appendLog("Scripts encontrados:")
        for (script in romInfo.flashScripts) {
            appendLog("  • ${script.file.name} (${script.type.description})")
            appendLog("    Comandos: ${script.commands.size}")
        }

        appendLog("Imágenes disponibles (${romInfo.images.size}):")
        for (img in romInfo.images.take(10)) {
            appendLog("  • ${img.name} (${FileManager.getFileSizeString(img.size)})")
        }

        val (valid, msg) = MiToolParser.validateRom(romInfo)
        if (valid) {
            appendLog("✅ $msg")
            btnFlash.isEnabled = true
            statusText.text = "ROM lista para flashear"
            statusText.setTextColor(0xFF00FF41.toInt())
        } else {
            appendLog("❌ $msg")
            btnFlash.isEnabled = false
        }
    }

    private suspend fun flashRom() {
        val romInfo = currentRomInfo ?: return
        val script = MiToolParser.getRecommendedScript(romInfo) ?: return

        btnFlash.isEnabled = false
        btnFlash.text = "FLASHEANDO..."

        appendLog("\n══════════════ INICIANDO FLASHEO XIAOMI ══════════════")
        appendLog("Script: ${script.file.name}")
        appendLog("Comandos: ${script.commands.size}")
        appendLog("")

        withContext(Dispatchers.IO) {
            val success = fastbootClient.flashFromScript(script.commands) { index, total, cmd ->
                appendLog("[${index + 1}/$total] $cmd")
            }

            withContext(Dispatchers.Main) {
                btnFlash.isEnabled = true
                btnFlash.text = getString(R.string.xiaomi_flash)

                if (success) {
                    appendLog("\n✅ FLASHEO COMPLETADO")
                    statusText.text = "Flasheo completado"
                    statusText.setTextColor(0xFF00FF41.toInt())
                } else {
                    appendLog("\n❌ ERROR DURANTE EL FLASHEO")
                    statusText.text = "Error en flasheo"
                    statusText.setTextColor(0xFFFF3333.toInt())
                }
            }
        }
    }

    private fun appendLog(text: String) {
        requireActivity().runOnUiThread {
            logView.append("$text\n")
            val scrollView = view?.findViewById<android.widget.ScrollView>(R.id.xiaomiLogScroll)
            scrollView?.fullScroll(View.FOCUS_DOWN)
        }
    }
}
