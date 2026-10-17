package com.edgarguitarist.lince

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

class DescargaWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        // Cada yt-dlp es un proceso de Python: con una lista entera, de a dos para no ahogar el teléfono.
        private val cupo = Semaphore(2)
    }

    private val notifId = id.hashCode()
    private val titulo get() = inputData.getString("titulo") ?: "Descarga"

    override suspend fun getForegroundInfo() = foreground("En cola", null)

    override suspend fun doWork(): Result {
        val url = inputData.getString("url") ?: return Result.failure()
        setForeground(foreground("En cola", null))
        val dir = File(applicationContext.getExternalFilesDir(null) ?: applicationContext.filesDir, "tmp/$id")
        dir.mkdirs()
        return try {
            cupo.withPermit {
                Motor.iniciar(applicationContext)
                Motor.actualizar(applicationContext)
                try {
                    ejecutar(url, dir, multihilo = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Algunos servidores rechazan las conexiones paralelas de aria2c: se reintenta con una sola.
                    if ((e.message ?: "").contains("aria2c exited")) {
                        dir.listFiles()?.forEach { it.deleteRecursively() }
                        ejecutar(url, dir, multihilo = false)
                    } else throw e
                }
            }
            val archivos = dir.listFiles().orEmpty()
                .filter { it.isFile && it.extension !in setOf("part", "ytdl", "jpg", "webp", "png", "json") }
            if (archivos.isEmpty()) error("yt-dlp terminó sin dejar ningún archivo")
            val principal = archivos.maxBy { it.length() }
            var uriPrincipal = ""
            for (f in archivos) {
                val u = Descargas.publicar(applicationContext, f)
                if (f == principal) uriPrincipal = u.toString()
            }
            notificarFin(uriPrincipal, Descargas.mime(principal.name))
            Result.success(workDataOf("uri" to uriPrincipal, "mime" to Descargas.mime(principal.name), "nombre" to principal.name))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = (e.message ?: e.toString()).lines().lastOrNull { it.contains("ERROR") }
                ?: (e.message ?: e.toString()).take(300)
            notificarError(msg)
            Result.failure(workDataOf("error" to msg))
        } finally {
            dir.deleteRecursively()
        }
    }

    private suspend fun ejecutar(url: String, dir: File, multihilo: Boolean) = coroutineScope {
        val tipo = inputData.getString("tipo") ?: "video"
        val alto = inputData.getInt("alto", 0)
        val sub = inputData.getString("sub")
        val directo = inputData.getString("directo")
        val req = YoutubeDLRequest(directo ?: url).apply {
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("--no-warnings")
            // Multihilo: los CDN limitan la velocidad por conexión. Medido en la PC el 2026-10-17, de 1 a 8 conexiones:
            // MP4 directo de un CDN 24 -> 176 Mbit/s, YouTube 1080p 10 -> 81 Mbit/s.
            // - HTTP directo (CDN de MP4): aria2c parte el archivo en 16 trozos.
            // - YouTube: aria2c falla contra googlevideo, así que `formats=dashy` convierte cada formato en fragmentos
            //   y el descargador nativo baja 8 a la vez (con progreso).
            // - HLS: nativo, 8 fragmentos a la vez.
            addOption("--concurrent-fragments", 8)
            if (multihilo) {
                // Sin --downloader-args propios: pisarían los que pone youtubedl-android (entre ellos --ca-certificate,
                // sin el cual aria2c no valida TLS en Android y sale con código 1). yt-dlp ya usa -x16 -s16 -k1M.
                // Los protocolos van por su nombre corto ("dash", no "http_dash_segments"): con el largo, yt-dlp
                // ignoraba aria2c del todo y bajaba con una sola conexión.
                addOption("--downloader", "libaria2c.so")
                addOption("--downloader", "dash,m3u8:native")
            }
            addOption("--embed-metadata")
            addOption("--extractor-args", Motor.YT_CLIENTES + ";formats=dashy")
            MotorJs.opciones(this)
            if (directo != null) {
                // URL directa sacada por el Navegador: el CDN pide el Referer de la página, y el nombre del
                // archivo sería el del CDN (480P_2000K_…), así que se usa el título.
                addOption("--referer", url)
                addOption("--user-agent", Navegador.UA)
                addOption("-o", "${dir.absolutePath}/${nombreArchivo(titulo)}.%(ext)s")
            } else {
                addOption("-o", "${dir.absolutePath}/%(title).120B [%(id)s].%(ext)s")
            }
            when (tipo) {
                "mp3", "m4a" -> {
                    addOption("-f", if (tipo == "m4a") "ba[ext=m4a]/ba/b" else "ba/b")
                    addOption("-x")
                    addOption("--audio-format", tipo)
                    addOption("--audio-quality", 0)
                    addOption("--embed-thumbnail")
                    addOption("--convert-thumbnails", "jpg")
                }
                else -> if (directo == null) {
                    addOption("-f", "bv*+ba/b")
                    // A igual resolución, H.264: YouTube sirve AV1 por defecto y muchos reproductores (o WhatsApp) no lo abren.
                    // Pasado 1080p no hay H.264 y se queda con VP9/AV1.
                    addOption("-S", if (alto > 0) "res:$alto,vcodec:h264,ext:mp4:m4a" else "vcodec:h264,ext:mp4:m4a")
                    addOption("--merge-output-format", "mp4")
                    if (sub != null) {
                        addOption(if (inputData.getBoolean("subAuto", false)) "--write-auto-subs" else "--write-subs")
                        addOption("--sub-langs", sub)
                        addOption("--convert-subs", "srt")
                        addOption("--embed-subs")
                    }
                }
            }
        }
        val pid = id.toString()
        var ultimo = -1
        val proceso = async(Dispatchers.IO) {
            YoutubeDL.getInstance().execute(req, pid) { progreso, eta, _ ->
                val p = progreso.toInt().coerceIn(0, 100)
                if (p != ultimo) {
                    ultimo = p
                    setProgressAsync(workDataOf("p" to p, "eta" to eta))
                    NotificationManagerCompat.from(applicationContext).let {
                        if (it.areNotificationsEnabled()) {
                            @Suppress("MissingPermission")
                            it.notify(notifId, notificacion(if (p >= 100) "Procesando…" else "$p %", p))
                        }
                    }
                }
            }
        }
        try {
            proceso.await()
        } catch (e: CancellationException) {
            YoutubeDL.getInstance().destroyProcessById(pid)
            throw e
        }
    }

    private fun nombreArchivo(t: String) =
        t.replace(Regex("""[\\/:*?"<>|%]"""), " ").replace(Regex("""\s+"""), " ").trim().take(120).ifBlank { "video" }

    private fun notificacion(texto: String, p: Int?) =
        NotificationCompat.Builder(applicationContext, Lince.CANAL_PROGRESO)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, p ?: 0, p == null)
            .addAction(0, "Cancelar", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()

    private fun foreground(texto: String, p: Int?) =
        ForegroundInfo(notifId, notificacion(texto, p), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    @Suppress("MissingPermission")
    private fun notificarFin(uri: String, mime: String) {
        val nm = NotificationManagerCompat.from(applicationContext)
        if (!nm.areNotificationsEnabled()) return
        val abrir = Intent(Intent.ACTION_VIEW).setDataAndType(android.net.Uri.parse(uri), mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(applicationContext, notifId, abrir, PendingIntent.FLAG_IMMUTABLE)
        nm.notify(
            notifId + 1,
            NotificationCompat.Builder(applicationContext, Lince.CANAL_LISTAS)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(titulo)
                .setContentText("Descargado en Descargas/${Descargas.CARPETA}")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
        )
    }

    @Suppress("MissingPermission")
    private fun notificarError(msg: String) {
        val nm = NotificationManagerCompat.from(applicationContext)
        if (!nm.areNotificationsEnabled()) return
        nm.notify(
            notifId + 1,
            NotificationCompat.Builder(applicationContext, Lince.CANAL_LISTAS)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Falló: $titulo")
                .setContentText(msg)
                .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
                .setAutoCancel(true)
                .build()
        )
    }
}
