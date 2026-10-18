package com.edgarguitarist.lince.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val REPO = "https://github.com/edgarguitarist/lince"

/** Pestaña «Acerca de»: versión, enlaces de donación (los mismos del perfil de GitHub) y el repo. */
@Composable
fun PantallaAcerca(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "—"
    }
    val abrir: (String) -> Unit = { url ->
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure {
            Toast.makeText(ctx, "No hay navegador para abrir el enlace", Toast.LENGTH_SHORT).show()
        }
    }

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text("Versión $version · por Edgar Laz", color = TextoSec, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "Descargador sin anuncios, gratis y de código abierto. Si te sirve, puedes invitarme un café: " +
                "ayuda a mantenerlo al día.",
            color = Color.White, fontSize = 15.sp, lineHeight = 21.sp,
        )

        Titulo("Apoya el proyecto")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Enlace(Icons.Outlined.FavoriteBorder, "GitHub Sponsors", "github.com/sponsors/edgarguitarist") {
                abrir("https://github.com/sponsors/edgarguitarist")
            }
            Enlace(Icons.Outlined.LocalCafe, "Ko-fi", "ko-fi.com/edguitarist") { abrir("https://ko-fi.com/edguitarist") }
            Enlace(Icons.Outlined.Payments, "PayPal", "paypal.me/edgarguitarist") { abrir("https://paypal.me/edgarguitarist") }
        }

        Titulo("Proyecto")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Enlace(Icons.Outlined.SystemUpdate, "Buscar nueva versión", "Descargas en GitHub") { abrir("$REPO/releases/latest") }
            Enlace(Icons.Outlined.Code, "Código fuente", "github.com/edgarguitarist/lince") { abrir(REPO) }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Titulo(texto: String) {
    Text(
        texto, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 28.dp, bottom = 10.dp),
    )
}

@Composable
private fun Enlace(icono: ImageVector, titulo: String, detalle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Superficie, RoundedCornerShape(12.dp)).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icono, null, tint = Amarillo, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(titulo, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(detalle, color = TextoSec, fontSize = 12.sp)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = TextoSec)
    }
}
