# Lince

Descargador para Android al estilo Snaptube, sin anuncios. Compartes un enlace desde cualquier app y se abre una hoja que lo analiza y ofrece:

- **Vídeo**: «Rápido» (hasta 480p) y «Alta calidad» (la mejor hasta 1080p), con su tamaño.
- **Audio**: MP3 (y M4A en «Más formatos»), con portada y metadatos.
- **Subtítulos**: los del video (y los automáticos en español, inglés o el idioma original), incrustados en el MP4 (los reproductores los muestran como pista).
- **Más formatos**: todas las resoluciones disponibles.
- **Listas**: elegir qué vídeos y la calidad; se bajan de a dos.

## Estados de WhatsApp

Pestaña «Estados»: muestra los estados que ya viste en WhatsApp (o WhatsApp Business) y los guarda en `Descargas/Lince/Estados`. WhatsApp los deja en `Android/media/com.whatsapp/WhatsApp/Media/.Statuses`; desde Android 11 esa carpeta solo se puede leer con el selector del sistema, así que se pide acceso una vez (el selector se abre justo ahí) y el permiso queda guardado. Solo aparecen los estados ya abiertos en WhatsApp: los baja recién cuando los ves.

## Sitios que bloquean a yt-dlp

- **Algunos sitios** revisan la huella TLS y solo dejan pasar a un navegador real (yt-dlp lo resuelve con `curl_cffi`, que no existe en Android). Cuando yt-dlp falla, `Navegador.kt` abre la página en un WebView invisible, saca las URLs directas (`flashvars_*` y `get_media`) y yt-dlp baja el MP4 del CDN, que no está protegido. La app recuerda esos sitios y la próxima vez va directo al navegador (de 11 s a 3 s).
- **YouTube**, desde un teléfono por IPv4 y sin cookies, pide «confirmar que no es un bot». Se usa `player_client=web_embedded,mweb` (hasta 2160p; `mweb` cubre los videos no insertables, hasta 360p). El desafío JS de YouTube no se resuelve con el QuickJS de youtubedl-android (11 s) sino con el V8 del WebView del sistema: el plugin `assets/plugins/lince/…/lince_jcp.py` se carga con `--plugin-dirs` y le pasa el script a `MotorJs.kt`, un servidor en 127.0.0.1 que lo corre en un WebView invisible. Si falla, yt-dlp vuelve solo a QuickJS.
- **Descarga multihilo**: los CDN limitan la velocidad por conexión (medido con 1 y con 8 conexiones: MP4 directo de un CDN 24 → 176 Mbit/s, YouTube 10 → 81 Mbit/s). El HTTP directo lo baja aria2c en 16 trozos (271 MB en 9 s en BlueStacks); con googlevideo aria2c falla, así que YouTube usa `formats=dashy` y el descargador nativo baja 8 fragmentos a la vez. Si aria2c falla, se reintenta con una sola conexión.
- Se prefiere H.264 a igual resolución: YouTube sirve AV1 y no todo lo reproduce.

Todo corre en el teléfono: yt-dlp y ffmpeg van embebidos ([youtubedl-android](https://github.com/JunkFood02/youtubedl-android)), y yt-dlp se actualiza solo una vez al día. Si un sitio falla, se actualiza en el momento y se reintenta. Lo descargado queda en `Descargas/Lince`.

## Compilar

Requiere el SDK de Android (`local.properties` → `sdk.dir`) y un JDK 17 a 22.

```bash
./gradlew assembleRelease
```

Deja un APK por arquitectura en `app/build/outputs/apk/release/`. Casi todos los teléfonos actuales usan `app-arm64-v8a-release.apk`. Se firma con la llave debug: es para uso personal.

## Estructura

| Archivo | Qué hace |
|---|---|
| `ShareActivity.kt` | Recibe el «Compartir» y muestra la hoja sobre la app de origen |
| `MainActivity.kt` | Pegar un enlace y ver la lista de descargas |
| `ui/HojaDescarga.kt` | La hoja: análisis, opciones, subtítulos, listas |
| `Motor.kt` | Arranque, actualización y análisis con yt-dlp (`-J`) |
| `Modelo.kt` | Formatos, opciones rápidas y tamaños estimados |
| `DescargaWorker.kt` | Descarga en segundo plano (WorkManager) con notificación |
| `MotorJs.kt` | Resuelve el desafío JS de YouTube en un WebView (plugin de yt-dlp) |
| `Navegador.kt` | Plan B con WebView para sitios que bloquean a yt-dlp |
| `Estados.kt`, `ui/PantallaEstados.kt` | Estados de WhatsApp: acceso, listado y guardado |
| `Descargas.kt` | Encolar y copiar el resultado a Descargas vía MediaStore |

## Diagnóstico

El build debug trae un receptor que corre el yt-dlp de la app con cualquier argumento (con la app en primer plano: HyperOS corta la red en segundo plano):

```bash
adb shell am broadcast -n com.edgarguitarist.lince/.Prueba --es url URL --es args "'--simulate|-v'"
adb logcat -s LincePrueba:I LinceJs:I
```

`--ez webview false` lo corre sin el plugin (con QuickJS), para comparar. `--ez motor true` mide `Motor.analizar` (el camino de la hoja) y `--ei descargar 720` hace una descarga real por `DescargaWorker` y espera a que termine.
