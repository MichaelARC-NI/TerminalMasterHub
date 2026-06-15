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
import com.terminalmasterhub.core.adb.FastbootClient
import com.terminalmasterhub.core.file.FileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Fragmento de Fastboot puro — sesión independiente solo para bootloader.
 *
 * Proporciona:
 * - Detección y conexión a dispositivos en modo fastboot
 * - Consola para comandos fastboot manuales
 * - Botones rápidos: devices, reboot, flash, oem, getvar
 * - Flasheo de imágenes de partición
 * - Desbloqueo de bootloader
 *
 * NOTA: ADB tiene su propio fragmento independiente (AdbConsoleFragment).
 */
class FastbootFragment : Fragment() {

    private lateinit var deviceStatusText: TextView
    private lateinit var logView: TextView
    private lateinit var commandInput: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnDevices: Button
    private lateinit var btnReboot: Button
    private lateinit var btnFlash: Button
    private lateinit var btnGetvar: Button
    private lateinit var btnOem: Button

    private val usbCore get() = TerminalMasterHubApp.instance.usbCore
    private val fastbootClient by lazy { FastbootClient(usbCore) }
    private var fastbootConnected = false

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

        // Nuevos botones
        btnGetvar = view.findViewById(R.id.btnFastbootGetvar)
        btnOem = view.findViewById(R.id.btnFastbootOem)

        setupListeners()
        setupUsbReceiver()
        appendLog("Fastboot listo. Conecta un dispositivo en modo bootloader.")
        appendLog("Comandos: devices, flash, reboot, oem unlock, getvar all")
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { checkFastbootDevices() }
    }

    private fun setupListeners() {
        btnDevices.setOnClickListener {
            lifecycleScope.launch { runFastbootCommand("devices") }
        }

        btnReboot.setOnClickListener {
            val options = arrayOf("reboot", "reboot-bootloader", "reboot-recovery", "reboot-edl")
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Reiniciar dispositivo")
                .setItems(options) { _, which ->
                    lifecycleScope.launch { runFastbootCommand(options[which]) }
                }
                .show()
        }

        btnFlash.setOnClickListener {
            showFlashDialog()
        }

        btnGetvar.setOnClickListener {
            lifecycleScope.launch { runFastbootCommand("getvar all") }
        }

        btnOem.setOnClickListener {
            val oemCmds = arrayOf(
                "oem device-info",
                "oem unlock",
                "oem lock",
                "flashing unlock",
                "flashing lock",
                "oem edl"
            )
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Comandos OEM")
                .setItems(oemCmds) { _, which ->
                    lifecycleScope.launch { runFastbootCommand(oemCmds[which]) }
                }
                .show()
        }

        btnSend.setOnClickListener {
            sendFastbootCommand()
        }

        commandInput.setOnEditorActionListener { _, _, _ ->
            sendFastbootCommand(); true
        }
    }

    private fun sendFastbootCommand(): Boolean {
        val input = commandInput.text.toString().trim()
        if (input.isEmpty()) return false
        commandInput.text.clear()
        lifecycleScope.launch { runFastbootCommand(input) }
        return true
    }

    private fun setupUsbReceiver() {
        val filter = android.content.IntentFilter().apply {
            addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        requireContext().registerReceiver(usbReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
    }

    private val usbReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            when (intent.action) {
                android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    appendLog("Dispositivo USB detectado. Verificando modo Fastboot...")
                    lifecycleScope.launch { checkFastbootDevices() }
                }
                android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    fastbootConnected = false
                    deviceStatusText.text = "Ningún dispositivo conectado"
                    deviceStatusText.setTextColor(0xFFFF3333.toInt())
                    appendLog("Dispositivo USB desconectado")
                }
            }
        }
    }

    private suspend fun checkFastbootDevices() {
        val devices = usbCore.getAttachedDevices()
        val fastbootDevice = devices.values.firstOrNull { device ->
            usbCore.isFastbootMode(device)
        }

        if (fastbootDevice != null) {
            deviceStatusText.text = "${usbCore.getVendorName(fastbootDevice)} - Fastboot"
            deviceStatusText.setTextColor(0xFF00FF41.toInt())
            appendLog("Dispositivo Fastboot detectado: ${fastbootDevice.productName ?: "Desconocido"}")
            fastbootConnected = fastbootClient.connect()
            if (fastbootConnected) {
                appendLog("✅ Fastboot conectado")
                // Mostrar info básica
                val info = fastbootClient.getDeviceInfo()
                appendLog("Producto: ${info["product"] ?: "N/A"}")
                appendLog("Bootloader: ${info["version-bootloader"] ?: "N/A"}")
            } else {
                appendLog("⚠️  No se pudo conectar en modo Fastboot")
            }
        } else {
            deviceStatusText.text = "Ningún dispositivo conectado"
            deviceStatusText.setTextColor(0xFFFF3333.toInt())
            fastbootConnected = false
        }
    }

    private suspend fun runFastbootCommand(command: String) {
        appendLog("fastboot $command")

        if (!fastbootConnected) {
            val success = fastbootClient.connect()
            if (!success) {
                appendLog("Error: No hay dispositivo Fastboot conectado.")
                appendLog("Verifica que el dispositivo esté en modo bootloader.")
                return
            }
            fastbootConnected = true
        }

        val result = fastbootClient.sendCommand(command)
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
            .setTitle("Flashear imagen Fastboot")
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
            val success = fastbootClient.flashPartition("boot", file) { sent, total ->
                val pct = if (total > 0) (sent * 100 / total) else 0
                appendLog("Progreso: $pct% ($sent/$total bytes)")
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
            val scrollView = view?.findViewById<android.widget.ScrollView>(R.id.fastbootLogScroll)
            scrollView?.fullScroll(View.FOCUS_DOWN)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { requireContext().unregisterReceiver(usbReceiver) } catch (_: Exception) {}
    }
}
