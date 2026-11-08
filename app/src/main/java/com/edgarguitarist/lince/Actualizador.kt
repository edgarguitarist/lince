package com.edgarguitarist.lince

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Avisa de las versiones nuevas publicadas en GitHub y baja el APK de la misma arquitectura que la instalada. */
object Actualizador {
    private const val API = "https://api.github.com/repos/edgarguitarist/lince/releases/latest"

    data class Nueva(val version: String, val notas: String, val url: String, val tam: Long)

    private fun versionActual(ctx: Context) =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "0"

    /** Con las librerías extraídas (useLegacyPackaging), la carpeta dice la arquitectura del APK instalado. */
    private fun abi(ctx: Context) = when (File(ctx.applicationInfo.nativeLibraryDir).name) {
        "arm64" -> "arm64-v8a"
        "arm" -> "armeabi-v7a"
        "x86_64" -> "x86_64"
        else -> Build.SUPPORTED_ABIS[0]
    }

    /** La versión nueva, o null si no hay (o no se pudo consultar). Las builds debug no preguntan. */
    suspend fun buscar(ctx: Context): Nueva? = withContext(Dispatchers.IO) {
        if (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) return@withContext null
        runCatching {
            val j = JSONObject(abrir(API).inputStream.bufferedReader().use { it.readText() })
            val version = j.getString("tag_name").removePrefix("v")
            if (comparar(version, versionActual(ctx)) <= 0) {
                File(ctx.cacheDir, "actualizacion").deleteRecursively() // el APK de la actualización ya instalada
                return@runCatching null
            }
            val assets = j.getJSONArray("assets")
            val a = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith("-${abi(ctx)}.apk") } ?: return@runCatching null
            Nueva(version, notas(j.optString("body")), a.getString("browser_download_url"), a.optLong("size"))
        }.getOrNull()
    }

    /** "Ahora no": no vuelve a preguntar por esa versión hasta el día siguiente. */
    fun omitir(ctx: Context, n: Nueva) = prefs(ctx).edit()
        .putString("omitida", n.version).putLong("omitida_en", System.currentTimeMillis()).apply()

    fun toca(ctx: Context, n: Nueva): Boolean {
        val p = prefs(ctx)
        return p.getString("omitida", null) != n.version ||
            System.currentTimeMillis() - p.getLong("omitida_en", 0) > 24 * 3600_000L
    }

    suspend fun bajar(ctx: Context, n: Nueva, progreso: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "actualizacion")
        val f = File(dir, "Lince-${n.version}.apk")
        if (n.tam > 0 && f.length() == n.tam) return@withContext f // ya bajado: se canceló el instalador
        dir.deleteRecursively()
        dir.mkdirs()
        val c = abrir(n.url)
        val total = c.contentLengthLong.takeIf { it > 0 } ?: n.tam
        c.inputStream.use { i ->
            f.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                var leidos = 0L
                while (true) {
                    ensureActive()
                    val r = i.read(buf)
                    if (r < 0) break
                    o.write(buf, 0, r)
                    leidos += r
                    if (total > 0) progreso(leidos.toFloat() / total)
                }
            }
        }
        if (n.tam > 0 && f.length() != n.tam) {
            f.delete()
            error("la descarga quedó incompleta")
        }
        f
    }

    /** Abre el instalador de Android; la primera vez pide permitir instalar apps desde Lince. */
    fun instalar(ctx: Context, f: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.archivos", f)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** 0.6.10 > 0.6.9: compara número a número, no como texto. */
    internal fun comparar(a: String, b: String): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return 0
    }

    /** De las notas del release, solo lo que cambió (sin la tabla de APKs ni las instrucciones). */
    private fun notas(body: String) = body.substringBefore("## ¿Cuál").lines()
        .filterNot { it.startsWith("#") }.joinToString("\n").replace("**", "").replace("`", "").trim()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("actualizador", Context.MODE_PRIVATE)

    private fun abrir(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", "Lince") // la API de GitHub rechaza pedidos sin User-Agent
            connectTimeout = 15_000
            readTimeout = 30_000
            if (responseCode != 200) error("GitHub respondió $responseCode")
        }
}
