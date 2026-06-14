package com.terminalmasterhub.ui.samsung

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.terminalmasterhub.R
import com.terminalmasterhub.TerminalMasterHubApp
import com.terminalmasterhub.core.file.FileManager
import com.terminalmasterhub.core.odin.OdinProtocol
import com.terminalmasterhub.core.usb.UsbBroadcastReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Fragmento de la sesión Samsung Odin3.
 *
 * Proporciona interfaz para flashear firmware Samsung
 * en modo Download Mode vía protocolo Odin3.
 *
 * Características:
 * - Detección de Samsung en Download Mode
 * - Selección de archivos .tar y .tar.md5
 * - Verificación MD5 integrada
 * - Opciones de auto-reboot y wipe data
 * - Monitoreo de progreso en tiempo real
 */
class SamsungOdinFragment : Fragment() {

    private lateinit var deviceStatusText: TextView
    private lateinit var filePathText: TextView
    private lateinit var btnBrowse: Button
    private lateinit var btnFlash: Button
    private lateinit var btnReboot: Button
    private lateinit var logView: TextView
    private lateinit var chkAutoReboot: CheckBox
    private lateinit var chkMd5: CheckBox
    private lateinit var chkWipe: CheckBox

    private val usbCore get() = TerminalMasterHubApp.instance.usbCore
    private val odinProtocol by lazy {
        OdinProtocol(usbCore).apply {
            onLog = { logMsg -> appendLog(logMsg) }
        }
    }

    private var selectedTarFile: File? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            uri?.let {
                val path = FileManager.getStoragePath() + "/" +
                        (uri.path?.substringAfter(":") ?: "")
                val file = File(path)
                if (file.exists() && (file.name.endsWith(".tar") || file.name.endsWith(".tar.md5"))) {
                    selectedTarFile = file
                    filePathText.text = file.name
                    appendLog("Archivo seleccionado: ${file.name} (${FileManager.getFileSizeString(file.length())})")
                } else {
                    appendLog("Error: Archivo no válido. Selecciona .tar o .tar.md5")
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_samsung, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        deviceStatusText = view.findViewById(R.id.samsungDeviceStatus)
        filePathText = view.findViewById(R.id.samsungFilePath)
        btnBrowse = view.findViewById(R.id.btnSamsungBrowse)
        btnFlash = view.findViewById(R.id.btnSamsungFlash)
        btnReboot = view.findViewById(R.id.btnSamsungReboot)
        logView = view.findViewById(R.id.samsungLog)
        chkAutoReboot = view.findViewById(R.id.chkSamsungAutoReboot)
        chkMd5 = view.findViewById(R.id.chkSamsungMd5Check)
        chkWipe = view.findViewById(R.id.chkSamsungUserdata)

        setupListeners()
        setupUsbReceiver()
    }

    override fun onResume() {
        super.onResume()
        checkSamsungDevice()
    }

    private fun setupListeners() {
        btnBrowse.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            filePickerLauncher.launch(intent)
        }

        btnFlash.setOnClickListener {
            val tarFile = selectedTarFile
            if (tarFile == null) {
                appendLog("Selecciona un archivo .tar o .tar.md5 primero")
                return@setOnClickListener
            }
            if (!odinProtocol.isDeviceConnected) {
                appendLog("Conecta un Samsung en Download Mode primero")
                return@setOnClickListener
            }
            lifecycleScope.launch {
                startFlashing(tarFile)
            }
        }

        btnReboot.setOnClickListener {
            if (!odinProtocol.isDeviceConnected) {
                appendLog("No hay dispositivo conectado")
                return@setOnClickListener
            }
            lifecycleScope.launch {
                appendLog("Enviando comando reboot...")
                odinProtocol.reboot()
                appendLog("Comando reboot enviado")
            }
        }
    }

    private fun setupUsbReceiver() {
        UsbBroadcastReceiver.onDeviceDetached = { _ ->
            deviceStatusText.text = getString(R.string.samsung_no_device)
            deviceStatusText.setTextColor(0xFFFF3333.toInt())
            appendLog("Samsung desconectado")
        }

        UsbBroadcastReceiver.onPermissionResult = { granted, device ->
            if (granted && device != null && usbCore.isSamsungDownloadMode(device)) {
                lifecycleScope.launch {
                    odinProtocol.connect()
                    deviceStatusText.text = "Samsung ${usbCore.getVendorName(device)} - Download Mode"
                    deviceStatusText.setTextColor(0xFFFFB000.toInt())
                    appendLog("✓ Conectado a Samsung Download Mode")
                }
            }
        }
    }

    private fun checkSamsungDevice() {
        val devices = usbCore.getAttachedDevices()
        for ((_, device) in devices) {
            if (usbCore.isSamsungDownloadMode(device)) {
                deviceStatusText.text = "Samsung detectado - Conectando..."
                deviceStatusText.setTextColor(0xFFFFB000.toInt())
                lifecycleScope.launch {
                    val connected = odinProtocol.connect()
                    if (connected) {
                        deviceStatusText.text = "Samsung ${usbCore.getVendorName(device)} - Download Mode"
                        deviceStatusText.setTextColor(0xFF00FF41.toInt())
                        appendLog("✓ Conectado a Samsung Download Mode")

                        // Leer tabla PIT
                        val pit = odinProtocol.readPit()
                        if (pit != null) {
                            appendLog("Tabla de particiones (PIT): ${pit.size} entradas")
                        }
                    }
                }
            }
        }
    }

    private suspend fun startFlashing(tarFile: File) {
        btnFlash.isEnabled = false
        btnFlash.text = "FLASHEANDO..."

        appendLog("\n══════════════ INICIANDO FLASHEO ══════════════")
        appendLog("Archivo: ${tarFile.name}")
        appendLog("Tamaño: ${FileManager.getFileSizeString(tarFile.length())}")
        appendLog("Auto Reboot: ${chkAutoReboot.isChecked}")
        appendLog("Verificar MD5: ${chkMd5.isChecked}")
        appendLog("Wipe Userdata: ${chkWipe.isChecked}")
        appendLog("")

        withContext(Dispatchers.IO) {
            val success = odinProtocol.flashTar(
                tarFile = tarFile,
                autoReboot = chkAutoReboot.isChecked,
                wipeData = chkWipe.isChecked,
                md5Check = chkMd5.isChecked,
                onProgress = { fileIndex, totalFiles, bytesRead, bytesTotal ->
                    val pct = if (bytesTotal > 0) (bytesRead * 100 / bytesTotal) else 0
                    appendLog("  [$fileIndex/$totalFiles] $pct%")
                }
            )

            withContext(Dispatchers.Main) {
                btnFlash.isEnabled = true
                btnFlash.text = getString(R.string.samsung_flash)

                if (success) {
                    appendLog("\n✅ FLASHEO COMPLETADO EXITOSAMENTE")
                } else {
                    appendLog("\n❌ ERROR DURANTE EL FLASHEO")
                }
            }
        }
    }

    private fun appendLog(text: String) {
        requireActivity().runOnUiThread {
            logView.append("$text\n")
            val scrollView = view?.findViewById<android.widget.ScrollView>(R.id.samsungLogScroll)
            scrollView?.fullScroll(View.FOCUS_DOWN)
        }
    }
}
