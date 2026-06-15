package com.terminalmasterhub

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.snackbar.Snackbar
import com.terminalmasterhub.core.permissions.PermissionManager
import com.terminalmasterhub.core.usb.UsbBroadcastReceiver
import com.terminalmasterhub.core.usb.UsbManagerCore
import com.terminalmasterhub.ui.SessionPagerAdapter
import com.terminalmasterhub.ui.fastboot.FastbootHostFragment
import com.terminalmasterhub.ui.samsung.SamsungOdinFragment
import com.terminalmasterhub.ui.terminal.TerminalFragment
import com.terminalmasterhub.ui.xiaomi.XiaomiFragment

/**
 * Actividad principal de Terminal Master Hub.
 *
 * Implementa Bottom Navigation + ViewPager2 con 4 sesiones:
 * [0] Terminal & Python
 * [1] ADB/Fastboot Universal
 * [2] Samsung Odin3
 * [3] Xiaomi MiTool
 *
 * Gestiona permisos runtime y la conexión USB OTG global.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var pagerAdapter: SessionPagerAdapter

    // Fragmentos de cada sesión
    private val terminalFragment = TerminalFragment()
    private val fastbootHostFragment = FastbootHostFragment()
    private val samsungFragment = SamsungOdinFragment()
    private val xiaomiFragment = XiaomiFragment()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewPager = findViewById(R.id.sessionPager)
        bottomNav = findViewById(R.id.bottomNavigation)

        // Edge-to-Edge: manejar WindowInsets para Android 16
        setupEdgeToEdge()

        setupViewPager()
        setupBottomNav()
        requestRequiredPermissions()

        // Registrar receiver USB
        registerUsbReceiver()

        // Verificar dispositivos USB conectados al inicio
        checkUsbDevices()
    }

    // ===================== EDGE-TO-EDGE (Android 16) =====================

    private fun setupEdgeToEdge() {
        val rootView = findViewById<android.view.View>(R.id.mainContainer)
        // Aplicar padding dinamico para status bar y navigation bar
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
                        or WindowInsetsCompat.Type.displayCutout()
            )
            // Padding superior para la barra de estado
            view.setPadding(
                view.paddingLeft,
                systemBars.top.coerceAtLeast(0),
                view.paddingRight,
                systemBars.bottom.coerceAtLeast(0)
            )
            insets
        }
        // Solicitar layout edge-to-edge (contenido detras de barras)
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(usbBroadcastReceiver)
        } catch (_: Exception) {}
    }

    // ===================== UI SETUP =====================

    private fun setupViewPager() {
        pagerAdapter = SessionPagerAdapter(this)
        pagerAdapter.setFragments(
            terminalFragment,
            fastbootHostFragment,
            samsungFragment,
            xiaomiFragment
        )
        viewPager.adapter = pagerAdapter

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                bottomNav.menu.getItem(position).isChecked = true
            }
        })
    }

    private fun setupBottomNav() {
        bottomNav.setOnItemSelectedListener { item ->
            val position = when (item.itemId) {
                R.id.nav_terminal -> SessionPagerAdapter.TAB_TERMINAL
                R.id.nav_fastboot -> SessionPagerAdapter.TAB_FASTBOOT
                R.id.nav_samsung -> SessionPagerAdapter.TAB_SAMSUNG
                R.id.nav_xiaomi -> SessionPagerAdapter.TAB_XIAOMI
                else -> return@setOnItemSelectedListener false
            }
            viewPager.setCurrentItem(position, true)
            true
        }
    }

    // ===================== PERMISSIONS =====================

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (PermissionManager.hasStoragePermission(this)) {
            Snackbar.make(findViewById(R.id.mainContainer),
                "Permiso de almacenamiento concedido", Snackbar.LENGTH_SHORT).show()
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (PermissionManager.hasOverlayPermission(this)) {
            Snackbar.make(findViewById(R.id.mainContainer),
                "Permiso de superposición concedido", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun requestRequiredPermissions() {
        // 1. Almacenamiento (MANAGE_EXTERNAL_STORAGE en Android 11+)
        if (!PermissionManager.hasStoragePermission(this)) {
            PermissionManager.requestStoragePermission(this, storagePermissionLauncher)
        }

        // 2. Notificaciones (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    PermissionManager.REQUEST_CODE_NOTIFICATION
                )
            }
        }

        // 3. Superposición (SYSTEM_ALERT_WINDOW para ventanas flotantes)
        if (!PermissionManager.hasOverlayPermission(this)) {
            PermissionManager.requestOverlayPermission(this, overlayPermissionLauncher)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PermissionManager.REQUEST_CODE_STORAGE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Permiso de almacenamiento concedido", Toast.LENGTH_SHORT).show()
                }
            }
            PermissionManager.REQUEST_CODE_NOTIFICATION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Notificaciones permitidas
                }
            }
        }
    }

    // ===================== USB HANDLING =====================

    private val usbBroadcastReceiver = UsbBroadcastReceiver()

    private fun registerUsbReceiver() {
        val filter = android.content.IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManagerCore.ACTION_USB_PERMISSION)
        }
        registerReceiver(usbBroadcastReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    private fun checkUsbDevices() {
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        if (usbManager.deviceList.isNotEmpty()) {
            Snackbar.make(
                findViewById(R.id.mainContainer),
                "Dispositivo USB detectado: ${usbManager.deviceList.size}",
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    // ===================== NAVIGATION =====================

    /**
     * Cambia a una sesión específica programáticamente.
     */
    fun navigateToSession(session: Int) {
        if (session in 0 until SessionPagerAdapter.TOTAL_TABS) {
            viewPager.setCurrentItem(session, true)
        }
    }
}
