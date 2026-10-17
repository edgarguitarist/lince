package com.edgarguitarist.lince

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** adb shell am broadcast -n com.edgarguitarist.lince/.Prueba --es url URL --es args "arg1|arg2" */
class Prueba : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val pr = goAsync()
        val app = ctx.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Motor.iniciar(app)
                val alto = i.getIntExtra("descargar", 0)
                if (alto > 0) {
                    // La descarga real por DescargaWorker, como desde la hoja; espera a que termine.
                    val titulo = "prueba-${System.currentTimeMillis()}"
                    val t0 = System.currentTimeMillis()
                    Descargas.encolar(app, i.getStringExtra("url")!!, titulo, Opcion.Vid(alto, null))
                    val wm = androidx.work.WorkManager.getInstance(app)
                    while (true) {
                        kotlinx.coroutines.delay(1000)
                        val w = wm.getWorkInfosByTag("titulo:$titulo").get().firstOrNull() ?: continue
                        if (w.state.isFinished) {
                            Log.i("LincePrueba", "DESCARGA ${w.state} en ${System.currentTimeMillis() - t0} ms ${w.outputData.keyValueMap}")
                            break
                        }
                    }
                    Log.i("LincePrueba", "FIN")
                    return@launch
                }
                if (i.getBooleanExtra("motor", false)) {
                    // El mismo camino que la hoja: cuánto tarda y hasta qué calidad llega.
                    val t0 = System.currentTimeMillis()
                    val r = runCatching { Motor.analizar(app, i.getStringExtra("url")!!) }
                    val alto = (r.getOrNull() as? Analisis.Unico)?.video?.formatos?.mapNotNull { it.alto }?.maxOrNull()
                    Log.i("LincePrueba", "MOTOR ${System.currentTimeMillis() - t0} ms, hasta ${alto}p ${r.exceptionOrNull() ?: ""}")
                    Log.i("LincePrueba", "FIN")
                    return@launch
                }
                val req = YoutubeDLRequest(i.getStringExtra("url")!!)
                // addCommands y no addOption: la librería agrupa las opciones con el mismo nombre y desordenaría los argumentos.
                i.getStringExtra("args")?.split('|')?.filter { it.isNotBlank() }?.let { req.addCommands(it) }
                if (i.getBooleanExtra("webview", true)) MotorJs.opciones(req)
                val r = runCatching { YoutubeDL.getInstance().execute(req) }
                val completo = r.fold({ "OK\n" + it.out + "\n" + it.err }, { "FALLO\n" + (it.message ?: it.toString()) })
                // Salida entera para `adb shell cat /sdcard/Android/data/<paquete>/files/prueba.txt`; logcat corta las líneas largas.
                java.io.File(app.getExternalFilesDir(null) ?: app.cacheDir, "prueba.txt").writeText(completo)
                val txt = r.fold({ "OK\n" + it.out.take(3000) + "\n" + it.err.takeLast(8000) }, { "FALLO\n" + (it.message ?: it.toString()).takeLast(3000) })
                txt.chunked(3000).forEach { Log.i("LincePrueba", it) }
                Log.i("LincePrueba", "FIN")
            } finally {
                pr.finish()
            }
        }
    }
}
