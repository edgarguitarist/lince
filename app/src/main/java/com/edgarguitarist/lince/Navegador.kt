package com.edgarguitarist.lince

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/**
 * Plan B cuando un sitio bloquea a yt-dlp por su huella TLS (algunos responden 403/410 si no es un navegador real,
 * y en Android no hay curl_cffi para imitarlo). Se abre la página en un WebView invisible, que *es* Chrome,
 * y de ahí se sacan las URLs directas del video; esas sí las baja yt-dlp sin problema.
 * Si la página no tiene video, entrega sus fotos: así se bajan las publicaciones de fotos de las redes sociales.
 * Lo que corre dentro de la página está en `assets/navegador.js`.
 */
object Navegador {
    // Escritorio, como hace yt-dlp (`platform=pc`): la página de PC trae `flashvars_*` con todas las calidades.
    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"

    private val reAlto = Regex("""(\d{3,4})[pP]_\d+[kK]""")

    /** Con `soloImagenes` no busca videos: es para sacar las fotos de una página cuyo video ya se conoce. */
    suspend fun analizar(ctx: Context, url: String, soloImagenes: Boolean = false): Analisis? {
        val json = withContext(Dispatchers.Main) {
            runCatching { withTimeout(40_000) { cargar(ctx.applicationContext, url, soloImagenes) } }.getOrNull()
        } ?: return null
        val j = JSONObject(json)
        video(j, url)?.let { return Analisis.Unico(it) }
        val arr = j.optJSONArray("images")
        val fotos = (0 until (arr?.length() ?: 0))
            .mapNotNull { i -> arr!!.optJSONObject(i)?.optString("url")?.takeIf { it.startsWith("http") } }
            .map { Imagen(it) }
        if (fotos.isEmpty()) return null
        return Analisis.Imagenes(j.optString("title").ifBlank { "Imágenes" }, url, fotos)
    }

    private suspend fun video(j: JSONObject, url: String): Video? {
        val arr = j.optJSONArray("formats") ?: return null
        val crudos = (0 until arr.length()).mapNotNull { i ->
            val f = arr.optJSONObject(i) ?: return@mapNotNull null
            val u = f.optString("url").takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val h = f.optInt("h", 0).takeIf { it > 0 } ?: reAlto.find(u)?.groupValues?.get(1)?.toInt()
            u to h
        }
        // Si hay MP4 con calidad conocida, el HLS sobra.
        val mp4 = crudos.filter { it.second != null && !it.first.contains(".m3u8") }
        val elegidos = (mp4.ifEmpty { crudos }).distinctBy { it.second ?: it.first.hashCode() }
        if (elegidos.isEmpty()) return null
        val referer = url
        val tams = coroutineScope {
            elegidos.map { (u, _) -> async(Dispatchers.IO) { if (u.contains(".m3u8")) null else tamano(u, referer) } }.awaitAll()
        }
        val formatos = elegidos.mapIndexed { i, (u, h) ->
            Formato(id = "web-${h ?: i}", ext = "mp4", alto = h, vcodec = null, acodec = null, tam = tams[i], url = u)
        }
        val dur = j.optDouble("dur").takeIf { !it.isNaN() && it > 0 }
        return Video(
            url = url,
            titulo = j.optString("title").ifBlank { "Video" },
            miniatura = j.optString("thumb").takeIf { it.startsWith("http") },
            duracion = dur,
            autor = null,
            formatos = formatos,
            subtitulos = emptyList(),
            directo = true,
        )
    }

    /** Tamaño real con un HEAD, para mostrarlo en la hoja como Snaptube. */
    private fun tamano(u: String, referer: String): Long? = runCatching {
        val c = URL(u).openConnection() as HttpURLConnection
        c.requestMethod = "HEAD"
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Referer", referer)
        try {
            if (c.responseCode in 200..299) c.contentLengthLong.takeIf { it > 0 } else null
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private suspend fun cargar(ctx: Context, url: String, soloImagenes: Boolean): String? = suspendCancellableCoroutine { cont ->
        val cm = CookieManager.getInstance()
        val host = runCatching { URL(url).host.split('.').takeLast(2).joinToString(".") }.getOrDefault("")
        // Las mismas cookies que pone yt-dlp: saltan el aviso de mayoría de edad y piden la versión de PC.
        listOf("age_verified=1", "accessAgeDisclaimerPH=1", "accessAgeDisclaimerUK=1", "accessPH=1", "platform=pc")
            .forEach { cm.setCookie("https://$host", "$it; Domain=.$host; Path=/") }
        cm.flush()

        val web = WebView(ctx)
        // Sin estar en pantalla el WebView mide 0x0, y las páginas no cargan las imágenes que «no se ven»:
        // se le da el tamaño de una ventana de escritorio.
        web.measure(
            View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY),
        )
        web.layout(0, 0, 1280, 2400)
        var listo = false
        fun terminar(r: String?) {
            if (listo) return
            listo = true
            web.post { web.stopLoading(); web.destroy() }
            if (cont.isActive) cont.resume(r)
        }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = UA
            mediaPlaybackRequiresUserGesture = true
            // Las imágenes no se bloquean (antes sí, por datos): hacen falta sus medidas para saber cuáles son las
            // fotos, y en un WebView fuera de pantalla desbloquearlas después ya no las trae (medido el 2026-11-15).
        }
        web.addJavascriptInterface(object {
            @JavascriptInterface
            fun listo(json: String) = terminar(json)
        }, "Lince")
        val js = "window.LINCE_SOLO_IMG=$soloImagenes;" + ctx.assets.open("navegador.js").bufferedReader().use { it.readText() }
        web.webViewClient = object : WebViewClient() {
            // En cada página que termina de cargar: hay sitios que pasan primero por una de verificación y redirigen,
            // y el script de la primera muere con ella. El propio script evita correr dos veces en la misma página.
            override fun onPageFinished(view: WebView, u: String?) {
                if (listo) return
                view.evaluateJavascript(js, null)
            }
        }
        cont.invokeOnCancellation { web.post { web.destroy() } }
        web.loadUrl(url)
    }
}
