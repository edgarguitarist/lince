package com.edgarguitarist.lince

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Las fotos que se consiguen sin abrir la página: un enlace directo a una imagen y los tuits. */
object Fotos {
    private val reImagen = Regex("""\.(jpe?g|png|webp|gif|avif|bmp)$""", RegexOption.IGNORE_CASE)
    private val reTuit = Regex("""https?://(?:www\.|mobile\.)?(?:twitter|x)\.com/[^/]+/status/(\d+)""")

    /** El enlace ya es una imagen: no hay nada que analizar. */
    fun directa(url: String): Analisis.Imagenes? {
        val ruta = runCatching { URL(url).path }.getOrNull() ?: return null
        if (!reImagen.containsMatchIn(ruta)) return null
        return Analisis.Imagenes(ruta.substringAfterLast('/').substringBeforeLast('.').ifBlank { "Imagen" }, url, listOf(Imagen(url)))
    }

    class Tuit(val titulo: String, val imagenes: List<Imagen>, val conVideo: Boolean)

    /**
     * X no muestra los tuits sin iniciar sesión y yt-dlp solo saca videos, pero la API pública de los tuits
     * insertados entrega las fotos (medido el 2026-11-14). Devuelve null si el enlace no es un tuit o la API falla.
     */
    suspend fun tuit(url: String): Tuit? = withContext(Dispatchers.IO) {
        val id = reTuit.find(url)?.groupValues?.get(1) ?: return@withContext null
        runCatching {
            val c = URL("https://cdn.syndication.twimg.com/tweet-result?id=$id&token=a").openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty("User-Agent", Navegador.UA)
            val j = try {
                JSONObject(c.inputStream.bufferedReader().readText())
            } finally {
                c.disconnect()
            }
            val medios = j.optJSONArray("mediaDetails")
            val lista = (0 until (medios?.length() ?: 0)).mapNotNull { medios!!.optJSONObject(it) }
            val fotos = lista.filter { it.optString("type") == "photo" }
                .mapNotNull { m -> m.optString("media_url_https").takeIf { it.startsWith("http") } }
                .map { Imagen("$it?name=orig", "$it?name=small") }
            val autor = j.optJSONObject("user")?.optString("name").orEmpty()
            val texto = j.optString("text").substringBefore("https://t.co/").substringBefore("http://t.co/").trim()
            Tuit(listOf(autor, texto).filter { it.isNotBlank() }.joinToString(": ").ifBlank { "Tuit $id" }, fotos, lista.size > fotos.size)
        }.getOrNull()
    }
}
