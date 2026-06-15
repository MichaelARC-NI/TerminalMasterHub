package com.terminalmasterhub.core.wireless

import android.app.Activity
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleCoroutineScope
import com.google.android.material.tabs.TabLayout
import com.terminalmasterhub.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dialogo de conexion ADB inalambrica.
 *
 * v1.3.2: Correcciones UI:
 * - .show() siempre en Main Thread via requireActivity().runOnUiThread
 * - Inflado con contexto de Activity para evitar errores de temas
 * - Try-catch en inflado con Toast de error
 * - Spinner con AdapterView.OnItemSelectedListener estandar
 */
class WirelessAdbDialog(
    private val activity: Activity,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val onConnected: (ip: String, port: Int) -> Unit,
    private val onLog: (String) -> Unit
) {

    private val wirelessClient = WirelessAdbClient(activity)
    private val prefs = activity.getSharedPreferences("wireless_adb", Context.MODE_PRIVATE)

    private var currentMode = 1 // 0=Pairing, 1=Connect

    fun show() {
        // Asegurar que show() corre en Main Thread
        activity.runOnUiThread {
            try {
                val builder = AlertDialog.Builder(activity, R.style.Theme_TerminalMasterHub_Dialog)
                val rootView = LayoutInflater.from(activity)
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
                                codeLabel.visibility = View.VISIBLE
                                codeInput.visibility = View.VISIBLE
                                portInput.setText(WirelessAdbClient.DEFAULT_PAIRING_PORT.toString())
                                btnAction.text = "Vincular"
                            }
                            1 -> { // Connect
                                codeLabel.visibility = View.GONE
                                codeInput.visibility = View.GONE
                                portInput.setText(WirelessAdbClient.DEFAULT_ADB_PORT.toString())
                                btnAction.text = "Conectar"
                            }
                        }
                    }
                    override fun onTabUnselected(tab: TabLayout.Tab?) {}
                    override fun onTabReselected(tab: TabLayout.Tab?) {}
                })

                // Spinner: seleccion de dispositivo reciente autocompleta
                recentSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                        val item = parent?.getItemAtPosition(pos)?.toString() ?: return
                        if (item.contains(":")) {
                            val parts = item.split(":")
                            ipInput.setText(parts[0])
                            if (parts.size > 1) portInput.setText(parts[1].split(" ")[0])
                        }
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }

                // Boton de conexion/vinculacion
                btnAction.setOnClickListener {
                    val ip = ipInput.text.toString().trim()
                    val port = portInput.text.toString().trim().toIntOrNull()
                        ?: WirelessAdbClient.DEFAULT_ADB_PORT
                    val code = codeInput.text.toString().trim()

                    if (ip.isEmpty()) {
                        Toast.makeText(activity, "Ingresa una direccion IP", Toast.LENGTH_SHORT).show()
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
                                        Toast.makeText(activity, "El codigo debe tener 6 digitos", Toast.LENGTH_SHORT).show()
                                        btnAction.isEnabled = true
                                        btnAction.text = "Vincular"
                                    }
                                    return@launch
                                }
                                onLog("Wireless: Vinculando con $ip:$port...")
                                val result = wirelessClient.pair(ip, port, code)
                                withContext(Dispatchers.Main) {
                                    if (result.isSuccess) {
                                        Toast.makeText(activity, result.getOrThrow(), Toast.LENGTH_LONG).show()
                                        onLog("Wireless: ${result.getOrThrow()}")
                                        saveRecentDevice(ip, port)
                                    } else {
                                        val err = result.exceptionOrNull()?.message ?: "Error desconocido"
                                        Toast.makeText(activity, "Error: $err", Toast.LENGTH_LONG).show()
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
                                        Toast.makeText(activity, "Conectado a $ip:$port", Toast.LENGTH_SHORT).show()
                                        onLog("Wireless: Conectado a $ip:$port")
                                        saveRecentDevice(ip, port)
                                        onConnected(ip, port)
                                    } else {
                                        val err = result.exceptionOrNull()?.message ?: "Conexion rechazada"
                                        Toast.makeText(activity, "Error: $err", Toast.LENGTH_LONG).show()
                                        onLog("Wireless: Error - $err")
                                    }
                                    btnAction.isEnabled = true
                                    btnAction.text = "Conectar"
                                }
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(activity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
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
                        val result = AdbCryptoManager.generateKeys(activity)
                        withContext(Dispatchers.Main) {
                            if (result.isSuccess) {
                                Toast.makeText(activity, "Nuevas claves generadas con exito", Toast.LENGTH_SHORT).show()
                                onLog("Claves RSA regeneradas correctamente")
                            } else {
                                Toast.makeText(activity, "Error: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                            }
                            btnKeys.isEnabled = true
                        }
                    }
                }

                // Mostrar dialogo
                builder.setView(rootView)
                    .setTitle("ADB Inalambrico")
                    .setNegativeButton("Cerrar", null)
                    .show()

            } catch (e: Exception) {
                // Error al inflar layout del dialogo - mostrar Toast
                Toast.makeText(activity, "Error al abrir dialogo ADB: ${e.message}", Toast.LENGTH_LONG).show()
                onLog("Error UI WirelessADB: ${e.message}")
            }
        }
    }

    private fun loadRecentDevices(spinner: Spinner, ipInput: EditText, portInput: EditText) {
        val saved = prefs.getString("recent_ips", "") ?: ""
        val devices = if (saved.isEmpty()) {
            listOf("192.168.1.100:5555")
        } else {
            saved.split(",").filter { it.isNotEmpty() }
        }
        val adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, devices)
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
}
