package com.edgarguitarist.lince.ui

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import coil.compose.AsyncImage
import com.edgarguitarist.lince.Descargas
import com.edgarguitarist.lince.Estados
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PantallaEstados(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var app by rememberSaveable { mutableStateOf(Estados.App.WHATSAPP) }
    var filtro by rememberSaveable { mutableStateOf("Todos") }
    // Cambia al volver a la app (puede haber estados nuevos) y al dar acceso.
    var recarga by remember { mutableIntStateOf(0) }
    var acceso by remember(app, recarga) { mutableStateOf(Estados.acceso(ctx, app)) }
    var lista by remember(app) { mutableStateOf<List<Estados.Estado>?>(null) }
    var guardados by remember { mutableStateOf(Estados.guardados(ctx)) }
    val guardando = remember { mutableStateOf(setOf<String>()) }

    LifecycleResumeEffect(Unit) {
        recarga++
        onPauseOrDispose {}
    }
    LaunchedEffect(acceso, recarga) {
        val a = acceso ?: return@LaunchedEffect
        lista = withContext(Dispatchers.IO) { runCatching { Estados.listar(ctx, a) }.getOrDefault(emptyList()) }
    }
    val pedir = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val arbol = r.data?.data
        if (r.resultCode != Activity.RESULT_OK || arbol == null) return@rememberLauncherForActivityResult
        if (Estados.guardarAcceso(ctx, app, arbol)) {
            recarga++
        } else {
            Toast.makeText(ctx, "Esa no es la carpeta .Statuses. Toca «Usar esta carpeta» sin moverte de donde se abre.", Toast.LENGTH_LONG).show()
        }
    }

    Column(modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Estados.App.entries.forEach { a ->
                Chip(a.etiqueta, app == a) { app = a }
            }
            Spacer(Modifier.weight(1f))
            if (acceso != null) {
                IconButton(onClick = { recarga++ }) { Icon(Icons.Outlined.Refresh, "Actualizar", tint = TextoSec) }
            }
        }

        val a = acceso
        if (a == null) {
            SinAcceso(app) { runCatching { pedir.launch(Estados.intentAcceso(app)) } }
            return@Column
        }

        val todos = lista
        if (todos == null) {
            Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Amarillo)
            }
            return@Column
        }
        val visibles = when (filtro) {
            "Fotos" -> todos.filter { !it.video }
            "Videos" -> todos.filter { it.video }
            else -> todos
        }
        Row(
            Modifier.padding(top = 4.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf("Todos", "Fotos", "Videos").forEach { f -> Chip(f, filtro == f) { filtro = f } }
            Spacer(Modifier.weight(1f))
            Text("${visibles.size}", color = TextoSec, fontSize = 13.sp)
        }
        if (visibles.isEmpty()) {
            Text(
                "No hay estados. Abre ${if (app == Estados.App.WHATSAPP) "WhatsApp" else "WhatsApp Business"} y mira algunos: " +
                    "solo aparecen los que ya viste.",
                color = TextoSec, fontSize = 14.sp, modifier = Modifier.padding(top = 16.dp),
            )
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(visibles, key = { it.uri.toString() }) { e ->
                Celda(
                    e = e,
                    guardado = e.nombre in guardados,
                    ocupado = e.nombre in guardando.value,
                    onAbrir = {
                        val i = Intent(Intent.ACTION_VIEW).setDataAndType(e.uri, Descargas.mime(e.nombre))
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        runCatching { ctx.startActivity(i) }
                    },
                    onGuardar = {
                        guardando.value = guardando.value + e.nombre
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { runCatching { Estados.guardar(ctx, e) } }
                            guardando.value = guardando.value - e.nombre
                            guardados = Estados.guardados(ctx)
                            Toast.makeText(
                                ctx,
                                if (r.isSuccess) "Guardado en Descargas/${Descargas.CARPETA}/${Estados.SUBCARPETA}"
                                else "No se pudo guardar: ${r.exceptionOrNull()?.message}",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun Chip(texto: String, elegido: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = elegido,
        onClick = onClick,
        label = { Text(texto) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Amarillo, selectedLabelColor = Color.Black),
    )
}

@Composable
private fun SinAcceso(app: Estados.App, onPedir: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp).background(Superficie, RoundedCornerShape(16.dp)).padding(20.dp)
    ) {
        Text("Estados de ${if (app == Estados.App.WHATSAPP) "WhatsApp" else "WhatsApp Business"}", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Para verlos, Android pide permiso para leer la carpeta donde WhatsApp los guarda. Se pide una sola vez: " +
                "en la pantalla que se abre, toca «Usar esta carpeta» (o «Seleccionar») y después «Permitir».",
            color = TextoSec, fontSize = 14.sp,
        )
        Spacer(Modifier.height(16.dp))
        BotonAmarillo("Dar acceso a los estados", onClick = onPedir)
    }
}

@Composable
private fun Celda(e: Estados.Estado, guardado: Boolean, ocupado: Boolean, onAbrir: () -> Unit, onGuardar: () -> Unit) {
    Box(
        Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(Superficie).clickable(onClick = onAbrir)
    ) {
        AsyncImage(e.uri, e.nombre, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (e.video) {
            Box(
                Modifier.align(Alignment.Center).size(34.dp).background(Color(0x99000000), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.PlayArrow, null, tint = Color.White) }
        }
        Box(
            Modifier.align(Alignment.BottomEnd).padding(6.dp).size(32.dp)
                .background(if (guardado) Color(0xCC000000) else Amarillo, CircleShape)
                .clickable(enabled = !guardado && !ocupado, onClick = onGuardar),
            contentAlignment = Alignment.Center,
        ) {
            when {
                ocupado -> CircularProgressIndicator(Modifier.size(18.dp), color = Color.Black, strokeWidth = 2.dp)
                guardado -> Icon(Icons.Filled.Check, "Guardado", tint = Amarillo, modifier = Modifier.size(18.dp))
                else -> Icon(Icons.Filled.Download, "Guardar", tint = Color.Black, modifier = Modifier.size(18.dp))
            }
        }
    }
}
