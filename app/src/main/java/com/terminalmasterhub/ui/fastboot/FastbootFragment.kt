package com.terminalmasterhub.ui.fastboot

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.terminalmasterhub.R
import com.terminalmasterhub.TerminalMasterHubApp
import com.terminalmasterhub.core.adb.AdbClient
import com.terminalmasterhub.core.adb.FastbootClient
import com.terminalmasterhub.core.file.FileManager
import com.terminalmasterhub.core.usb.UsbBroadcastReceiver
import com.terminalmasterhub.core.usb.UsbManagerCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Fragmento de la sesión ADB/Fastboot Universal.
 *
 * Proporciona:
 * - Detección y conexión a dispositivos en modo fastboot
 * - Consola para comandos fastboot manuales
 * - Botones rápidos: devices, reboot, flash
 * - Información del dispositivo conectado
 */
class FastbootFragment : Fragment() {

    private lateinit var deviceStatusText: TextView
    private lateinit var logView: TextView
    private lateinit var commandInput: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnDevices: Button
    private lateinit var btnReboot: Button
    private lateinit var btnFlash: Button

    private val usbCore get() = TerminalMasterHubApp.instance.usbCore
    private val fastbootClient by lazy { FastbootClient(usbCore) }
    private val adbClient by lazy { AdbClient(usbCore) }

    private var currentMode: DeviceMode = DeviceMode.DISCONNECTED

    enum class DeviceMode { DISCONNECTED, FASTBOOT, ADB }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            uri?.let {
                val path = uri.path?.substringAfter(":")?.let { "/storage/emulated/0/$it" }
                if (path != null) {
                    lifecycleScope.launch { flashImage(path) }
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_fastboot, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        deviceStatusText = view.findViewById(R.id.fastbootDeviceStatus)
        logView = view.findViewById(R.id.fastbootLog)
        commandInput = view.findViewById(R.id.fastbootInput)
        btnSend = view.findViewById(R.id.btnFastbootSend)
        btnDevices = view.findViewById(R.id.btnFastbootDevices)
        btnReboot = view.findViewById(R.id.btnFastbootReboot)
        btnFlash = view.findViewById(R.id.btnFastbootFlash)

        setupListeners()
        setupUsbReceiver()
    }

    override fun onResume() {
        super.onResume()
        checkConnectedDevices()
    }

    private fun setupListeners() {
        btnDevices.setOnClickListener {
            lifecycleScope.launch { runFastbootCommand("devices") }
        }

        btnReboot.setOnClickListener {
            lifecycleScope.launch { runFastbootCommand("reboot") }
        }

        btnFlash.setOnClickListener {
            showFlashDialog()
        }

        btnSend.setOnClickListener {
            val cmd = commandInput.text.toString().trim()
            if (cmd.isNotEmpty()) {
                commandInput.text.clear()
                lifecycleScope.launch { runFastbootCommand(cmd) }
            }
        }

        commandInput.setOnEditorActionListener { _, _, _ ->
            val cmd = commandInput.text.toString().trim()
            if (cmd.isNotEmpty()) {
                commandInput.text.clear()
                lifecycleScope.launch { runFastbootCommand(cmd) }
            }
            true
        }
    }

    private fun setupUsbReceiver() {
        UsbBroadcastReceiver.onDeviceDetached = { _ ->
            deviceStatusText.text = getString(R.string.fastboot_no_device)
            deviceStatusText.setTextColor(0xFFFF3333.toInt())
            currentMode = DeviceMode.DISCONNECTED
            appendLog("Dispositivo desconectado")
        }

        UsbBroadcastReceiver.onPermissionResult = { granted, device ->
            if (granted && device != null) {
                lifecycleScope.launch {
                    connectToDevice(device.deviceId)
                }
            }
        }
    }

    private fun checkConnectedDevices() {
        val devices = usbCore.getAttachedDevices()
        for ((_, device) in devices) {
            when {
                usbCore.isFastbootMode(device) -> {
                    deviceStatusText.text = "${usbCore.getVendorName(device)} - Fastboot"
                    deviceStatusText.setTextColor(0xFF00FF41.toInt())
                    currentMode = DeviceMode.FASTBOOT
                    appendLog("Dispositivo Fastboot detectado: ${device.productName ?: "Desconocido"}")
                    lifecycleScope.launch { fastbootClient.connect() }
                }
                !usbCore.isSamsungDownloadMode(device) -> {
                    deviceStatusText.text = "${usbCore.getVendorName(device)} - Modo ADB"
                    deviceStatusText.setTextColor(0xFF3399FF.toInt())
                    currentMode = DeviceMode.ADB
                    appendLog("Dispositivo ADB detectado: ${device.productName ?: "Desconocido"}")
                    lifecycleScope.launch { adbClient.connect() }
                }
            }
        }
    }

    private suspend fun connectToDevice(deviceId: Int) {
        appendLog("Conectando al dispositivo...")
        val connected = fastbootClient.connect()
        if (connected) {
            currentMode = DeviceMode.FASTBOOT
            deviceStatusText.text = "Fastboot conectado"
            deviceStatusText.setTextColor(0xFF00FF41.toInt())
            appendLog("✓ Conectado a Fastboot")

            // Mostrar info del dispositivo
            val info = fastbootClient.getDeviceInfo()
            appendLog("Producto: ${info["product"] ?: "N/A"}")
            appendLog("Bootloader: ${info["version-bootloader"] ?: "N/A"}")
        } else {
            appendLog("✗ No se pudo conectar en modo Fastboot")
            // Intentar ADB
            val adbConnected = adbClient.connect()
            if (adbConnected) {
                currentMode = DeviceMode.ADB
                deviceStatusText.text = "ADB conectado"
                deviceStatusText.setTextColor(0xFF3399FF.toInt())
                appendLog("✓ Conectado a ADB")
            }
        }
    }

    private suspend fun runFastbootCommand(command: String) {
        appendLog("fastboot $command")

        val result = when (currentMode) {
            DeviceMode.FASTBOOT -> fastbootClient.sendCommand(command)
            DeviceMode.ADB -> adbClient.shellCommand(command)
            DeviceMode.DISCONNECTED -> {
                appendLog("No hay dispositivo conectado. Verifica la conexión OTG.")
                return
            }
        }

        if (result != null) {
            appendLog(result)
        } else {
            appendLog("Error: Sin respuesta del dispositivo")
        }
    }

    private fun showFlashDialog() {
        val partitions = arrayOf("boot", "recovery", "system", "vendor", "dtbo", "vbmeta", "super", "userdata")
        var selectedPartition = "boot"

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Flashear imagen")
            .setMessage("Selecciona la partición y luego el archivo .img")
            .setSingleChoiceItems(partitions, 0) { _, which ->
                selectedPartition = partitions[which]
            }
            .setPositiveButton("Seleccionar archivo") { _, _ ->
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                filePickerLauncher.launch(intent)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private suspend fun flashImage(imagePath: String) {
        appendLog("Flasheando: $imagePath")
        val file = File(imagePath)
        if (!file.exists()) {
            appendLog("Error: Archivo no encontrado")
            return
        }

        withContext(Dispatchers.IO) {
            val success = if (currentMode == DeviceMode.FASTBOOT) {
                fastbootClient.flashPartition("boot", file) { sent, total ->
                    val pct = if (total > 0) (sent * 100 / total) else 0
                    appendLog("Progreso: $pct% ($sent/$total bytes)")
                }
            } else {
                false
            }

            withContext(Dispatchers.Main) {
                if (success) {
                    appendLog("✅ Flasheo completado")
                } else {
                    appendLog("❌ Error durante el flasheo")
                }
            }
        }
    }

    private fun appendLog(text: String) {
        requireActivity().runOnUiThread {
            logView.append("$text\n")
            // Auto-scroll
            val scrollView = view?.findViewById<android.widget.ScrollView>(R.id.fastbootLogScroll)
            scrollView?.fullScroll(View.FOCUS_DOWN)
        }
    }
}
