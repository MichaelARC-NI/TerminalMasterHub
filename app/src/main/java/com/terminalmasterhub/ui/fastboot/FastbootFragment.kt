package com.terminalmasterhub.ui.fastboot

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
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
import com.terminalmasterhub.core.usb.UsbBroadcastReceiver
import com.terminalmasterhub.core.usb.UsbManagerCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Fragmento de Fastboot puro — sesion independiente solo para bootloader.
 *
 * Maneja el flujo completo de permisos USB:
 * 1. Detecta dispositivos en modo fastboot
 * 2. Solicita permiso al usuario via PendingIntent (FLAG_MUTABLE)
 * 3. Espera la respuesta del BroadcastReceiver
 * 4. Si concedido, inicia conexion Fastboot + consulta de variables
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

    override fun onPause() {
        super.onPause()
    }

    private fun setupListeners() {
        btnDevices.setOnClickListener {
            lifecycleScope.launch {
                requestUsbPermission()
                runFastbootCommand("devices")
            }
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

    // ===================== PERMISOS USB =====================

    private fun setupUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManagerCore.ACTION_USB_PERMISSION)
        }
        requireContext().registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    appendLog("Dispositivo USB detectado: ${device?.productName ?: "Desconocido"}")
                    lifecycleScope.launch { checkFastbootDevices() }
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    fastbootConnected = false
                    deviceStatusText.text = "Ningun dispositivo conectado"
                    deviceStatusText.setTextColor(0xFFFF3333.toInt())
                    appendLog("Dispositivo USB desconectado")
                }

                UsbManagerCore.ACTION_USB_PERMISSION -> {
                    val device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null) {
                        appendLog("Permiso USB CONCEDIDO para: ${device.productName ?: device.deviceName}")
                        deviceStatusText.text = "${usbCore.getVendorName(device)} (permiso OK)"
                        deviceStatusText.setTextColor(0xFF00FF41.toInt())
                        lifecycleScope.launch { connectToFastboot(device) }
                    } else if (!granted) {
                        appendLog("Permiso USB DENEGADO. No se puede acceder al dispositivo.")
                        deviceStatusText.text = "Permiso denegado"
                        deviceStatusText.setTextColor(0xFFFF3333.toInt())
                    }
                }
            }
        }
    }

    private suspend fun requestUsbPermission() {
        val devices = usbCore.getAttachedDevices()
        val fastbootDevice = devices.values.firstOrNull { device ->
            usbCore.isFastbootMode(device)
        }
        if (fastbootDevice != null) {
            val usbManager = requireContext().getSystemService(Context.USB_SERVICE) as UsbManager
            if (!usbManager.hasPermission(fastbootDevice)) {
                appendLog("Solicitando permiso USB para: ${fastbootDevice.productName ?: fastbootDevice.deviceName}")
                usbCore.requestPermission(fastbootDevice)
            }
        }
    }

    // ===================== DETECCION DE DISPOSITIVOS =====================

    private suspend fun checkFastbootDevices() {
        val devices = usbCore.getAttachedDevices()
        val fastbootDevice = devices.values.firstOrNull { device ->
            usbCore.isFastbootMode(device)
        }

        if (fastbootDevice != null) {
            val usbManager = requireContext().getSystemService(Context.USB_SERVICE) as UsbManager
            if (usbManager.hasPermission(fastbootDevice)) {
                deviceStatusText.text = "${usbCore.getVendorName(fastbootDevice)} - permiso OK"
                deviceStatusText.setTextColor(0xFF00FF41.toInt())
                appendLog("Dispositivo Fastboot: ${fastbootDevice.productName ?: fastbootDevice.deviceName}")
                appendLog("Permiso USB ya concedido. Conectando...")
                connectToFastboot(fastbootDevice)
            } else {
                deviceStatusText.text = "${usbCore.getVendorName(fastbootDevice)} - sin permiso"
                deviceStatusText.setTextColor(0xFFFFB000.toInt())
                appendLog("Dispositivo Fastboot detectado: ${fastbootDevice.productName ?: fastbootDevice.deviceName}")
                appendLog("Solicitando permiso USB al usuario...")
                usbCore.requestPermission(fastbootDevice)
            }
        } else {
            deviceStatusText.text = "Ningun dispositivo conectado"
            deviceStatusText.setTextColor(0xFFFF3333.toInt())
            fastbootConnected = false
        }
    }

    private suspend fun connectToFastboot(device: UsbDevice) {
        appendLog("Iniciando conexion Fastboot con ${device.productName ?: device.deviceName}...")
        fastbootConnected = fastbootClient.connect()
        if (fastbootConnected) {
            appendLog("Fastboot conectado exitosamente")
            deviceStatusText.text = "${usbCore.getVendorName(device)} - Fastboot OK"
            deviceStatusText.setTextColor(0xFF00FF41.toInt())
            // Mostrar info basica del dispositivo
            val info = fastbootClient.getDeviceInfo()
            appendLog("Producto: ${info["product"] ?: "N/A"}")
            appendLog("Bootloader: ${info["version-bootloader"] ?: "N/A"}")
        } else {
            appendLog("No se pudo conectar en modo Fastboot.")
            deviceStatusText.text = "${usbCore.getVendorName(device)} - error conexion"
            deviceStatusText.setTextColor(0xFFFF3333.toInt())
        }
    }

    // ===================== COMANDOS FASTBOOT =====================

    private suspend fun runFastbootCommand(command: String) {
        appendLog("fastboot $command")

        if (!fastbootConnected) {
            requestUsbPermission()
            val success = fastbootClient.connect()
            if (!success) {
                appendLog("Error: No hay dispositivo Fastboot conectado.")
                appendLog("Verifica que el dispositivo este en modo bootloader.")
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
            .setMessage("Selecciona la particion y luego el archivo .img")
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
                    appendLog("Flasheo completado")
                } else {
                    appendLog("Error durante el flasheo")
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
