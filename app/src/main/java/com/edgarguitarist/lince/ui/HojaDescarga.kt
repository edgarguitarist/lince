package com.edgarguitarist.lince.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.edgarguitarist.lince.Analisis
import com.edgarguitarist.lince.Descargas
import com.edgarguitarist.lince.Entrada
import com.edgarguitarist.lince.Motor
import com.edgarguitarist.lince.Opcion
import com.edgarguitarist.lince.Subtitulo
import com.edgarguitarist.lince.Video
import com.edgarguitarist.lince.duracionTexto
import com.edgarguitarist.lince.opcionesRapidas
import com.edgarguitarist.lince.tamAudio
import com.edgarguitarist.lince.tamTexto
import com.edgarguitarist.lince.todasLasOpciones
import com.edgarguitarist.lince.urlDirecta
import kotlinx.coroutines.launch

sealed interface Estado {
    data object Cargando : Estado
    data class Error(val msg: String) : Estado
    data class Listo(val analisis: Analisis) : Estado
}

class AnalisisVM(app: Application) : AndroidViewModel(app) {
    var estado by mutableStateOf<Estado>(Estado.Cargando)
        private set
    private var url: String? = null

    fun analizar(u: String, forzar: Boolean = false) {
        if (u == url && !forzar) return
        url = u
        estado = Estado.Cargando
        viewModelScope.launch {
            estado = try {
                Estado.Listo(Motor.analizar(getApplication(), u))
            } catch (e: Exception) {
                val txt = e.message ?: e.toString()
                Estado.Error(
                    txt.lines().lastOrNull { it.contains("ERROR:") }?.substringAfter("ERROR:")?.trim()
                        ?: txt.take(300)
                )
            }
        }
    }
}

/** La hoja inferior: analiza el enlace y ofrece las opciones. `onCerrar` se llama al terminar o descartar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HojaDescarga(url: String, onCerrar: () -> Unit) {
    val vm: AnalisisVM = viewModel()
    LaunchedEffect(url) { vm.analizar(url) }
    PedirPermisos()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val cerrar: () -> Unit = { scope.launch { sheet.hide() }.invokeOnCompletion { onCerrar() } }

    ModalBottomSheet(
        onDismissRequest = onCerrar,
        sheetState = sheet,
        containerColor = Fondo,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color(0xFF4A4A4A)) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            when (val e = vm.estado) {
                Estado.Cargando -> VistaCargando(url)
                is Estado.Error -> VistaError(e.msg) { vm.analizar(url, forzar = true) }
                is Estado.Listo -> when (val a = e.analisis) {
                    is Analisis.Unico -> VistaVideo(a.video) { op, sub ->
                        Descargas.encolar(ctx, a.video.url, a.video.titulo, op, sub, a.video.urlDirecta(op))
                        Toast.makeText(ctx, "Descargando…", Toast.LENGTH_SHORT).show()
                        cerrar()
                    }
                    is Analisis.Lista -> VistaLista(a) { entradas, op ->
                        entradas.forEach { Descargas.encolar(ctx, it.url, it.titulo, op) }
                        Toast.makeText(ctx, "${entradas.size} descargas en cola", Toast.LENGTH_SHORT).show()
                        cerrar()
                    }
                }
            }
        }
    }
}

@Composable
private fun Titulo(texto: String) {
    Text(texto, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
}

@Composable
private fun Seccion(texto: String) {
    Text(texto, color = TextoSec, fontSize = 15.sp, modifier = Modifier.padding(top = 18.dp, bottom = 4.dp))
}

@Composable
private fun VistaCargando(url: String) {
    Titulo("Descargar")
    Spacer(Modifier.height(28.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(26.dp), color = Amarillo, strokeWidth = 3.dp)
        Spacer(Modifier.width(16.dp))
        Column {
            Text("Analizando enlace…", color = Color.White, fontSize = 16.sp)
            Text(url, color = TextoSec, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    Spacer(Modifier.height(48.dp))
}

@Composable
private fun VistaError(msg: String, reintentar: () -> Unit) {
    Titulo("Descargar")
    Spacer(Modifier.height(20.dp))
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.ErrorOutline, null, tint = Color(0xFFFF6B6B))
        Spacer(Modifier.width(12.dp))
        Text(msg, color = Color.White, fontSize = 14.sp)
    }
    Spacer(Modifier.height(24.dp))
    BotonAmarillo("Reintentar", onClick = reintentar)
}

@Composable
private fun Cabecera(video: Video) {
    Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(112.dp, 63.dp).clip(RoundedCornerShape(8.dp)).background(Superficie)) {
            if (video.miniatura != null) {
                AsyncImage(video.miniatura, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            }
            val d = duracionTexto(video.duracion)
            if (d.isNotEmpty()) {
                Text(
                    d, color = Color.White, fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(video.titulo, color = Color.White, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            video.autor?.let { Text(it, color = TextoSec, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun VistaVideo(video: Video, onDescargar: (Opcion, Subtitulo?) -> Unit) {
    val rapidas = remember(video) { video.opcionesRapidas() }
    val todas = remember(video) { video.todasLasOpciones() }
    var sel by remember(video) { mutableStateOf<Opcion>(rapidas.last().second) }
    var sub by remember(video) { mutableStateOf(video.subtitulos.firstOrNull { it.codigo.startsWith("es") && !it.automatico }) }
    var pagina by remember { mutableStateOf("principal") }

    when (pagina) {
        "formatos" -> SubPagina("Más formatos", { pagina = "principal" }) {
            todas.forEach { (etq, op) ->
                FilaOpcion(if (op is Opcion.Aud) Icons.Outlined.MusicNote else Icons.Outlined.SmartDisplay, etq, tamTexto(op.tam()), sel.clave == op.clave) {
                    sel = op; pagina = "principal"
                }
            }
        }
        "subs" -> SubPagina("Subtítulos", { pagina = "principal" }) {
            FilaOpcion(Icons.Outlined.Subtitles, "Sin subtítulos", "", sub == null) { sub = null; pagina = "principal" }
            video.subtitulos.forEach { s ->
                FilaOpcion(Icons.Outlined.Subtitles, s.nombre, "", sub == s) { sub = s; pagina = "principal" }
            }
        }
        else -> {
            Titulo("Descargar")
            Cabecera(video)
            Seccion("Vídeo")
            rapidas.forEach { (etq, op) ->
                FilaOpcion(Icons.Outlined.SmartDisplay, etq, tamTexto(op.tam), sel.clave == op.clave) { sel = op }
            }
            // Si se eligió una calidad desde «Más formatos», se muestra aquí para que se vea qué se va a bajar.
            val extra = sel
            if (extra is Opcion.Vid && rapidas.none { it.second.clave == extra.clave }) {
                FilaOpcion(Icons.Outlined.SmartDisplay, "${extra.alto}p", tamTexto(extra.tam), true) {}
            }
            Seccion("Audio")
            val mp3 = Opcion.Aud("mp3", video.tamAudio())
            FilaOpcion(Icons.Outlined.MusicNote, "MP3", tamTexto(mp3.tam), sel.clave == mp3.clave) { sel = mp3 }
            if (extra is Opcion.Aud && extra.formato != "mp3") {
                FilaOpcion(Icons.Outlined.MusicNote, extra.formato.uppercase(), tamTexto(extra.tam), true) {}
            }
            HorizontalDivider(Modifier.padding(top = 10.dp), color = Linea)
            if (video.subtitulos.isNotEmpty() && sel is Opcion.Vid) {
                FilaAjuste("Subtítulos", sub?.nombre ?: "No") { pagina = "subs" }
            }
            FilaAjuste("Más formatos", "Todo") { pagina = "formatos" }
            Spacer(Modifier.height(16.dp))
            BotonAmarillo("Descargar") { onDescargar(sel, sub.takeIf { sel is Opcion.Vid }) }
        }
    }
}

private fun Opcion.tam() = when (this) {
    is Opcion.Vid -> tam
    is Opcion.Aud -> tam
}

@Composable
private fun SubPagina(titulo: String, atras: () -> Unit, contenido: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = atras) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás", tint = Color.White) }
        Text(titulo, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
    Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) { contenido() }
}

@Composable
private fun FilaOpcion(icono: ImageVector, texto: String, tam: String, elegido: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icono, null, tint = Color.White, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(20.dp))
        Text(texto, color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Text(tam, color = TextoSec, fontSize = 15.sp)
        Spacer(Modifier.width(16.dp))
        Radio(elegido)
    }
}

@Composable
private fun Radio(elegido: Boolean) {
    if (elegido) {
        Box(Modifier.size(26.dp).background(Amarillo, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Check, null, tint = Color.Black, modifier = Modifier.size(18.dp))
        }
    } else {
        Box(Modifier.size(26.dp).border(1.5.dp, Color(0xFF6A6A6A), CircleShape))
    }
}

@Composable
private fun FilaAjuste(texto: String, valor: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(60.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(texto, color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Text(valor, color = TextoSec, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
        Spacer(Modifier.width(8.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = TextoSec)
    }
}

@Composable
fun BotonAmarillo(texto: String, habilitado: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = habilitado,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Amarillo, contentColor = Color.Black),
    ) {
        Text(texto, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun VistaLista(lista: Analisis.Lista, onDescargar: (List<Entrada>, Opcion) -> Unit) {
    val calidades = remember {
        listOf("480p" to Opcion.Vid(480, null), "720p" to Opcion.Vid(720, null), "1080p" to Opcion.Vid(1080, null), "MP3" to Opcion.Aud("mp3", null))
    }
    var calidad by remember { mutableStateOf<Opcion>(calidades[1].second) }
    val marcados = remember(lista) { mutableStateListOf(*lista.entradas.indices.toList().toTypedArray()) }

    Titulo("Descargar lista")
    Text(lista.titulo, color = Color.White, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
    Text("${lista.entradas.size} vídeos", color = TextoSec, fontSize = 13.sp)
    Seccion("Calidad")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        calidades.forEach { (etq, op) ->
            FilterChip(
                selected = calidad.clave == op.clave,
                onClick = { calidad = op },
                label = { Text(etq) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Amarillo, selectedLabelColor = Color.Black),
            )
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).clickable {
            if (marcados.size == lista.entradas.size) marcados.clear()
            else { marcados.clear(); marcados.addAll(lista.entradas.indices) }
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(marcados.size == lista.entradas.size, null, colors = CheckboxDefaults.colors(checkedColor = Amarillo, checkmarkColor = Color.Black))
        Spacer(Modifier.width(8.dp))
        Text("Seleccionar todo", color = Color.White)
    }
    HorizontalDivider(color = Linea)
    LazyColumn(Modifier.heightIn(max = 340.dp)) {
        itemsIndexed(lista.entradas) { i, e ->
            Row(
                Modifier.fillMaxWidth().clickable { if (i in marcados) marcados.remove(i) else marcados.add(i) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(i in marcados, null, colors = CheckboxDefaults.colors(checkedColor = Amarillo, checkmarkColor = Color.Black))
                Spacer(Modifier.width(8.dp))
                Text(e.titulo, color = Color.White, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(duracionTexto(e.duracion), color = TextoSec, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    BotonAmarillo("Descargar (${marcados.size})", habilitado = marcados.isNotEmpty()) {
        onDescargar(marcados.sorted().map { lista.entradas[it] }, calidad)
    }
}

/**
 * Android 13+: sin notificaciones la descarga corre igual, pero no se ve el progreso.
 * Android 9: sin el permiso de almacenamiento no se puede guardar en Descargas.
 */
@Composable
private fun PedirPermisos() {
    val faltan = buildList {
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT < 29) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
    if (faltan.isEmpty()) return
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    LaunchedEffect(Unit) {
        val pedir = faltan.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
        if (pedir.isNotEmpty()) launcher.launch(pedir.toTypedArray())
    }
}
