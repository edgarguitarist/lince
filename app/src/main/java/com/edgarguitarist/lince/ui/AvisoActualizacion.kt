package com.edgarguitarist.lince.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edgarguitarist.lince.Actualizador
import com.edgarguitarist.lince.tamTexto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

/** Al abrir la app: si hay una versión nueva en GitHub, pregunta si se actualiza y baja el APK que corresponde. */
@Composable
fun AvisoActualizacion() {
    val ctx = LocalContext.current
    var nueva by remember { mutableStateOf<Actualizador.Nueva?>(null) }
    LaunchedEffect(Unit) { nueva = Actualizador.buscar(ctx)?.takeIf { Actualizador.toca(ctx, it) } }
    val n = nueva ?: return
    DialogoActualizacion(n) {
        Actualizador.omitir(ctx, n)
        nueva = null
    }
}

/** El diálogo de «versión nueva»: lo usan el aviso al abrir y el botón «Buscar nueva versión» de Acerca. */
@Composable
fun DialogoActualizacion(n: Actualizador.Nueva, cerrar: () -> Unit) {
    val ctx = LocalContext.current
    var progreso by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var esperandoPermiso by remember { mutableStateOf<File?>(null) }
    val scope = rememberCoroutineScope()
    val permiso = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val f = esperandoPermiso ?: return@rememberLauncherForActivityResult
        esperandoPermiso = null
        if (ctx.packageManager.canRequestPackageInstalls()) Actualizador.instalar(ctx, f)
        else error = "Sin el permiso «Instalar apps desconocidas» para Lince, Android no deja instalar la actualización."
    }
    val instalar: (File) -> Unit = { f ->
        if (!Actualizador.mismaFirma(ctx, f)) {
            error = "Esta versión está firmada con otra llave que la Lince instalada, y Android no deja actualizar " +
                "encima. Desinstala Lince una vez y luego instala el APK nuevo."
        } else if (!ctx.packageManager.canRequestPackageInstalls()) {
            esperandoPermiso = f
            permiso.launch(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            )
        } else Actualizador.instalar(ctx, f)
    }

    AlertDialog(
        onDismissRequest = { if (progreso == null) cerrar() },
        containerColor = Superficie,
        title = { Text("Lince ${n.version} disponible", color = Color.White) },
        text = {
            Column {
                Text(n.notas.ifBlank { "Hay una versión nueva de Lince." }, color = TextoSec, fontSize = 14.sp)
                progreso?.let { p ->
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth(), color = Amarillo, trackColor = Linea)
                    Text("Bajando… ${(p * 100).toInt()} %", color = TextoSec, fontSize = 12.sp)
                }
                error?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = Color(0xFFFF6B6B), fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = progreso == null, onClick = {
                scope.launch {
                    progreso = 0f
                    error = null
                    try {
                        instalar(Actualizador.bajar(ctx, n) { progreso = it })
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = "No se pudo bajar: ${e.message ?: e}"
                    } finally {
                        progreso = null
                    }
                }
            }) {
                Text(if (progreso == null) "Actualizar (${tamTexto(n.tam)})" else "Bajando…", color = Amarillo)
            }
        },
        dismissButton = {
            if (progreso == null) TextButton(onClick = cerrar) { Text("Ahora no", color = TextoSec) }
        },
    )
}
