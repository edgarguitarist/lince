package com.edgarguitarist.lince

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.edgarguitarist.lince.ui.HojaDescarga
import com.edgarguitarist.lince.ui.TemaLince

/** Se abre transparente sobre la app desde la que se compartió: solo muestra la hoja de descarga. */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = extraerUrl(texto(intent))
        if (url == null) {
            Toast.makeText(this, "No encontré ningún enlace en lo compartido", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        setContent { TemaLince { HojaDescarga(url) { finish() } } }
    }

    private fun texto(i: Intent): String? = when (i.action) {
        Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT) ?: i.getStringExtra(Intent.EXTRA_SUBJECT)
        Intent.ACTION_VIEW -> i.dataString
        else -> null
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
