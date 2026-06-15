package com.terminalmasterhub.ui.fastboot

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
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.terminalmasterhub.R
import com.terminalmasterhub.TerminalMasterHubApp
import com.terminalmasterhub.core.adb.AdbClient
import com.terminalmasterhub.core.usb.UsbManagerCore
import kotlinx.coroutines.launch

/**
 * Fragmento de consola ADB Shell interactiva.
 *
 * Maneja el flujo completo de permisos USB:
 * 1. Detecta dispositivos conectados
 * 2. Solicita permiso al usuario via PendingIntent (FLAG_MUTABLE)
 * 3. Espera la respuesta del BroadcastReceiver
 * 4. Si concedido, inicia conexion ADB + handshake
 */
class AdbConsoleFragment : Fragment() {

    private lateinit var deviceStatusText: TextView
    private lateinit var logView: TextView
    private lateinit var commandInput: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnDevices: Button
    private lateinit var btnShell: Button
    private lateinit var btnPush: Button
    private lateinit var btnPull: Button

    private val usbCore get() = TerminalMasterHubApp.instance.usbCore
    private val adbClient by lazy { AdbClient(usbCore) }
    private var adbConnected = false
    private var pendingDevice: UsbDevice? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_adb_console, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        deviceStatusText = view.findViewById(R.id.adbDeviceStatus)
        logView = view.findViewById(R.id.adbLog)
        commandInput = view.findViewById(R.id.adbInput)
        btnSend = view.findViewById(R.id.btnAdbSend)
        btnDevices = view.findViewById(R.id.btnAdbDevices)
        btnShell = view.findViewById(R.id.btnAdbShell)
        btnPush = view.findViewById(R.id.btnAdbPush)
        btnPull = view.findViewById(R.id.btnAdbPull)

        setupListeners()
        setupUsbReceiver()
        appendLog("ADB Console listo. Conecta un dispositivo USB en modo debug.")
        appendLog("Comandos: devices, shell, push, pull")
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { checkAdbDevices() }
    }

    private fun setupListeners() {
        btnDevices.setOnClickListener {
            lifecycleScope.launch {
                // Al presionar "devices", forzar solicitud de permiso si hay dispositivo
                requestUsbPermission()
                runAdbCommand("devices")
            }
        }

        btnShell.setOnClickListener {
            val input = commandInput.text.toString().trim()
            val cmd = if (input.isEmpty()) "shell" else "shell $input"
            lifecycleScope.launch { runAdbCommand(cmd) }
        }

        btnPush.setOnClickListener {
            commandInput.setText("push ")
            commandInput.setSelection(5)
        }

        btnPull.setOnClickListener {
            commandInput.setText("pull ")
            commandInput.setSelection(5)
        }

        btnSend.setOnClickListener {
            sendAdbCommand()
        }

        commandInput.setOnEditorActionListener { _, _, _ ->
            sendAdbCommand(); true
        }
    }

    private fun sendAdbCommand(): Boolean {
        val input = commandInput.text.toString().trim()
        if (input.isEmpty()) return false
        commandInput.text.clear()
        lifecycleScope.launch { runAdbCommand(input) }
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
                    lifecycleScope.launch { checkAdbDevices() }
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    adbConnected = false
                    pendingDevice = null
                    deviceStatusText.text = "Desconectado"
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
                        // Proceder con la conexion ADB
                        lifecycleScope.launch {
                            connectToDevice(device)
                        }
                    } else if (!granted) {
                        appendLog("Permiso USB DENEGADO. No se puede acceder al dispositivo.")
                        deviceStatusText.text = "Permiso denegado"
                        deviceStatusText.setTextColor(0xFFFF3333.toInt())
                    }
                }
            }
        }
    }

    /**
     * Solicita permiso USB para el primer dispositivo ADB detectado.
     */
    private suspend fun requestUsbPermission() {
        val devices = usbCore.getAttachedDevices()
        val adbDevice = devices.values.firstOrNull { device ->
            !usbCore.isFastbootMode(device) && !usbCore.isSamsungDownloadMode(device)
        }
        if (adbDevice != null) {
            val usbManager = requireContext().getSystemService(Context.USB_SERVICE) as UsbManager
            if (!usbManager.hasPermission(adbDevice)) {
                appendLog("Solicitando permiso USB para: ${adbDevice.productName ?: adbDevice.deviceName}")
                usbCore.requestPermission(adbDevice)
            }
        }
    }

    // ===================== DETECCION DE DISPOSITIVOS =====================

    private suspend fun checkAdbDevices() {
        val devices = usbCore.getAttachedDevices()
        val adbDevice = devices.values.firstOrNull { device ->
            !usbCore.isFastbootMode(device) && !usbCore.isSamsungDownloadMode(device)
        }

        if (adbDevice != null) {
            val usbManager = requireContext().getSystemService(Context.USB_SERVICE) as UsbManager
            if (usbManager.hasPermission(adbDevice)) {
                deviceStatusText.text = "${usbCore.getVendorName(adbDevice)} - permiso OK"
                deviceStatusText.setTextColor(0xFF00FF41.toInt())
                appendLog("Dispositivo: ${adbDevice.productName ?: adbDevice.deviceName}")
                appendLog("Permiso USB ya concedido. Conectando...")
                connectToDevice(adbDevice)
            } else {
                deviceStatusText.text = "${usbCore.getVendorName(adbDevice)} - sin permiso"
                deviceStatusText.setTextColor(0xFFFFB000.toInt())
                appendLog("Dispositivo detectado: ${adbDevice.productName ?: adbDevice.deviceName}")
                appendLog("Solicitando permiso USB al usuario...")
                pendingDevice = adbDevice
                usbCore.requestPermission(adbDevice)
            }
        } else {
            deviceStatusText.text = "Desconectado"
            deviceStatusText.setTextColor(0xFFFF3333.toInt())
            adbConnected = false
            pendingDevice = null
        }
    }

    private suspend fun connectToDevice(device: UsbDevice) {
        appendLog("Iniciando conexion ADB con ${device.productName ?: device.deviceName}...")
        adbConnected = adbClient.connect()
        if (adbConnected) {
            appendLog("ADB conectado exitosamente")
            deviceStatusText.text = "${usbCore.getVendorName(device)} - ADB OK"
            deviceStatusText.setTextColor(0xFF3399FF.toInt())
            // Obtener informacion basica del dispositivo
            val info = adbClient.getDeviceInfo()
            if (info.isNotEmpty()) {
                appendLog("Modelo: ${info["model"] ?: "N/A"}")
                appendLog("Android: ${info["android_version"] ?: "N/A"}")
            }
        } else {
            appendLog("No se pudo establecer conexion ADB.")
            appendLog("Posibles causas: dispositivo no autorizado, o requiere RSA")
            deviceStatusText.text = "${usbCore.getVendorName(device)} - error conexion"
            deviceStatusText.setTextColor(0xFFFF3333.toInt())
        }
    }

    // ===================== COMANDOS ADB =====================

    private suspend fun runAdbCommand(command: String) {
        appendLog("adb $command")

        if (!adbConnected && command != "devices") {
            // Intentar conectar primero
            requestUsbPermission()
            val success = adbClient.connect()
            if (!success) {
                appendLog("Error: No hay conexion ADB. Verifica el permiso USB y el cable OTG.")
                return
            }
            adbConnected = true
        }

        val result: String? = when {
            command == "devices" -> {
                val devices = usbCore.getAttachedDevices()
                if (devices.isEmpty()) {
                    "List of devices attached\n\nNingun dispositivo"
                } else {
                    "List of devices attached\n" + devices.entries.joinToString("\n") { (_, d) ->
                        "${d.deviceName}\t${usbCore.getVendorName(d)}"
                    }
                }
            }
            command.startsWith("shell ") -> {
                val shellCmd = command.removePrefix("shell ")
                adbClient.shellCommand(shellCmd)
            }
            command == "shell" -> {
                adbClient.shellCommand("")
            }
            command.startsWith("push ") -> {
                val parts = command.removePrefix("push ").trim().split("\\s+".toRegex())
                if (parts.size >= 2) {
                    val local = parts[0]
                    val remote = parts[1]
                    val success = adbClient.pushFile(local, remote)
                    if (success) "Archivo enviado: $local -> $remote" else "Error al enviar archivo"
                } else {
                    "Uso: push <local> <remoto>"
                }
            }
            command.startsWith("pull ") -> {
                val parts = command.removePrefix("pull ").trim().split("\\s+".toRegex())
                val remote = parts[0]
                val local = if (parts.size >= 2) parts[1] else "/storage/emulated/0/${remote.substringAfterLast("/")}"
                adbClient.pullFile(remote, local)
            }
            else -> adbClient.shellCommand(command)
        }

        if (result != null) {
            appendLog(result)
        } else {
            appendLog("Error: Sin respuesta")
        }
    }

    private fun appendLog(text: String) {
        requireActivity().runOnUiThread {
            logView.append("$text\n")
            val scrollView = view?.findViewById<android.widget.ScrollView>(R.id.adbLogScroll)
            scrollView?.fullScroll(View.FOCUS_DOWN)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { requireContext().unregisterReceiver(usbReceiver) } catch (_: Exception) {}
    }
}
