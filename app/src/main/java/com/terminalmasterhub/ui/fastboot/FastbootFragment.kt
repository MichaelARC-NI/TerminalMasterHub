package com.terminalmasterhub.ui.fastboot

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.terminalmasterhub.R
import com.terminalmasterhub.TerminalMasterHubApp
import com.terminalmasterhub.core.adb.FastbootClient
import com.terminalmasterhub.core.usb.UsbManagerCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Fragmento de Fastboot puro — sesion independiente solo para bootloader.
 *
 * v1.3.1 Fixes:
 * - Todo I/O USB corre en Dispatchers.IO
 * - BroadcastReceiver registrado con ContextCompat
 * - Try-catch en todas las operaciones USB
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
                    lifecycleScope.launch(Dispatchers.IO) { flashImageSafe(path) }
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
        btnGetvar = view.findViewById(R.id.btnFastbootGetvar)
        btnOem = view.findViewById(R.id.btnFastbootOem)

        setupListeners()
        setupUsbReceiver()
        appendLog("Fastboot listo. Conecta un dispositivo en modo bootloader.")
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch(Dispatchers.IO) { checkFastbootDevices() }
    }

    override fun onPause() {
        super.onPause()
    }

    private fun setupListeners() {
        btnDevices.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    requestUsbPermissionSafe()
                    runFastbootCommand("devices")
                } catch (e: Exception) {
                    showToastSafe("Error: ${e.message}")
                }
            }
        }

        btnReboot.setOnClickListener {
            val options = arrayOf("reboot", "reboot-bootloader", "reboot-recovery", "reboot-edl")
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Reiniciar dispositivo")
                .setItems(options) { _, which ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        try { runFastbootCommand(options[which]) } catch (e: Exception) { showToastSafe("Error: ${e.message}") }
                    }
                }
                .show()
        }

        btnFlash.setOnClickListener { showFlashDialog() }

        btnGetvar.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                try { runFastbootCommand("getvar all") } catch (e: Exception) { showToastSafe("Error: ${e.message}") }
            }
        }

        btnOem.setOnClickListener {
            val oemCmds = arrayOf(
                "oem device-info", "oem unlock", "oem lock",
                "flashing unlock", "flashing lock", "oem edl"
            )
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Comandos OEM")
                .setItems(oemCmds) { _, which ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        try { runFastbootCommand(oemCmds[which]) } catch (e: Exception) { showToastSafe("Error: ${e.message}") }
                    }
                }
                .show()
        }

        btnSend.setOnClickListener { sendFastbootCommand() }
        commandInput.setOnEditorActionListener { _, _, _ -> sendFastbootCommand(); true }
    }

    private fun sendFastbootCommand(): Boolean {
        val input = commandInput.text.toString().trim()
        if (input.isEmpty()) return false
        commandInput.text.clear()
        lifecycleScope.launch(Dispatchers.IO) {
            try { runFastbootCommand(input) } catch (e: Exception) { showToastSafe("Error: ${e.message}") }
        }
        return true
    }

    // ===================== PERMISOS USB (v1.3.1 FIX) =====================

    private fun setupUsbReceiver() {
        try {
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(UsbManagerCore.ACTION_USB_PERMISSION)
            }
            ContextCompat.registerReceiver(
                requireContext(),
                usbReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (e: Exception) {
            appendLog("Error registrando receptor USB: ${e.message}")
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            try {
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        val device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        appendLog("USB detectado: ${device?.productName ?: "Desconocido"}")
                        lifecycleScope.launch(Dispatchers.IO) { checkFastbootDevices() }
                    }

                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        fastbootConnected = false
                        withContext(Dispatchers.Main) {
                            deviceStatusText.text = "Desconectado"
                            deviceStatusText.setTextColor(0xFFFF3333.toInt())
                        }
                        appendLog("USB desconectado")
                    }

                    UsbManagerCore.ACTION_USB_PERMISSION -> {
                        val device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        if (granted && device != null) {
                            appendLog("Permiso USB CONCEDIDO: ${device.productName ?: device.deviceName}")
                            withContext(Dispatchers.Main) {
                                deviceStatusText.text = "${usbCore.getVendorName(device)} (permiso OK)"
                                deviceStatusText.setTextColor(0xFF00FF41.toInt())
                            }
                            lifecycleScope.launch(Dispatchers.IO) { connectToFastbootSafe(device) }
                        } else if (!granted) {
                            appendLog("Permiso USB DENEGADO")
                            withContext(Dispatchers.Main) {
                                deviceStatusText.text = "Permiso denegado"
                                deviceStatusText.setTextColor(0xFFFF3333.toInt())
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                appendLog("Error en receptor USB: ${e.message}")
            }
        }
    }

    private suspend fun requestUsbPermissionSafe() {
        try {
            val devices = usbCore.getAttachedDevices()
            val fbDevice = devices.values.firstOrNull { usbCore.isFastbootMode(it) }
            if (fbDevice != null) {
                val usbManager = requireContext().getSystemService(Context.USB_SERVICE) as UsbManager
                if (!usbManager.hasPermission(fbDevice)) {
                    appendLog("Solicitando permiso USB...")
                    usbCore.requestPermission(fbDevice)
                }
            }
        } catch (e: Exception) {
            appendLog("Error solicitando permiso USB: ${e.message}")
        }
    }

    // ===================== DETECCION (IO Thread) =====================

    private suspend fun checkFastbootDevices() {
        try {
            val devices = usbCore.getAttachedDevices()
            val fbDevice = devices.values.firstOrNull { usbCore.isFastbootMode(it) }

            withContext(Dispatchers.Main) {
                if (fbDevice != null) {
                    val usbManager = requireContext().getSystemService(Context.USB_SERVICE) as UsbManager
                    if (usbManager.hasPermission(fbDevice)) {
                        deviceStatusText.text = "${usbCore.getVendorName(fbDevice)} - permiso OK"
                        deviceStatusText.setTextColor(0xFF00FF41.toInt())
                        appendLog("Fastboot detectado: ${fbDevice.productName ?: fbDevice.deviceName}")
                        appendLog("Permiso OK. Conectando...")
                        lifecycleScope.launch(Dispatchers.IO) { connectToFastbootSafe(fbDevice) }
                    } else {
                        deviceStatusText.text = "${usbCore.getVendorName(fbDevice)} - sin permiso"
                        deviceStatusText.setTextColor(0xFFFFB000.toInt())
                        appendLog("Fastboot detectado. Solicitando permiso USB...")
                        usbCore.requestPermission(fbDevice)
                    }
                } else {
                    deviceStatusText.text = "Desconectado"
                    deviceStatusText.setTextColor(0xFFFF3333.toInt())
                    fastbootConnected = false
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                appendLog("Error: ${e.message}")
            }
        }
    }

    private suspend fun connectToFastbootSafe(device: UsbDevice) {
        try {
            appendLog("Conectando Fastboot a ${device.productName ?: device.deviceName}...")
            fastbootConnected = fastbootClient.connect()
            withContext(Dispatchers.Main) {
                if (fastbootConnected) {
                    appendLog("Fastboot conectado")
                    deviceStatusText.text = "${usbCore.getVendorName(device)} - Fastboot OK"
                    deviceStatusText.setTextColor(0xFF00FF41.toInt())
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val info = fastbootClient.getDeviceInfo()
                            withContext(Dispatchers.Main) {
                                appendLog("Producto: ${info["product"] ?: "N/A"}")
                                appendLog("Bootloader: ${info["version-bootloader"] ?: "N/A"}")
                            }
                        } catch (_: Exception) {}
                    }
                } else {
                    appendLog("No se pudo conectar Fastboot")
                    deviceStatusText.text = "${usbCore.getVendorName(device)} - error"
                    deviceStatusText.setTextColor(0xFFFF3333.toInt())
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                appendLog("Error: ${e.message}")
            }
        }
    }

    // ===================== COMANDOS =====================

    private suspend fun runFastbootCommand(command: String) {
        withContext(Dispatchers.Main) { appendLog("fastboot $command") }

        if (!fastbootConnected) {
            try {
                requestUsbPermissionSafe()
                fastbootConnected = fastbootClient.connect()
                if (!fastbootConnected) {
                    withContext(Dispatchers.Main) {
                        appendLog("Error: No hay dispositivo Fastboot. Verifica modo bootloader.")
                    }
                    return
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { appendLog("Error: ${e.message}") }
                return
            }
        }

        try {
            val result = fastbootClient.sendCommand(command)
            withContext(Dispatchers.Main) {
                if (result != null) appendLog(result) else appendLog("Error: Sin respuesta")
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { appendLog("Error: ${e.message}") }
        }
    }

    private fun showFlashDialog() {
        val partitions = arrayOf("boot", "recovery", "system", "vendor", "dtbo", "vbmeta", "super", "userdata")
        var selectedPartition = "boot"
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Flashear imagen Fastboot")
            .setMessage("Selecciona la particion y luego el archivo .img")
            .setSingleChoiceItems(partitions, 0) { _, which -> selectedPartition = partitions[which] }
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

    private suspend fun flashImageSafe(imagePath: String) {
        try {
            val file = File(imagePath)
            if (!file.exists()) {
                withContext(Dispatchers.Main) { appendLog("Error: Archivo no encontrado") }
                return
            }
            appendLog("Flasheando: $imagePath")
            val success = fastbootClient.flashPartition("boot", file) { sent, total ->
                val pct = if (total > 0) (sent * 100 / total) else 0
                appendLog("Progreso: $pct% ($sent/$total bytes)")
            }
            withContext(Dispatchers.Main) {
                if (success) appendLog("Flasheo completado") else appendLog("Error durante el flasheo")
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { appendLog("Error: ${e.message}") }
        }
    }

    private fun appendLog(text: String) {
        requireActivity().runOnUiThread {
            logView.append("$text\n")
            view?.findViewById<android.widget.ScrollView>(R.id.fastbootLogScroll)?.fullScroll(View.FOCUS_DOWN)
        }
    }

    private suspend fun showToastSafe(msg: String) {
        withContext(Dispatchers.Main) {
            try { Toast.makeText(context, msg, Toast.LENGTH_LONG).show() } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { requireContext().unregisterReceiver(usbReceiver) } catch (_: Exception) {}
    }
}
