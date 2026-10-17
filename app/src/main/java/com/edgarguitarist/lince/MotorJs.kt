package com.edgarguitarist.lince

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * El desafío JS de YouTube resuelto con el V8 del WebView del sistema: con el QuickJS de youtubedl-android se llevaba
 * 11 de los 17 s del análisis. yt-dlp carga el plugin `lince_jcp.py` (de los assets) con `--plugin-dirs`, y el
 * plugin le manda el script a este servidor en 127.0.0.1, que lo corre en un WebView invisible. Si algo falla,
 * yt-dlp vuelve solo a QuickJS.
 */
object MotorJs {
    private const val TAG = "LinceJs"
    private const val PLUGIN = "plugins/lince/yt_dlp_plugins/extractor/lince_jcp.py"

    @Volatile private var dir: File? = null
    private val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val main = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private lateinit var app: Context

    /** Copia el plugin, levanta el servidor y precalienta el WebView. Se llama una sola vez, con Python. */
    @Synchronized
    fun iniciar(ctx: Context) {
        if (dir != null) return
        app = ctx.applicationContext
        val base = File(app.filesDir, "jsc")
        File(base, PLUGIN).apply {
            parentFile!!.mkdirs()
            app.assets.open(PLUGIN).use { i -> outputStream().use { i.copyTo(it) } }
        }
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        thread(name = "lince-jsc", isDaemon = true) {
            while (true) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { atender(s) }
            }
        }
        // Se escribe al final: el plugin solo se da por disponible cuando el servidor ya escucha.
        File(base, "plugins/lince/conexion.json").writeText(JSONObject().put("port", server.localPort).put("token", token).toString())
        main.post { webView() }
        dir = File(base, "plugins")
    }

    /** Las opciones que hacen que yt-dlp use el WebView. */
    fun opciones(req: YoutubeDLRequest) {
        val d = dir ?: return
        req.addOption("--plugin-dirs", d.absolutePath)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun webView(): WebView = webView ?: WebView(app).also {
        it.settings.javaScriptEnabled = true
        it.loadDataWithBaseURL(null, "<html></html>", "text/html", "utf-8", null)
        webView = it
    }

    private fun atender(s: Socket) = s.use {
        it.soTimeout = 60_000
        val ent = BufferedInputStream(it.getInputStream())
        val cab = mutableMapOf<String, String>()
        val primera = linea(ent) ?: return
        while (true) {
            val l = linea(ent) ?: return
            if (l.isEmpty()) break
            val i = l.indexOf(':')
            if (i > 0) cab[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        val largo = cab["content-length"]?.toIntOrNull() ?: 0
        if (!primera.startsWith("POST /jsc ") || cab["x-token"] != token || largo <= 0) {
            responder(it, 403, "no")
            return
        }
        val cuerpo = ByteArray(largo)
        var leido = 0
        while (leido < largo) {
            val n = ent.read(cuerpo, leido, largo - leido)
            if (n < 0) return
            leido += n
        }
        val t0 = System.currentTimeMillis()
        val r = runCatching { ejecutar(String(cuerpo, Charsets.UTF_8)) }
        Log.i(TAG, "desafío resuelto en ${System.currentTimeMillis() - t0} ms (${largo / 1024} KB): ${r.isSuccess}")
        r.fold({ v -> responder(it, 200, v) }, { e -> responder(it, 500, e.toString()) })
    }

    private fun ejecutar(js: String): String {
        val f = CompletableFuture<String?>()
        main.post {
            runCatching { webView().evaluateJavascript(js) { f.complete(it) } }.onFailure { f.completeExceptionally(it) }
        }
        val crudo = f.get(55, TimeUnit.SECONDS)
        // evaluateJavascript devuelve el valor como JSON: aquí, un string con el JSON que espera yt-dlp.
        if (crudo == null || crudo == "null") error("el WebView no devolvió nada")
        return JSONArray("[$crudo]").getString(0)
    }

    private fun linea(i: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = i.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    private fun responder(s: Socket, codigo: Int, txt: String) {
        val b = txt.toByteArray(Charsets.UTF_8)
        val o = s.getOutputStream()
        o.write("HTTP/1.1 $codigo X\r\nContent-Type: application/json\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
        o.write(b)
        o.flush()
    }
}
