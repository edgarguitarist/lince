package com.edgarguitarist.lince

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Estados de WhatsApp: WhatsApp guarda los que uno ya vio en una carpeta oculta `.Statuses`. Desde Android 11 no se
 * puede leer directo, así que se pide acceso una vez con el selector de carpetas del sistema (abierto justo ahí) y
 * el permiso queda guardado.
 */
object Estados {
    enum class App(val etiqueta: String, val ruta: String) {
        WHATSAPP("WhatsApp", "Android/media/com.whatsapp/WhatsApp/Media/.Statuses"),
        BUSINESS("Business", "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/.Statuses"),
    }

    data class Estado(val uri: Uri, val nombre: String, val video: Boolean, val fecha: Long, val tam: Long)

    const val SUBCARPETA = "Estados"
    private const val AUTORIDAD = "com.android.externalstorage.documents"
    private val extensiones = setOf("jpg", "jpeg", "png", "webp", "gif", "mp4", "3gp", "mkv")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("estados", Context.MODE_PRIVATE)

    /** El selector de carpetas, abierto directamente en `.Statuses`. */
    fun intentAcceso(app: App): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        .putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(AUTORIDAD, "primary:" + app.ruta))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)

    /** Guarda el acceso si la carpeta elegida es una `.Statuses`; si el usuario eligió otra, devuelve false. */
    fun guardarAcceso(ctx: Context, app: App, arbol: Uri): Boolean {
        val id = runCatching { DocumentsContract.getTreeDocumentId(arbol) }.getOrNull() ?: return false
        if (!id.trimEnd('/').endsWith(".Statuses")) return false
        ctx.contentResolver.takePersistableUriPermission(arbol, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        prefs(ctx).edit().putString(app.name, arbol.toString()).apply()
        return true
    }

    fun acceso(ctx: Context, app: App): Uri? {
        val u = prefs(ctx).getString(app.name, null)?.let(Uri::parse) ?: return null
        // El permiso se pierde si se reinstala WhatsApp o el usuario lo revoca.
        return u.takeIf { ctx.contentResolver.persistedUriPermissions.any { p -> p.uri == u && p.isReadPermission } }
    }

    /** Los estados, del más nuevo al más viejo. */
    fun listar(ctx: Context, arbol: Uri): List<Estado> {
        val hijos = DocumentsContract.buildChildDocumentsUriUsingTree(arbol, DocumentsContract.getTreeDocumentId(arbol))
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val out = mutableListOf<Estado>()
        ctx.contentResolver.query(hijos, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val nombre = c.getString(1) ?: continue
                val ext = nombre.substringAfterLast('.', "").lowercase()
                if (ext !in extensiones) continue
                out += Estado(
                    uri = DocumentsContract.buildDocumentUriUsingTree(arbol, c.getString(0)),
                    nombre = nombre,
                    video = ext in setOf("mp4", "3gp", "mkv"),
                    fecha = c.getLong(2),
                    tam = c.getLong(3),
                )
            }
        }
        return out.sortedByDescending { it.fecha }
    }

    /** Copia el estado a Descargas/Lince/Estados. */
    fun guardar(ctx: Context, e: Estado): Uri {
        val uri = Descargas.publicar(ctx, e.nombre, SUBCARPETA) {
            ctx.contentResolver.openInputStream(e.uri) ?: error("No se pudo leer ${e.nombre}")
        }
        prefs(ctx).edit().putStringSet("guardados", guardados(ctx) + e.nombre).apply()
        return uri
    }

    /** Nombres ya guardados, para marcarlos con un check. */
    fun guardados(ctx: Context): Set<String> = prefs(ctx).getStringSet("guardados", emptySet())!!.toSet()
}
