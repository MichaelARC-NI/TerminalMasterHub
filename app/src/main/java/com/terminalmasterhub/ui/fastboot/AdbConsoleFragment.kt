package com.terminalmasterhub.ui.fastboot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import kotlinx.coroutines.launch

/**
 * Fragmento de consola ADB Shell interactiva.
 *
 * Sesión independiente de Fastboot. Proporciona:
 * - adb devices
 * - adb shell (bash del dispositivo conectado)
 * - adb push/pull de archivos
 * - Comandos de depuración
 *
 * Completamente separado del módulo Fastboot.
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
        appendLog("Comandos: devices, shell, push <local> <remoto>, pull <remoto> [local]")
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { checkAdbDevices() }
    }

    private fun setupListeners() {
        btnDevices.setOnClickListener {
            lifecycleScope.launch { runAdbCommand("devices") }
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

    private fun setupUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        requireContext().registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    appendLog("Dispositivo USB detectado. Verificando modo ADB...")
                    lifecycleScope.launch { checkAdbDevices() }
                }
                android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    adbConnected = false
                    deviceStatusText.text = "Desconectado"
                    deviceStatusText.setTextColor(0xFFFF3333.toInt())
                    appendLog("Dispositivo USB desconectado")
                }
            }
        }
    }

    private suspend fun checkAdbDevices() {
        val devices = usbCore.getAttachedDevices()
        val adbDevice = devices.values.firstOrNull { device ->
            !usbCore.isFastbootMode(device) && !usbCore.isSamsungDownloadMode(device)
        }

        if (adbDevice != null) {
            deviceStatusText.text = "${usbCore.getVendorName(adbDevice)} detectado"
            deviceStatusText.setTextColor(0xFF3399FF.toInt())
            appendLog("Dispositivo: ${adbDevice.productName ?: adbDevice.deviceName}")
            adbConnected = adbClient.connect()
            if (adbConnected) {
                appendLog("ADB conectado")
            } else {
                appendLog("No se pudo establecer conexion ADB (puede requerir autorizacion)")
            }
        } else {
            deviceStatusText.text = "Desconectado"
            deviceStatusText.setTextColor(0xFFFF3333.toInt())
            adbConnected = false
        }
    }

    private suspend fun runAdbCommand(command: String) {
        appendLog("adb $command")

        if (!adbConnected && command != "devices") {
            val success = adbClient.connect()
            if (!success) {
                appendLog("Error: No hay conexion ADB. Verifica el cable OTG.")
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
