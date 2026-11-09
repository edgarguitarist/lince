# Mejoras pendientes

Análisis del 2026-11-09 (versión 0.6.4). Nada de esto está implementado todavía: es la lista para trabajar después.

Antes de optimizar, **medir**: correr un análisis con `-v` en el teléfono y anotar cuánto se va en arrancar Python,
en la red y en el desafío JS. Sin eso es fácil optimizar lo que no pesa.

---

## 1. «Analizando enlace…» más rápido

Ya está bien optimizado: el desafío JS va por V8 (`MotorJs.kt` + `lince_jcp.py`), el player de YouTube queda en caché
y para analizar se usa un solo cliente (`YT_RAPIDO`). Queda esto:

| # | Qué | Dónde | Ganancia estimada | Riesgo |
|---|-----|-------|-------------------|--------|
| 1.1 | Sacar la actualización diaria de yt-dlp del arranque: hoy compite por red con el primer análisis cuando la app se abre desde «Compartir». Pasarla a WorkManager (`requiresDeviceIdle`/`UNMETERED`) o lanzarla después del primer análisis. | `Lince.kt:342`, `Motor.actualizar` | Alta (solo el día que toca) | Bajo |
| 1.2 | Pasar `--cache-dir` a una carpeta de `filesDir`. Hoy no se pasa y no está claro que `~/.cache` exista en el proceso de la app; si no persiste, yt-dlp no reusa lo que guarda entre corridas (resultados del desafío, datos del player). Verificar con `-v` si aparece «Writing cache» o algún error. | `Motor.analizarCon`, `DescargaWorker` | Media | Bajo |
| 1.3 | Agregar `skip=hls,translated_subs` a `YT_RAPIDO`: el manifiesto m3u8 no se usa (la descarga va con `formats=dashy`). Probar aparte `player_skip=webpage` (un pedido menos, pero puede perder metadatos). | `Motor.kt:25` | Baja-media (un pedido ≈ 0,3–0,8 s) | Bajo / medio |
| 1.4 | Mostrar algo mientras se analiza: para YouTube, el oEmbed (`https://www.youtube.com/oembed?url=…&format=json`) da título y miniatura en ~200 ms. La hoja puede mostrarlos con el spinner solo en las opciones. | `HojaDescarga.kt` (`VistaCargando`) | Percibida alta | Bajo |
| 1.5 | La descarga vuelve a extraer todo desde cero. Guardar el JSON del análisis en un archivo y pasarlo con `--load-info-json` (las URLs de YouTube duran ~6 h, así que solo si la descarga empieza enseguida; si falla, caer a la URL normal). | `DescargaWorker.kt` | Alta en el arranque de la descarga | Medio |

Piso que no se puede bajar fácil: cada análisis levanta un proceso de Python e importa yt-dlp (~1–2 s en el teléfono).
youtubedl-android no ofrece un proceso persistente.

---

## 2. Sitios que no pasan por yt-dlp (o no deberían)

Hoy hay dos caminos: yt-dlp y el `Navegador` (WebView) para los sitios que bloquean a yt-dlp por su huella TLS.
El camino del navegador es el más lento de toda la app (hasta 30 s + 8 s esperando `flashvars`).

| # | Qué | Dónde |
|---|-----|-------|
| 2.1 | **No arrancar Python si no hace falta.** `Motor.analizar` llama a `iniciar()` (Python + ffmpeg + aria2c) antes de mirar si el host va directo al navegador. Mover el chequeo de `hosts_navegador` antes de `iniciar()`. | `Motor.kt:59-67` |
| 2.2 | **Enlaces directos a archivos** (`.mp4`, `.webm`, `.mp3`, `.m3u8`…): hoy los analiza el extractor genérico de yt-dlp. Un `HEAD` desde Kotlin da tipo y tamaño en ~200 ms; se arma un `Video(directo = true)` sin Python. | `Motor.analizar` (nuevo atajo) |
| 2.3 | **Capturar el video sin esperar `onPageFinished`**: en `shouldInterceptRequest` se ven pasar las peticiones a `.mp4`/`.m3u8`/`get_media`; en cuanto aparecen se puede responder. `onPageFinished` espera anuncios, imágenes de terceros, etc. | `Navegador.cargar` |
| 2.4 | **Bloquear anuncios y rastreadores** en ese mismo `shouldInterceptRequest` (lista corta de dominios: doubleclick, googlesyndication, trafficjunky, exoclick…) y no pedir CSS/fuentes. La página carga mucho más rápido. | `Navegador.cargar` |
| 2.5 | **Los `HEAD` de tamaño no deben frenar la hoja**: hoy se espera a todos (timeout 8 s c/u, en paralelo). Mostrar la hoja con las calidades y completar los tamaños cuando lleguen (estado en el ViewModel). | `Navegador.analizar`, `AnalisisVM` |
| 2.6 | **Reusar un WebView** en vez de crear uno por análisis (crear el primero cuesta ~300–500 ms). Ojo con limpiar estado entre sitios. | `Navegador.cargar` |
| 2.7 | `hosts_navegador` no caduca nunca: si yt-dlp arregla el sitio, se sigue usando el camino lento. Guardar fecha y reintentar con yt-dlp cada X días (o tras actualizar yt-dlp). | `Motor.kt:64-76` |
| 2.8 | A futuro, para los sitios más usados (Instagram, TikTok, Facebook, X), evaluar extractores en Kotlin que lean el JSON embebido en la página o la API pública, sin Python. Solo si las métricas muestran que son la mayoría de los enlaces: mantenerlos cuesta. | nuevo |

---

## 3. Estados: verlos dentro de la app

Hoy, al tocar un estado se abre otra app (`Intent.ACTION_VIEW`, `PantallaEstados.kt:152`): depende de qué galería
tenga el teléfono y saca al usuario de Lince.

Propuesta: un visor propio a pantalla completa.

- `Dialog(properties = DialogProperties(usePlatformDefaultWidth = false))` o una ruta nueva, fondo negro.
- `HorizontalPager` con todos los estados del filtro actual, empezando en el tocado (deslizar entre estados, como en WhatsApp).
- Imágenes: Coil `AsyncImage` con zoom (pellizcar + doble toque) con `Modifier.transformable` o la librería `telephoto` (`me.saket.telephoto:zoomable-image-coil`).
- Videos: Media3 ExoPlayer (`androidx.media3:media3-exoplayer` + `media3-ui`) con `PlayerView` en un `AndroidView`; pausar al cambiar de página y liberar al cerrar. Lee el `content://` del árbol de documentos sin copiarlo.
- Barra inferior: **Guardar** (reusar `Estados.guardar`, con el check si ya está), **Compartir** (`ACTION_SEND` con la URI) y **Abrir con…** (lo de hoy, como opción secundaria).
- Atrás / deslizar hacia abajo cierra el visor.

---

## 4. Aviso de versión nueva: «manda a una página de error»

Revisado desde aquí: el repo es público, `api.github.com/repos/edgarguitarist/lince/releases/latest` responde 200,
el release `v0.6.4` trae los tres APK (`arm64-v8a`, `armeabi-v7a`, `x86_64`) y `github.com/…/releases/latest`
redirige bien a `/releases/tag/v0.6.4`. O sea, desde fuera los enlaces funcionan: el fallo está en el teléfono.
Hay que reproducirlo y confirmar cuál de estas es:

1. **Firma distinta.** Desde `ce629a4` el release se firma con un keystore propio. Si en el teléfono está instalada
   una build firmada con otra llave (debug, o una release anterior a ese cambio), el instalador de Android muestra
   «La app no se instaló» / «paquete en conflicto». No hay forma de actualizar encima: hay que desinstalar una vez.
   *Arreglo*: detectar la firma instalada vs. la del APK bajado (`PackageManager.getPackageArchiveInfo` con
   `GET_SIGNING_CERTIFICATES`) y, si no coinciden, explicar que hay que desinstalar en vez de abrir el instalador.
2. **«Buscar nueva versión» en Acerca** abre `releases/latest` en el navegador (`PantallaAcerca.kt:75`). Si el
   navegador del teléfono o la app de GitHub intercepta el enlace, puede mostrar error. *Arreglo*: que ese botón use
   `Actualizador.buscar` y el mismo diálogo de la app (con un mensaje «Ya tienes la última versión»), y el
   navegador solo como respaldo.
3. **El diálogo no aparece en builds debug** (`Actualizador.buscar` sale si es `FLAG_DEBUGGABLE`). Si la prueba fue
   con una build debug, el único camino era el navegador (punto 2).
4. **Arquitectura mal detectada** (`Actualizador.abi`): si `nativeLibraryDir` no termina en `arm64`/`arm`/`x86_64`,
   cae a `SUPPORTED_ABIS[0]`. En BlueStacks puede reportar `arm64-v8a` (traducción ARM) aunque la app instalada
   sea `x86_64`, o al revés, y el APK bajado no instala («error al analizar el paquete»). *Arreglo*: guardar la
   ABI del APK en `BuildConfig` al compilar (cada split ya sabe la suya) en vez de deducirla.
5. **Permiso de instalar apps desconocidas**: la primera vez Android manda a Ajustes; si el usuario vuelve con
   «Atrás» sin activarlo, no pasa nada y no se informa. *Arreglo*: chequear
   `packageManager.canRequestPackageInstalls()` antes y, si falta, abrir
   `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` y reintentar al volver.

Además, en `Actualizador.abrir` el `responseCode` se lee dentro de `apply`, antes de devolver la conexión: funciona,
pero si GitHub responde 403 por límite de la API (60 pedidos/h por IP sin token) el aviso desaparece sin decir nada.
Conviene registrar el error (Log) para poder diagnosticarlo.

---

## 5. Plan de prueba (BlueStacks y teléfono)

- [ ] Análisis con `-v`: tiempos de YouTube, TikTok, Instagram y un sitio que vaya por el navegador.
- [ ] Abrir la app desde «Compartir» justo después de borrar `ultima_actualizacion` (simula el día que toca actualizar) y medir.
- [ ] Enlace directo a un `.mp4`.
- [ ] Estados: visor con imágenes y videos, deslizar, guardar, compartir.
- [ ] Actualización: instalar la 0.6.3 **release** (misma firma), abrir, aceptar el aviso y ver que instale la 0.6.4. Repetir en BlueStacks (x86_64) y en el teléfono (arm64).
- [ ] Actualización con una build de firma distinta instalada: debe explicar que hay que desinstalar.
- [ ] «Buscar nueva versión» desde Acerca.
