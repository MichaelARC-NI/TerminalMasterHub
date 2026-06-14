package com.terminalmasterhub.core.permissions

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Gestor centralizado de permisos runtime.
 *
 * Unifica la solicitud de permisos de almacenamiento,
 * notificaciones y superposición para todos los módulos.
 */
object PermissionManager {

    // Permisos requeridos para almacenamiento
    private val STORAGE_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        emptyArray() // Android 13+ usa MANAGE_EXTERNAL_STORAGE
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        emptyArray() // Android 11+ usa MANAGE_EXTERNAL_STORAGE
    } else {
        arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    }

    private val NOTIFICATION_PERMISSION = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.POST_NOTIFICATIONS
    } else {
        null
    }

    /**
     * Verifica si todos los permisos de almacenamiento están concedidos.
     */
    fun hasStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            STORAGE_PERMISSIONS.all {
                ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        }
    }

    /**
     * Solicita permiso de almacenamiento.
     * En Android 11+ abre la configuración de MANAGE_EXTERNAL_STORAGE.
     */
    fun requestStoragePermission(activity: Activity, launcher: ActivityResultLauncher<Intent>?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                launcher?.launch(intent)
            }
        } else {
            ActivityCompat.requestPermissions(
                activity,
                STORAGE_PERMISSIONS,
                REQUEST_CODE_STORAGE
            )
        }
    }

    /**
     * Verifica permiso de notificaciones.
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return NOTIFICATION_PERMISSION == null ||
                ContextCompat.checkSelfPermission(context, NOTIFICATION_PERMISSION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /**
     * Solicita permiso de notificaciones (Android 13+).
     */
    fun requestNotificationPermission(activity: Activity) {
        NOTIFICATION_PERMISSION?.let {
            if (ContextCompat.checkSelfPermission(activity, it) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(it),
                    REQUEST_CODE_NOTIFICATION
                )
            }
        }
    }

    /**
     * Verifica permiso de superposición (SYSTEM_ALERT_WINDOW).
     */
    fun hasOverlayPermission(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    /**
     * Abre configuración de superposición.
     */
    fun requestOverlayPermission(activity: Activity, launcher: ActivityResultLauncher<Intent>?) {
        if (!Settings.canDrawOverlays(activity)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${activity.packageName}")
            )
            launcher?.launch(intent)
        }
    }

    /**
     * Obtiene el StorageManager volume para el almacenamiento externo.
     */
    fun getStorageVolumes(context: Context): List<android.os.storage.StorageVolume> {
        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        return storageManager.storageVolumes.toList()
    }

    const val REQUEST_CODE_STORAGE = 9001
    const val REQUEST_CODE_NOTIFICATION = 9002
    const val REQUEST_CODE_OVERLAY = 9003
}
