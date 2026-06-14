package com.terminalmasterhub.ui.terminal

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import com.terminalmasterhub.R

/**
 * Actividad emergente para renderizar salida gráfica de Python.
 *
 * Se abre automáticamente cuando el usuario ejecuta un script
 * de Python que genera gráficos (matplotlib, PIL, etc.).
 *
 * Usa WebView para mostrar el contenido HTML generado
 * por el puente Python.
 */
class PythonGraphActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_HTML_CONTENT = "html_content"
        const val EXTRA_TEXT_OUTPUT = "text_output"
    }

    private lateinit var webView: WebView
    private lateinit var btnClose: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_python_graph)

        webView = findViewById(R.id.pythonGraphWebView)
        btnClose = findViewById(R.id.btnCloseGraph)

        btnClose.setOnClickListener { finish() }

        // Configurar WebView para renderizar HTML
        webView.apply {
            settings.javaScriptEnabled = false
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            webViewClient = WebViewClient()
        }

        // Cargar contenido HTML
        val htmlContent = intent.getStringExtra(EXTRA_HTML_CONTENT)
        if (htmlContent != null) {
            webView.loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null)
        } else {
            webView.loadDataWithBaseURL(
                null,
                "<html><body style='color:#E6EDF3;background:#0D1117;padding:16px'>" +
                "<p>No hay salida gráfica disponible</p></body></html>",
                "text/html", "UTF-8", null
            )
        }
    }
}
