package com.terminalmasterhub.ui.explorer

import android.content.Context
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import java.io.File

/**
 * Explorador de archivos visual con soporte Root/No-Root.
 *
 * Root:   Navega desde / (raíz del sistema)
 * No-Root: Navega desde /storage/emulated/0/
 *
 * Muestra archivos y directorios en una lista vertical.
 * Al hacer clic en una carpeta, navega a ella.
 * Al hacer clic en un archivo, lo selecciona.
 */
class FileExplorerDialog(
    private val context: Context,
    private val rootPath: String = "/storage/emulated/0",
    private val hasRoot: Boolean = false,
    private val onFileSelected: ((String) -> Unit)? = null
) {

    private var currentPath: String = rootPath
    private lateinit var dialog: AlertDialog
    private lateinit var listView: ListView
    private lateinit var pathText: TextView
    private val adapter = FileListAdapter(context, mutableListOf())

    data class FileItem(
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val size: Long,
        val icon: String = if (isDirectory) "📁" else "📄"
    )

    fun show() {
        val builder = AlertDialog.Builder(context)
        val rootView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Header con modo
        val headerText = TextView(context).apply {
            text = if (hasRoot) "🔓 Explorador Root" else "📱 Explorador (No-Root)"
            setTextColor(0xFF00FF41.toInt())
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(16, 12, 16, 4)
        }

        // Ruta actual
        pathText = TextView(context).apply {
            text = currentPath
            setTextColor(0xFFE6EDF3.toInt())
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(16, 4, 16, 8)
            setSingleLine()
            ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            isSelected = true
        }

        // Breadcrumb row with parent button
        val breadcrumbRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 0, 8, 0)
        }

        val btnParent = Button(context).apply {
            text = "← Subir"
            setTextColor(0xFF3399FF.toInt())
            textSize = 12f
            setOnClickListener { navigateToParent() }
        }

        val btnRefresh = Button(context).apply {
            text = "↻"
            setTextColor(0xFF00FF41.toInt())
            textSize = 14f
            setOnClickListener { loadDirectory(currentPath) }
        }

        breadcrumbRow.addView(btnParent)
        breadcrumbRow.addView(btnRefresh)

        // ListView de archivos
        listView = ListView(context).apply {
            adapter = this@FileExplorerDialog.adapter
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0
            ).apply { weight = 1f }
            divider = null
            dividerHeight = 0
            setPadding(8, 4, 8, 4)
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            val item = adapter.getItem(position) ?: return@setOnItemClickListener
            if (item.isDirectory) {
                navigateTo(item.path)
            } else {
                onFileSelected?.invoke(item.path)
                dialog.dismiss()
            }
        }

        listView.setOnItemLongClickListener { _, _, position, _ ->
            val item = adapter.getItem(position) ?: return@setOnItemLongClickListener false
            if (!item.isDirectory) {
                onFileSelected?.invoke(item.path)
                dialog.dismiss()
            }
            true
        }

        rootView.addView(headerText)
        rootView.addView(pathText)
        rootView.addView(breadcrumbRow)
        rootView.addView(listView)

        builder.setView(rootView)
        builder.setPositiveButton("Cerrar", null)
        dialog = builder.create()
        dialog.show()

        // Cargar directorio inicial
        loadDirectory(currentPath)
    }

    private fun loadDirectory(path: String) {
        currentPath = path
        pathText.text = path

        val items = mutableListOf<FileItem>()
        val dir = File(path)

        if (dir.exists() && dir.isDirectory) {
            try {
                dir.listFiles()
                    ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
                    ?.forEach { file ->
                        items.add(
                            FileItem(
                                name = file.name,
                                path = file.absolutePath,
                                isDirectory = file.isDirectory,
                                size = if (file.isFile) file.length() else 0L
                            )
                        )
                    }
            } catch (e: SecurityException) {
                items.add(FileItem("🚫 Permiso denegado", path, false, 0L, "🚫"))
            }
        } else {
            items.add(FileItem("🚫 No se puede acceder", path, false, 0L, "🚫"))
        }

        adapter.updateItems(items)
    }

    private fun navigateTo(path: String) {
        loadDirectory(path)
    }

    private fun navigateToParent() {
        val parent = File(currentPath).parentFile
        if (parent != null && parent.exists()) {
            loadDirectory(parent.absolutePath)
        }
    }

    /**
     * Adaptador personalizado para la lista de archivos.
     */
    class FileListAdapter(
        private val context: Context,
        private var items: MutableList<FileItem>
    ) : BaseAdapter() {

        fun updateItems(newItems: List<FileItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): FileItem? = items.getOrNull(position)
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val item = items[position]
            val textView = (convertView as? TextView) ?: TextView(context).apply {
                setPadding(12, 8, 12, 8)
                textSize = 13f
                typeface = android.graphics.Typeface.MONOSPACE
            }

            val sizeStr = if (item.isDirectory) "" else "  (${formatSize(item.size)})"
            textView.text = "${item.icon}  ${item.name}$sizeStr"
            textView.setTextColor(
                if (item.isDirectory) 0xFF00FF41.toInt()
                else 0xFFE6EDF3.toInt()
            )

            return textView
        }

        private fun formatSize(bytes: Long): String {
            return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> "${bytes / 1024} KB"
                bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
                else -> "${bytes / (1024 * 1024 * 1024)} GB"
            }
        }
    }
}
