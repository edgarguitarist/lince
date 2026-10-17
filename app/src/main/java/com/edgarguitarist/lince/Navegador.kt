package com.edgarguitarist.lince

import android.annotation.SuppressLint
import android.content.Context
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
 */
object Navegador {
    // Escritorio, como hace yt-dlp (`platform=pc`): la página de PC trae `flashvars_*` con todas las calidades.
    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"

    private val reAlto = Regex("""(\d{3,4})[pP]_\d+[kK]""")

    suspend fun analizar(ctx: Context, url: String): Video? {
        val json = withContext(Dispatchers.Main) {
            runCatching { withTimeout(30_000) { cargar(ctx.applicationContext, url) } }.getOrNull()
        } ?: return null
        val j = JSONObject(json)
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
    private suspend fun cargar(ctx: Context, url: String): String? = suspendCancellableCoroutine { cont ->
        val cm = CookieManager.getInstance()
        val host = runCatching { URL(url).host.split('.').takeLast(2).joinToString(".") }.getOrDefault("")
        // Las mismas cookies que pone yt-dlp: saltan el aviso de mayoría de edad y piden la versión de PC.
        listOf("age_verified=1", "accessAgeDisclaimerPH=1", "accessAgeDisclaimerUK=1", "accessPH=1", "platform=pc")
            .forEach { cm.setCookie("https://$host", "$it; Domain=.$host; Path=/") }
        cm.flush()

        val web = WebView(ctx)
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
            blockNetworkImage = true // solo interesa el HTML: menos datos y más rápido
        }
        web.addJavascriptInterface(object {
            @JavascriptInterface
            fun listo(json: String) = terminar(json)
        }, "Lince")
        var intentos = 0
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, u: String?) {
                if (listo || intentos++ > 0) return
                view.evaluateJavascript(JS, null)
            }
        }
        cont.invokeOnCancellation { web.post { web.destroy() } }
        web.loadUrl(url)
    }

    private val JS = """
        (async () => {
          const out = { formats: [] };
          const vistos = new Set();
          const add = (u, h) => {
            if (!u || typeof u !== 'string' || u.startsWith('blob:')) return;
            u = u.replace(/\\\//g, '/');
            if (vistos.has(u)) return;
            vistos.add(u);
            out.formats.push({ url: u, h: parseInt(h) || 0 });
          };
          const meta = s => (document.querySelector(s) || {}).content;
          // El reproductor a veces define flashvars después del onload: se espera hasta 8 s.
          let fv = null;
          for (let i = 0; i < 16 && !fv; i++) {
            const k = Object.keys(window).find(k => /^flashvars_\d+$/.test(k));
            if (k) fv = window[k]; else await new Promise(r => setTimeout(r, 500));
          }
          if (fv) {
            out.thumb = fv.image_url;
            out.dur = +fv.video_duration;
            out.title = fv.video_title;
            for (const d of (fv.mediaDefinitions || [])) {
              if (!d || !d.videoUrl) continue;
              if (d.videoUrl.includes('/video/get_media')) {
                try {
                  const r = await fetch(d.videoUrl, { credentials: 'include' });
                  for (const m of await r.json()) if (m && m.videoUrl) add(m.videoUrl, m.quality);
                } catch (e) {}
              } else add(d.videoUrl, Array.isArray(d.quality) ? 0 : d.quality);
            }
          }
          if (!out.formats.length) {
            document.querySelectorAll('video, video source').forEach(v => add(v.currentSrc || v.src, v.videoHeight));
            add(meta('meta[property="og:video:secure_url"]'));
            add(meta('meta[property="og:video"]'));
            add(meta('meta[property="og:video:url"]'));
            (document.documentElement.innerHTML.match(/https?:(?:\\?\/){2}[^"'\s<>]+?\.(?:mp4|m3u8)(?:\?[^"'\s<>]*)?/g) || []).forEach(u => add(u));
          }
          out.title = meta('meta[name="twitter:title"]') || meta('meta[property="og:title"]') || out.title || document.title;
          out.thumb = out.thumb || meta('meta[property="og:image"]');
          Lince.listo(JSON.stringify(out));
        })();
    """.trimIndent()
}
