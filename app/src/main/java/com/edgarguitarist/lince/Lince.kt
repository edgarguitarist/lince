package com.edgarguitarist.lince

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class Lince : Application(), ImageLoaderFactory {
    companion object {
        const val CANAL_PROGRESO = "progreso"
        const val CANAL_LISTAS = "listas"
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL_PROGRESO, "Descargas en curso", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CANAL_LISTAS, "Descargas terminadas", NotificationManager.IMPORTANCE_DEFAULT))
        // Deja Python y yt-dlp listos (y al día) antes de que llegue el primer enlace.
        scope.launch { runCatching { Motor.iniciar(this@Lince); Motor.actualizar(this@Lince) } }
    }

    // Miniaturas de los estados en video: Coil saca un cuadro del MP4.
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this).components { add(VideoFrameDecoder.Factory()) }.crossfade(true).build()
}
