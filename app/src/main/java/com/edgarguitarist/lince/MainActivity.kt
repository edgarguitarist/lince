package com.edgarguitarist.lince

import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.edgarguitarist.lince.ui.Amarillo
import com.edgarguitarist.lince.ui.AvisoActualizacion
import com.edgarguitarist.lince.ui.BotonAmarillo
import com.edgarguitarist.lince.ui.Fondo
import com.edgarguitarist.lince.ui.HojaDescarga
import com.edgarguitarist.lince.ui.Linea
import com.edgarguitarist.lince.ui.PantallaAcerca
import com.edgarguitarist.lince.ui.PantallaEstados
import com.edgarguitarist.lince.ui.Superficie
import com.edgarguitarist.lince.ui.TemaLince
import com.edgarguitarist.lince.ui.TextoSec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { TemaLince { Inicio() } }
    }
}

@Composable
private fun Inicio() {
    val ctx = LocalContext.current
    val wm = remember { WorkManager.getInstance(ctx) }
    val trabajos by wm.getWorkInfosByTagFlow(Descargas.TAG).collectAsState(initial = emptyList())
    var texto by rememberSaveable { mutableStateOf("") }
    var analizando by rememberSaveable { mutableStateOf<String?>(null) }
    var version by remember { mutableStateOf(Motor.version(ctx)) }
    var pestana by rememberSaveable { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().background(Fondo).safeDrawingPadding().padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        Text("Lince", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Amarillo)
        Pestanas(listOf("Descargar", "Estados", "Acerca de"), pestana) { pestana = it }
        if (pestana == 1) {
            PantallaEstados(Modifier.weight(1f))
        } else if (pestana == 2) {
            PantallaAcerca(Modifier.weight(1f))
        } else {
            Text("Comparte un enlace desde cualquier app, o pégalo aquí.", color = TextoSec, fontSize = 14.sp)
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = texto,
                onValueChange = { texto = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("https://…") },
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Amarillo, cursorColor = Amarillo),
                trailingIcon = {
                    IconButton(onClick = {
                        val cm = ctx.getSystemService(ClipboardManager::class.java)
                        cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.let { texto = it }
                    }) { Icon(Icons.Outlined.ContentPaste, "Pegar") }
                },
            )
            Spacer(Modifier.height(12.dp))
            BotonAmarillo("Analizar") {
                val u = extraerUrl(texto)
                if (u == null) Toast.makeText(ctx, "Eso no parece un enlace", Toast.LENGTH_SHORT).show() else analizando = u
            }

            Row(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Descargas", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (trabajos.any { it.state.isFinished }) {
                    TextButton(onClick = { wm.pruneWork() }) { Text("Limpiar", color = TextoSec) }
                }
            }
            if (trabajos.isEmpty()) {
                Text("Todavía no hay descargas. Se guardan en Descargas/${Descargas.CARPETA}.", color = TextoSec, fontSize = 14.sp)
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(trabajos.sortedWith(compareBy({ it.state.isFinished }, { it.id })), key = { it.id }) { w ->
                    FilaDescarga(w, onCancelar = { wm.cancelWorkById(w.id) })
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("yt-dlp ${version ?: "—"}", color = TextoSec, fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    scope.launch {
                        Toast.makeText(ctx, "Buscando actualización…", Toast.LENGTH_SHORT).show()
                        val cambio = withContext(Dispatchers.IO) { Motor.actualizar(ctx, forzar = true) }
                        version = Motor.version(ctx)
                        Toast.makeText(ctx, if (cambio) "yt-dlp actualizado a $version" else "yt-dlp ya está al día", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Actualizar motor", color = TextoSec, fontSize = 12.sp) }
            }
        }
    }

    analizando?.let { u -> HojaDescarga(u) { analizando = null } }
    AvisoActualizacion()
}

/** Pestañas de texto con subrayado amarillo, como las de Snaptube. */
@Composable
private fun Pestanas(titulos: List<String>, elegida: Int, onElegir: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        titulos.forEachIndexed { i, t ->
            Column(Modifier.clickable { onElegir(i) }.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    t,
                    color = if (i == elegida) Color.White else TextoSec,
                    fontSize = 18.sp,
                    fontWeight = if (i == elegida) FontWeight.Bold else FontWeight.Medium,
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier.width(28.dp).height(3.dp)
                        .background(if (i == elegida) Amarillo else Color.Transparent, RoundedCornerShape(2.dp))
                )
            }
        }
    }
}

@Composable
private fun FilaDescarga(w: WorkInfo, onCancelar: () -> Unit) {
    val ctx = LocalContext.current
    val titulo = w.tags.firstOrNull { it.startsWith("titulo:") }?.removePrefix("titulo:") ?: "Descarga"
    val tipo = w.tags.firstOrNull { it.startsWith("tipo:") }?.removePrefix("tipo:") ?: ""
    val uri = w.outputData.getString("uri")
    val p = w.progress.getInt("p", -1)
    val estado = when (w.state) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> "En cola"
        WorkInfo.State.RUNNING -> when {
            p < 0 -> "Preparando…"
            p >= 100 -> "Procesando…"
            else -> "$p %"
        }
        WorkInfo.State.SUCCEEDED -> "Listo · toca para abrir"
        WorkInfo.State.FAILED -> w.outputData.getString("error") ?: "Falló"
        WorkInfo.State.CANCELLED -> "Cancelada"
    }
    Column(
        Modifier.fillMaxWidth().background(Superficie, RoundedCornerShape(12.dp))
            .clickable(enabled = uri != null) {
                val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), w.outputData.getString("mime"))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runCatching { ctx.startActivity(i) }.onFailure {
                    Toast.makeText(ctx, "No hay app para abrir este archivo", Toast.LENGTH_SHORT).show()
                }
            }
            .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(titulo, color = Color.White, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(tipo, estado).filter { it.isNotEmpty() }.joinToString(" · "),
                    color = if (w.state == WorkInfo.State.FAILED) Color(0xFFFF6B6B) else TextoSec,
                    fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            }
            if (!w.state.isFinished) {
                IconButton(onClick = onCancelar) { Icon(Icons.Outlined.Close, "Cancelar", tint = TextoSec) }
            } else Spacer(Modifier.width(10.dp))
        }
        if (w.state == WorkInfo.State.RUNNING) {
            Spacer(Modifier.height(6.dp))
            if (p in 0..99) {
                LinearProgressIndicator(progress = { p / 100f }, Modifier.fillMaxWidth().padding(end = 10.dp), color = Amarillo, trackColor = Linea)
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(end = 10.dp), color = Amarillo, trackColor = Linea)
            }
        }
    }
}
