package com.edgarguitarist.lince

import android.content.Context
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

/** yt-dlp + ffmpeg embebidos (youtubedl-android). */
object Motor {
    /**
     * Desde un teléfono (IPv4, sin cookies) YouTube responde «Sign in to confirm you're not a bot» a los clientes por
     * defecto. Medido el 2026-10-17 en el teléfono: `web_embedded` da todas las calidades (hasta 2160p) y `mweb`
     * cubre los videos que no se dejan insertar (solo hasta 360p).
     */
    const val YT_CLIENTES = "youtube:player_client=web_embedded,mweb"

    /** Para analizar basta `web_embedded`: `mweb` suma dos pedidos (~0,8 s) y solo se usa si el video no se deja insertar. */
    private const val YT_RAPIDO = "youtube:player_client=web_embedded"

    private val mutexInicio = Mutex()
    private val mutexUpdate = Mutex()
    @Volatile private var iniciado = false

    suspend fun iniciar(ctx: Context) = mutexInicio.withLock {
        if (iniciado) return@withLock
        withContext(Dispatchers.IO) {
            YoutubeDL.getInstance().init(ctx.applicationContext)
            FFmpeg.getInstance().init(ctx.applicationContext)
            Aria2c.getInstance().init(ctx.applicationContext)
            runCatching { MotorJs.iniciar(ctx) }
        }
        iniciado = true
    }

    /** Los sitios cambian seguido: yt-dlp se actualiza solo una vez al día. Devuelve true si cambió. */
    suspend fun actualizar(ctx: Context, forzar: Boolean = false): Boolean = mutexUpdate.withLock {
        iniciar(ctx)
        val prefs = ctx.getSharedPreferences("motor", Context.MODE_PRIVATE)
        val ultima = prefs.getLong("ultima_actualizacion", 0)
        if (!forzar && System.currentTimeMillis() - ultima < 24 * 3600_000L) return@withLock false
        withContext(Dispatchers.IO) {
            runCatching {
                val r = YoutubeDL.getInstance().updateYoutubeDL(ctx.applicationContext, YoutubeDL.UpdateChannel.STABLE)
                prefs.edit().putLong("ultima_actualizacion", System.currentTimeMillis()).apply()
                r == YoutubeDL.UpdateStatus.DONE
            }.getOrDefault(false)
        }
    }

    fun version(ctx: Context): String? = runCatching { YoutubeDL.getInstance().versionName(ctx) }.getOrNull()

    suspend fun analizar(ctx: Context, url: String): Analisis {
        iniciar(ctx)
        // Los sitios donde yt-dlp ya fue bloqueado van directo al navegador: ahorra los segundos del intento fallido.
        val host = runCatching { java.net.URI(url).host.removePrefix("www.").removePrefix("m.") }.getOrNull()
        val prefs = ctx.getSharedPreferences("motor", Context.MODE_PRIVATE)
        val conNavegador = prefs.getStringSet("hosts_navegador", emptySet())!!
        if (host != null && host in conNavegador) {
            Navegador.analizar(ctx, url)?.let { return Analisis.Unico(it) }
        }
        return try {
            analizarUnaVez(url)
        } catch (e: Exception) {
            // Primero el navegador (sitios que bloquean a yt-dlp por su huella TLS); si tampoco, puede ser un
            // yt-dlp viejo: se actualiza y se reintenta una vez. Con YouTube el navegador no sirve: solo se
            // quedaba 30 s esperando (por ejemplo, ante un 429 por muchos pedidos).
            if (!esYoutube(url)) Navegador.analizar(ctx, url)?.let {
                if (host != null) prefs.edit().putStringSet("hosts_navegador", conNavegador + host).apply()
                return Analisis.Unico(it)
            }
            if (actualizar(ctx, forzar = true)) analizarUnaVez(url) else throw e
        }
    }

    private suspend fun analizarUnaVez(url: String): Analisis {
        val r = try {
            analizarCon(url, YT_RAPIDO)
        } catch (e: Exception) {
            if (!esYoutube(url)) throw e
            null
        }
        val sinFormatos = r is Analisis.Unico && r.video.formatos.none { it.alto != null }
        return if (r == null || (sinFormatos && esYoutube(url))) analizarCon(url, YT_CLIENTES) else r
    }

    private fun esYoutube(url: String) = runCatching { java.net.URI(url).host }.getOrNull()
        ?.let { it == "youtu.be" || it == "youtube.com" || it.endsWith(".youtube.com") } == true

    private suspend fun analizarCon(url: String, clientes: String): Analisis = withContext(Dispatchers.IO) {
        val req = YoutubeDLRequest(url).apply {
            addOption("-J")
            addOption("--flat-playlist")
            addOption("--no-playlist") // un enlace watch?v=…&list=… es el video, no la lista
            addOption("--no-warnings")
            addOption("--extractor-args", clientes)
            MotorJs.opciones(this)
        }
        val json = JSONObject(YoutubeDL.getInstance().execute(req).out)
        parsear(json, url)
    }

    private fun JSONObject.str(k: String): String? = optString(k, "").takeIf { it.isNotBlank() && it != "null" }
    private fun JSONObject.num(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() } else null

    private fun parsear(j: JSONObject, url: String): Analisis {
        if (j.optString("_type") == "playlist") {
            val arr = j.optJSONArray("entries")
            val entradas = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
                val e = arr!!.optJSONObject(i) ?: return@mapNotNull null
                val u = e.str("url") ?: e.str("webpage_url") ?: return@mapNotNull null
                Entrada(u, e.str("title") ?: u, e.num("duration"))
            }
            return Analisis.Lista(j.str("title") ?: "Lista", entradas)
        }
        val dur = j.num("duration")
        val fArr = j.optJSONArray("formats")
        val formatos = (0 until (fArr?.length() ?: 0)).mapNotNull { i ->
            val f = fArr!!.optJSONObject(i) ?: return@mapNotNull null
            if (f.str("format_note")?.contains("storyboard") == true || f.str("ext") == "mhtml") return@mapNotNull null
            val tbr = f.num("tbr")
            val tam = f.num("filesize")?.toLong()
                ?: f.num("filesize_approx")?.toLong()
                ?: if (tbr != null && dur != null) (tbr * 1000 / 8 * dur).toLong() else null
            Formato(
                id = f.str("format_id") ?: return@mapNotNull null,
                ext = f.str("ext") ?: "",
                alto = f.num("height")?.toInt(),
                vcodec = f.str("vcodec"),
                acodec = f.str("acodec"),
                tam = tam,
            )
        }
        return Analisis.Unico(
            Video(
                url = j.str("webpage_url") ?: url,
                titulo = j.str("title") ?: "Sin título",
                miniatura = j.str("thumbnail"),
                duracion = dur,
                autor = j.str("uploader") ?: j.str("channel"),
                formatos = formatos,
                subtitulos = subtitulos(j),
            )
        )
    }

    private fun subtitulos(j: JSONObject): List<Subtitulo> {
        val out = mutableListOf<Subtitulo>()
        j.optJSONObject("subtitles")?.let { s ->
            s.keys().forEach { k -> if (k != "live_chat") out += Subtitulo(k, nombreIdioma(k, s), false) }
        }
        // Los automáticos de YouTube traen cien traducciones: solo se ofrecen español, inglés y el idioma del video.
        val idioma = j.str("language")
        j.optJSONObject("automatic_captions")?.let { s ->
            s.keys().forEach { k ->
                val base = k.substringBefore('-')
                val util = base == "es" || base == "en" || base == idioma
                if (util && out.none { it.codigo == k }) {
                    out += Subtitulo(k, nombreIdioma(k, s) + " (automático)", true)
                }
            }
        }
        return out.sortedWith(compareBy({ !it.codigo.startsWith("es") }, { it.automatico }, { it.nombre }))
    }

    private fun nombreIdioma(k: String, s: JSONObject): String {
        val n = s.optJSONArray(k)?.optJSONObject(0)?.str("name")
        if (n != null) return n
        val loc = Locale.forLanguageTag(k)
        return loc.getDisplayName(Locale.forLanguageTag("es")).replaceFirstChar { it.uppercase() }.ifBlank { k }
    }
}
