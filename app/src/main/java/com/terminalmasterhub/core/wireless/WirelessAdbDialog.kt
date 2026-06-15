package com.terminalmasterhub.core.wireless

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayout
import com.terminalmasterhub.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dialogo de conexion ADB inalambrica.
 *
 * Proporciona dos modos (pestanas):
 * - Vincular (Pair): Android 11+, usa TLS + codigo de 6 digitos
 * - Conectar: conexion directa TCP/IP al puerto ADB
 *
 * Persiste las IPs exitosas en SharedPreferences para autocompletado.
 */
class WirelessAdbDialog(
    private val context: Context,
    private val lifecycleScope: androidx.lifecycle.LifecycleCoroutineScope,
    private val onConnected: (ip: String, port: Int) -> Unit,
    private val onLog: (String) -> Unit
) {

    private val wirelessClient = WirelessAdbClient(context)
    private val prefs = context.getSharedPreferences("wireless_adb", Context.MODE_PRIVATE)

    private var currentMode = 1 // 0=Pairing, 1=Connect

    fun show() {
        val builder = AlertDialog.Builder(context, R.style.Theme_TerminalMasterHub_Dialog)
        val rootView = android.view.LayoutInflater.from(context)
            .inflate(R.layout.dialog_wireless_adb, null)!!

        val tabLayout = rootView.findViewById<TabLayout>(R.id.wirelessTabLayout)
        val recentSpinner = rootView.findViewById<Spinner>(R.id.recentDevicesSpinner)
        val ipInput = rootView.findViewById<EditText>(R.id.wirelessIpInput)
        val portInput = rootView.findViewById<EditText>(R.id.wirelessPortInput)
        val codeInput = rootView.findViewById<EditText>(R.id.wirelessCodeInput)
        val codeLabel = rootView.findViewById<android.widget.TextView>(R.id.codeLabel)
        val btnAction = rootView.findViewById<android.widget.Button>(R.id.btnWirelessAction)
        val btnKeys = rootView.findViewById<android.widget.Button>(R.id.btnGenerateKeys)

        // Cargar dispositivos recientes
        loadRecentDevices(recentSpinner, ipInput, portInput)

        // Escuchar cambios de pestana
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentMode = tab?.position ?: 1
                when (currentMode) {
                    0 -> { // Pairing
                        codeLabel.visibility = android.view.View.VISIBLE
                        codeInput.visibility = android.view.View.VISIBLE
                        portInput.setText(WirelessAdbClient.DEFAULT_PAIRING_PORT.toString())
                        btnAction.text = "Vincular"
                    }
                    1 -> { // Connect
                        codeLabel.visibility = android.view.View.GONE
                        codeInput.visibility = android.view.View.GONE
                        portInput.setText(WirelessAdbClient.DEFAULT_ADB_PORT.toString())
                        btnAction.text = "Conectar"
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // Seleccionar dispositivo reciente autocompleta IP y puerto
        recentSpinner.setOnItemSelectedListener { position ->
            val item = recentSpinner.getItemAtPosition(position)?.toString() ?: return@setOnItemSelectedListener
            if (item.contains(":")) {
                val parts = item.split(":")
                ipInput.setText(parts[0])
                if (parts.size > 1) portInput.setText(parts[1].split(" ")[0])
            }
        }

        // Boton de conexion/vinculacion
        btnAction.setOnClickListener {
            val ip = ipInput.text.toString().trim()
            val port = portInput.text.toString().trim().toIntOrNull() ?: WirelessAdbClient.DEFAULT_ADB_PORT
            val code = codeInput.text.toString().trim()

            if (ip.isEmpty()) {
                Toast.makeText(context, "Ingresa una direccion IP", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnAction.isEnabled = false
            btnAction.text = "Conectando..."

            lifecycleScope.launch {
                try {
                    if (currentMode == 0) {
                        // Modo Pairing
                        if (code.length < 6) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "El codigo debe tener 6 digitos", Toast.LENGTH_SHORT).show()
                                btnAction.isEnabled = true
                                btnAction.text = "Vincular"
                            }
                            return@launch
                        }
                        onLog("Wireless: Vinculando con $ip:$port usando codigo $code...")
                        val result = wirelessClient.pair(ip, port, code)
                        withContext(Dispatchers.Main) {
                            if (result.isSuccess) {
                                Toast.makeText(context, result.getOrThrow(), Toast.LENGTH_LONG).show()
                                onLog("Wireless: ${result.getOrThrow()}")
                                // Guardar IP
                                saveRecentDevice(ip, port)
                            } else {
                                val err = result.exceptionOrNull()?.message ?: "Error desconocido"
                                Toast.makeText(context, "Error: $err", Toast.LENGTH_LONG).show()
                                onLog("Wireless: Error - $err")
                            }
                            btnAction.isEnabled = true
                            btnAction.text = "Vincular"
                        }
                    } else {
                        // Modo Connect
                        onLog("Wireless: Conectando a $ip:$port...")
                        val result = wirelessClient.connect(ip, port)
                        withContext(Dispatchers.Main) {
                            if (result.isSuccess) {
                                val conn = result.getOrThrow()
                                Toast.makeText(context, "Conectado a $ip:$port", Toast.LENGTH_SHORT).show()
                                onLog("Wireless: Conectado a $ip:$port")
                                saveRecentDevice(ip, port)
                                onConnected(ip, port)
                            } else {
                                val err = result.exceptionOrNull()?.message ?: "Conexion rechazada"
                                Toast.makeText(context, "Error: $err", Toast.LENGTH_LONG).show()
                                onLog("Wireless: Error - $err")
                            }
                            btnAction.isEnabled = true
                            btnAction.text = "Conectar"
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                        btnAction.isEnabled = true
                        btnAction.text = if (currentMode == 0) "Vincular" else "Conectar"
                    }
                }
            }
        }

        // Boton generar claves RSA
        btnKeys.setOnClickListener {
            btnKeys.isEnabled = false
            lifecycleScope.launch {
                val result = AdbCryptoManager.generateKeys(context)
                withContext(Dispatchers.Main) {
                    if (result.isSuccess) {
                        Toast.makeText(context, "Nuevas claves generadas con exito", Toast.LENGTH_SHORT).show()
                        onLog("Claves RSA regeneradas correctamente")
                    } else {
                        Toast.makeText(context, "Error: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                    }
                    btnKeys.isEnabled = true
                }
            }
        }

        // Configurar dialogo
        builder.setView(rootView)
            .setTitle("ADB Inalambrico")
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun loadRecentDevices(spinner: Spinner, ipInput: EditText, portInput: EditText) {
        val saved = prefs.getString("recent_ips", "") ?: ""
        val devices = if (saved.isEmpty()) listOf("192.168.1.100:5555") else saved.split(",").filter { it.isNotEmpty() }

        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, devices)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
    }

    private fun saveRecentDevice(ip: String, port: Int) {
        val entry = "$ip:$port"
        val saved = prefs.getString("recent_ips", "") ?: ""
        val devices = saved.split(",").filter { it.isNotEmpty() && it != entry }.toMutableList()
        devices.add(0, entry)
        if (devices.size > 10) devices.removeAt(devices.lastIndex)
        prefs.edit().putString("recent_ips", devices.joinToString(",")).apply()
    }

    private fun android.widget.Spinner.setOnItemSelectedListener(onSelect: (Int) -> Unit) {
        this.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                onSelect(pos)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }
}
