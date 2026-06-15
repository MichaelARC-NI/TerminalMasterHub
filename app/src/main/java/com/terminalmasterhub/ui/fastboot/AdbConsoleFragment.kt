package com.terminalmasterhub.ui.fastboot

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
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.terminalmasterhub.R
import com.terminalmasterhub.TerminalMasterHubApp
import com.terminalmasterhub.core.adb.AdbClient
import com.terminalmasterhub.core.usb.UsbManagerCore
import com.terminalmasterhub.core.wireless.AdbCryptoManager
import com.terminalmasterhub.core.wireless.WirelessAdbDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fragmento de consola ADB (USB + Inalambrico).
 *
 * Flujo de permisos USB reparado v1.3.1:
 * 1. Hilos en segundo plano: todo I/O USB via Dispatchers.IO
 * 2. Receptor registrado con ContextCompat para compatibilidad API
 * 3. Try-catch en todas las operaciones USB
 *
 * Nueva funcionalidad Wireless ADB:
 * - Boton WiFi para abrir dialogo de conexion inalambrica
 * - Modo Pairing (Android 11+) y Modo Conectar
 * - Persistencia de IPs recientes
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
    private lateinit var btnWireless: Button

    private val usbCore get() = TerminalMasterHubApp.instance.usbCore
    private val adbClient by lazy { AdbClient(usbCore) }
    private var adbConnected = false
    private var wirelessConnected = false
    private var wirelessClient: com.terminalmasterhub.core.wireless.WirelessAdbClient? = null

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
        btnWireless = view.findViewById(R.id.btnAdbWireless)

        setupListeners()
        setupUsbReceiver()
        autoGenerateKeys()
        appendLog("ADB Console listo. USB OTG + Inalambrico.")
        appendLog("Comandos: devices, shell, push, pull | Boton WiFi = ADB inalambrico")
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch(Dispatchers.IO) { checkAdbDevices() }
    }

    override fun onPause() {
        super.onPause()
    }

    private fun setupListeners() {
        btnDevices.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    requestUsbPermissionSafe()
                    runAdbCommand("devices")
                } catch (e: Exception) {
                    showToastSafe("Error: ${e.message}")
                }
            }
        }

        btnShell.setOnClickListener {
            val input = commandInput.text.toString().trim()
            val cmd = if (input.isEmpty()) "shell" else "shell $input"
            lifecycleScope.launch(Dispatchers.IO) {
                try { runAdbCommand(cmd) } catch (e: Exception) { showToastSafe("Error: ${e.message}") }
            }
        }

        btnPush.setOnClickListener { commandInput.setText("push "); commandInput.setSelection(5) }
        btnPull.setOnClickListener { commandInput.setText("pull "); commandInput.setSelection(5) }

        btnWireless.setOnClickListener {
            openWirelessDialog()
        }

        btnSend.setOnClickListener { sendAdbCommand() }
        commandInput.setOnEditorActionListener { _, _, _ -> sendAdbCommand(); true }
    }

    private fun sendAdbCommand(): Boolean {
        val input = commandInput.text.toString().trim()
        if (input.isEmpty()) return false
        commandInput.text.clear()
        lifecycleScope.launch(Dispatchers.IO) {
            try { runAdbCommand(input) } catch (e: Exception) { showToastSafe("Error: ${e.message}") }
        }
        return true
    }

    // ===================== WIRELESS ADB =====================

    private fun openWirelessDialog() {
        try {
            WirelessAdbDialog(
                activity = requireActivity(),
                lifecycleScope = lifecycleScope,
                onConnected = { ip, port ->
                    wirelessConnected = true
                    wirelessClient = com.terminalmasterhub.core.wireless.WirelessAdbClient(requireContext())
                    deviceStatusText.text = "WiFi: $ip:$port"
                    deviceStatusText.setTextColor(0xFF3399FF.toInt())
                    appendLog("ADB Inalambrico: Conectado a $ip:$port")
                },
                onLog = { msg -> appendLog(msg) }
            ).show()
        } catch (e: Exception) {
            appendLog("Error al abrir dialogo Wireless: ${e.message}")
        }
    }

    private fun autoGenerateKeys() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (!AdbCryptoManager.hasKeys(requireContext())) {
                    val result = AdbCryptoManager.generateKeys(requireContext())
                    if (result.isSuccess) {
                        withContext(Dispatchers.Main) {
                            appendLog("Claves RSA generadas automaticamente para ADB inalambrico")
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    // ===================== PERMISOS USB (v1.3.1 FIX) =====================

    private fun setupUsbReceiver() {
        try {
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(UsbManagerCore.ACTION_USB_PERMISSION)
            }
            // Fix: usar ContextCompat.registerReceiver para compatibilidad
            // con todas las versiones de Android (13/14+ RECEIVER_NOT_EXPORTED)
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
                        lifecycleScope.launch(Dispatchers.IO) { checkAdbDevices() }
                    }

                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        adbConnected = false
                        requireActivity().runOnUiThread {
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
                            requireActivity().runOnUiThread {
                                deviceStatusText.text = "${usbCore.getVendorName(device)} (permiso OK)"
                                deviceStatusText.setTextColor(0xFF00FF41.toInt())
                            }
                            lifecycleScope.launch(Dispatchers.IO) { connectToDeviceSafe(device) }
                        } else if (!granted) {
                            appendLog("Permiso USB DENEGADO")
                            requireActivity().runOnUiThread {
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
            val adbDevice = devices.values.firstOrNull { device ->
                !usbCore.isFastbootMode(device) && !usbCore.isSamsungDownloadMode(device)
            }
            if (adbDevice != null) {
                val usbManager = requireContext().getSystemService(Context.USB_SERVICE) as UsbManager
                if (!usbManager.hasPermission(adbDevice)) {
                    appendLog("Solicitando permiso USB...")
                    // El PendingIntent debe ser MUTABLE para Android 12+
                    usbCore.requestPermission(adbDevice)
                }
            }
        } catch (e: Exception) {
            appendLog("Error solicitando permiso USB: ${e.message}")
        }
    }

    // ===================== DETECCION DE DISPOSITIVOS (IO Thread) =====================

    private suspend fun checkAdbDevices() {
        try {
            val devices = usbCore.getAttachedDevices()
            val adbDevice = devices.values.firstOrNull { device ->
                !usbCore.isFastbootMode(device) && !usbCore.isSamsungDownloadMode(device)
            }

            withContext(Dispatchers.Main) {
                if (adbDevice != null) {
                    val usbManager = requireContext().getSystemService(Context.USB_SERVICE) as UsbManager
                    if (usbManager.hasPermission(adbDevice)) {
                        deviceStatusText.text = "${usbCore.getVendorName(adbDevice)} - permiso OK"
                        deviceStatusText.setTextColor(0xFF00FF41.toInt())
                        appendLog("Dispositivo: ${adbDevice.productName ?: adbDevice.deviceName}")
                        appendLog("Permiso OK. Conectando...")
                        lifecycleScope.launch(Dispatchers.IO) { connectToDeviceSafe(adbDevice) }
                    } else {
                        deviceStatusText.text = "${usbCore.getVendorName(adbDevice)} - sin permiso"
                        deviceStatusText.setTextColor(0xFFFFB000.toInt())
                        appendLog("Dispositivo detectado. Solicitando permiso USB...")
                        usbCore.requestPermission(adbDevice)
                    }
                } else {
                    deviceStatusText.text = "Desconectado"
                    deviceStatusText.setTextColor(0xFFFF3333.toInt())
                    adbConnected = false
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                appendLog("Error detectando dispositivos: ${e.message}")
            }
        }
    }

    private suspend fun connectToDeviceSafe(device: UsbDevice) {
        try {
            appendLog("Conectando ADB a ${device.productName ?: device.deviceName}...")
            val connected = adbClient.connect()
            withContext(Dispatchers.Main) {
                if (connected) {
                    adbConnected = true
                    appendLog("ADB conectado exitosamente")
                    deviceStatusText.text = "${usbCore.getVendorName(device)} - ADB OK"
                    deviceStatusText.setTextColor(0xFF3399FF.toInt())
                    // Obtener info del dispositivo en IO
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val info = adbClient.getDeviceInfo()
                            withContext(Dispatchers.Main) {
                                appendLog("Modelo: ${info["model"] ?: "N/A"}")
                                appendLog("Android: ${info["android_version"] ?: "N/A"}")
                            }
                        } catch (_: Exception) {}
                    }
                } else {
                    appendLog("No se pudo conectar ADB.")
                    appendLog("Posibles causas: dispositivo no autorizado o requiere RSA")
                    deviceStatusText.text = "${usbCore.getVendorName(device)} - error"
                    deviceStatusText.setTextColor(0xFFFF3333.toInt())
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                appendLog("Error de conexion: ${e.message}")
                deviceStatusText.text = "Error de conexion"
                deviceStatusText.setTextColor(0xFFFF3333.toInt())
            }
        }
    }

    // ===================== COMANDOS ADB =====================

    private suspend fun runAdbCommand(command: String) {
        withContext(Dispatchers.Main) { appendLog("adb $command") }

        if (!adbConnected && !wirelessConnected && command != "devices") {
            try {
                requestUsbPermissionSafe()
                val success = adbClient.connect()
                if (!success) {
                    withContext(Dispatchers.Main) {
                        appendLog("Error: No hay conexion ADB. Verifica USB OTG o usa WiFi.")
                    }
                    return
                }
                adbConnected = true
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendLog("Error: ${e.message}")
                }
                return
            }
        }

        try {
            val result: String? = when {
                command == "devices" -> {
                    val usbDevices = usbCore.getAttachedDevices()
                    val wirelessInfo = if (wirelessConnected) {
                        "\\n${wirelessClient?.getConnectedInfo() ?: ""} (inalambrico)"
                    } else ""
                    if (usbDevices.isEmpty() && !wirelessConnected) {
                        "List of devices attached\\n\\nNingun dispositivo"
                    } else {
                        val usbList = if (usbDevices.isNotEmpty()) {
                            usbDevices.entries.joinToString("\\n") { (_, d) ->
                                "${d.deviceName}\\t${usbCore.getVendorName(d)} (USB)"
                            }
                        } else ""
                        "List of devices attached\\n$usbList$wirelessInfo"
                    }
                }
                command.startsWith("shell ") -> {
                    val shellCmd = command.removePrefix("shell ")
                    adbClient.shellCommand(shellCmd)
                }
                command == "shell" -> adbClient.shellCommand("")
                command.startsWith("push ") -> {
                    val parts = command.removePrefix("push ").trim().split("\\s+".toRegex())
                    if (parts.size >= 2) {
                        val success = adbClient.pushFile(parts[0], parts[1])
                        if (success) "Archivo enviado: ${parts[0]} -> ${parts[1]}" else "Error al enviar archivo"
                    } else "Uso: push <local> <remoto>"
                }
                command.startsWith("pull ") -> {
                    val parts = command.removePrefix("pull ").trim().split("\\s+".toRegex())
                    val remote = parts[0]
                    val local = if (parts.size >= 2) parts[1] else "/storage/emulated/0/${remote.substringAfterLast("/")}"
                    adbClient.pullFile(remote, local)
                }
                else -> adbClient.shellCommand(command)
            }

            withContext(Dispatchers.Main) {
                if (result != null) appendLog(result) else appendLog("Error: Sin respuesta")
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                appendLog("Error: ${e.message}")
            }
        }
    }

    private fun appendLog(text: String) {
        requireActivity().runOnUiThread {
            logView.append("$text\\n")
            val scrollView = view?.findViewById<android.widget.ScrollView>(R.id.adbLogScroll)
            scrollView?.fullScroll(View.FOCUS_DOWN)
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
