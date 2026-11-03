package com.edgarguitarist.lince

import java.util.Locale

/** Un formato tal como lo devuelve yt-dlp en `formats`. */
data class Formato(
    val id: String,
    val ext: String,
    val alto: Int?,
    val vcodec: String?,
    val acodec: String?,
    val tam: Long?,
    /** Solo en lo sacado por [Navegador]: la URL directa del archivo. */
    val url: String? = null,
) {
    // Hay sitios que no informan los códecs: si trae altura es video y, salvo que diga "none", se asume que trae audio.
    val tieneVideo get() = vcodec != "none" && (vcodec != null || alto != null)
    val tieneAudio get() = acodec != "none"
    val soloAudio get() = vcodec == "none" && tieneAudio
}

data class Subtitulo(val codigo: String, val nombre: String, val automatico: Boolean)

data class Video(
    val url: String,
    val titulo: String,
    val miniatura: String?,
    val duracion: Double?,
    val autor: String?,
    val formatos: List<Formato>,
    val subtitulos: List<Subtitulo>,
    /** true si salió del [Navegador]: se baja la URL directa del formato, no la página. */
    val directo: Boolean = false,
)

/** La URL directa a bajar para la opción elegida (para audio, la calidad más liviana). */
fun Video.urlDirecta(op: Opcion): String? {
    if (!directo) return null
    val con = formatos.filter { it.url != null }
    return when (op) {
        is Opcion.Vid -> (con.firstOrNull { it.alto == op.alto } ?: con.maxByOrNull { it.alto ?: 0 })?.url
        is Opcion.Aud -> con.minByOrNull { it.alto ?: Int.MAX_VALUE }?.url
    }
}

data class Entrada(val url: String, val titulo: String, val duracion: Double?)

sealed interface Analisis {
    data class Unico(val video: Video) : Analisis
    data class Lista(val titulo: String, val entradas: List<Entrada>) : Analisis
}

/** Lo que el usuario elige en la hoja. */
sealed interface Opcion {
    val clave: String

    data class Vid(val alto: Int, val tam: Long?) : Opcion {
        override val clave get() = "v:$alto"
    }

    data class Aud(val formato: String, val tam: Long?) : Opcion {
        override val clave get() = "a:$formato"
    }
}

fun Video.alturas(): List<Int> =
    formatos.filter { it.tieneVideo }.mapNotNull { it.alto }.filter { it > 0 }.distinct().sorted()

private fun Formato.prefMp4() = if (ext == "mp4" || ext == "m4a") 1 else 0

private fun Video.mejorAudio(): Formato? =
    formatos.filter { it.soloAudio }.maxWithOrNull(compareBy({ it.prefMp4() }, { it.tam ?: 0L }))

/** Estima el tamaño de lo que bajará `-S res:<alto>,ext:mp4:m4a`. */
fun Video.tamVideo(alto: Int): Long? {
    val v = formatos.filter { it.tieneVideo && it.alto == alto }
        .maxWithOrNull(compareBy({ it.prefMp4() }, { it.tam ?: 0L })) ?: return null
    val tv = v.tam ?: return null
    if (v.tieneAudio) return tv
    return tv + (mejorAudio()?.tam ?: 0)
}

fun Video.tamAudio(): Long? = mejorAudio()?.tam

/** Las dos opciones rápidas de la hoja: hasta 480p y la mejor hasta 1080p. */
fun Video.opcionesRapidas(): List<Pair<String, Opcion.Vid>> {
    val hs = alturas()
    if (hs.isEmpty()) return listOf("Mejor calidad" to Opcion.Vid(0, null))
    val rapido = hs.lastOrNull { it <= 480 } ?: hs.first()
    val alta = hs.lastOrNull { it <= 1080 } ?: hs.last()
    val out = mutableListOf("Rápido (${rapido}p)" to Opcion.Vid(rapido, tamVideo(rapido)))
    if (alta != rapido) out += "Alta calidad (${alta}p)" to Opcion.Vid(alta, tamVideo(alta))
    return out
}

fun Video.todasLasOpciones(): List<Pair<String, Opcion>> =
    alturas().reversed().map { "${it}p" to Opcion.Vid(it, tamVideo(it)) as Opcion } +
        listOf(
            "MP3 (audio)" to Opcion.Aud("mp3", tamAudio()),
            "M4A (audio)" to Opcion.Aud("m4a", tamAudio()),
        )

private val es = Locale.forLanguageTag("es")

fun tamTexto(b: Long?): String {
    if (b == null || b <= 0) return ""
    val mb = b / 1048576.0
    return if (mb >= 1024) String.format(es, "%.2f GB", mb / 1024) else String.format(es, "%.1f MB", mb)
}

fun duracionTexto(s: Double?): String {
    if (s == null || s <= 0) return ""
    val t = s.toLong()
    val h = t / 3600
    val m = (t % 3600) / 60
    val ss = t % 60
    return if (h > 0) String.format(es, "%d:%02d:%02d", h, m, ss) else String.format(es, "%d:%02d", m, ss)
}

private val urlRegex = Regex("""https?://\S+""")

fun extraerUrl(texto: String?): String? = texto?.let { urlRegex.find(it)?.value?.trimEnd('.', ',', ')', '"', '\'') }
