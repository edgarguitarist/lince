package com.edgarguitarist.lince

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object Descargas {
    const val TAG = "descarga"
    const val CARPETA = "Lince"

    /** Encola una descarga. `sub` es el código de idioma de los subtítulos, o null. */
    fun encolar(ctx: Context, url: String, titulo: String, opcion: Opcion, sub: Subtitulo? = null, directo: String? = null) {
        val (tipo, alto) = when (opcion) {
            is Opcion.Vid -> "video" to opcion.alto
            is Opcion.Aud -> opcion.formato to 0
        }
        val req = OneTimeWorkRequestBuilder<DescargaWorker>()
            .setInputData(
                workDataOf(
                    "url" to url,
                    "titulo" to titulo,
                    "tipo" to tipo,
                    "alto" to alto,
                    "sub" to sub?.codigo,
                    "subAuto" to (sub?.automatico ?: false),
                    "directo" to directo,
                )
            )
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG)
            // WorkInfo no expone el inputData: el título y el tipo viajan como etiquetas para la lista.
            .addTag("titulo:" + titulo.take(200))
            .addTag("tipo:" + if (tipo == "video" && alto > 0) "${alto}p" else tipo.uppercase())
            .build()
        WorkManager.getInstance(ctx).enqueue(req)
    }

    fun mime(nombre: String): String {
        val ext = nombre.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "srt" -> "application/x-subrip"
            "vtt" -> "text/vtt"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }

    /** Copia el archivo terminado a Descargas/Lince, donde lo ven la galería y el explorador. */
    fun publicar(ctx: Context, archivo: File): Uri = publicar(ctx, archivo.name, null) { archivo.inputStream() }

    /** Copia lo que entregue `abrir` a Descargas/Lince[/subcarpeta] con el nombre dado. */
    fun publicar(ctx: Context, nombre: String, subcarpeta: String?, abrir: () -> InputStream): Uri {
        val ruta = listOfNotNull(CARPETA, subcarpeta).joinToString("/")
        return if (Build.VERSION.SDK_INT >= 29) publicarMediaStore(ctx, nombre, ruta, abrir)
        else publicarLegado(ctx, nombre, ruta, abrir)
    }

    @RequiresApi(29)
    private fun publicarMediaStore(ctx: Context, nombre: String, ruta: String, abrir: () -> InputStream): Uri {
        val r = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, nombre)
            put(MediaStore.Downloads.MIME_TYPE, mime(nombre))
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + ruta)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("No se pudo crear el archivo en Descargas")
        try {
            r.openOutputStream(uri)!!.use { out -> abrir().use { it.copyTo(out, 1 shl 16) } }
        } catch (e: Exception) {
            r.delete(uri, null, null)
            throw e
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        r.update(uri, values, null, null)
        return uri
    }

    /** Android 9: no hay MediaStore.Downloads; se copia a mano y el escáner de medios entrega la URI content://. */
    @Suppress("DEPRECATION")
    private fun publicarLegado(ctx: Context, nombre: String, ruta: String, abrir: () -> InputStream): Uri {
        val carpeta = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), ruta)
        carpeta.mkdirs()
        var destino = File(carpeta, nombre)
        var n = 1
        while (destino.exists()) destino = File(carpeta, "${destino.nameWithoutExtension.substringBefore(" (")} (${n++}).${destino.extension}")
        destino.outputStream().use { out -> abrir().use { it.copyTo(out, 1 shl 16) } }
        val listo = CountDownLatch(1)
        var uri: Uri? = null
        MediaScannerConnection.scanFile(ctx, arrayOf(destino.absolutePath), arrayOf(mime(destino.name))) { _, u ->
            uri = u
            listo.countDown()
        }
        listo.await(10, TimeUnit.SECONDS)
        return uri ?: error("El escáner de medios no registró ${destino.name}")
    }
}
