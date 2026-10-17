"""
Resuelve el desafío JS de YouTube con el V8 del WebView de Lince en vez de QuickJS (11 s → ~1 s en el teléfono).

La app levanta un servidor HTTP en 127.0.0.1 y deja el puerto y un token en `conexion.json`, junto a este
directorio de plugins. Si la app no responde, el proveedor falla y yt-dlp sigue con QuickJS.
"""
from __future__ import annotations

import json
import pathlib
import urllib.request

try:
    from yt_dlp.extractor.youtube.jsc._builtin.ejs import EJSBaseJCP
    from yt_dlp.extractor.youtube.jsc.provider import (
        JsChallengeProviderError,
        register_preference,
        register_provider,
    )
except ImportError:  # yt-dlp movió la interfaz: sin plugin, se usa QuickJS
    EJSBaseJCP = None

_CONEXION = pathlib.Path(__file__).resolve().parents[2] / 'conexion.json'

if EJSBaseJCP is not None:

    @register_provider
    class LinceWebViewJCP(EJSBaseJCP):
        PROVIDER_NAME = 'lince'
        PROVIDER_VERSION = '1.0.0'
        JS_RUNTIME_NAME = 'webview'
        BUG_REPORT_LOCATION = 'Lince'

        def is_available(self, /) -> bool:
            return self._available and _CONEXION.is_file()

        def _construct_stdin(self, player, preprocessed, requests, /) -> str:
            data = {
                'type': 'player',
                'player': player,
                'requests': [{'type': r.type.value, 'challenges': r.input.challenges} for r in requests],
                'output_preprocessed': False,
            }
            # El WebView devuelve el valor de la última expresión; la función evita chocar con declaraciones de una
            # corrida anterior en el mismo contexto.
            return f'''(function () {{
try {{
{self._lib_script.code}
;Object.assign(globalThis, lib);
{self._core_script.code}
;return JSON.stringify(jsc({json.dumps(data)}));
}} catch (e) {{
return JSON.stringify({{type: 'error', error: String(e && e.stack || e)}});
}}
}})()'''

        def _run_js_runtime(self, stdin: str, /) -> str:
            try:
                con = json.loads(_CONEXION.read_text())
                req = urllib.request.Request(
                    f'http://127.0.0.1:{con["port"]}/jsc', data=stdin.encode(),
                    headers={'X-Token': con['token'], 'Content-Type': 'application/javascript'})
                with urllib.request.urlopen(req, timeout=60) as r:
                    return r.read().decode()
            except Exception as e:
                raise JsChallengeProviderError(f'WebView de Lince no disponible: {e}') from e

    @register_preference(LinceWebViewJCP)
    def preference(provider, requests) -> int:
        return 2000


# yt-dlp baja el player de YouTube (3 MB) en cada análisis, aunque cambie cada varios días: se guarda en disco por
# su versión. Lo usan tanto el desafío JS como la marca de tiempo de la firma.
_PLAYERS = _CONEXION.parent / 'players'

try:
    from yt_dlp.extractor.youtube._video import YoutubeIE

    _load_player_original = YoutubeIE._load_player

    def _load_player(self, video_id, player_url, fatal=True):
        key = self._player_js_cache_key(player_url)
        if key in self._code_cache:
            return self._code_cache[key]
        archivo = _PLAYERS / f'{"".join(c for c in key if c.isalnum() or c in "-_")}.js'
        try:
            code = archivo.read_text(encoding='utf-8')
            self.write_debug(f'Player {key} desde la caché de Lince')
            self._code_cache[key] = code
            return code
        except OSError:
            pass
        code = _load_player_original(self, video_id, player_url, fatal=fatal)
        if code:
            try:
                _PLAYERS.mkdir(exist_ok=True)
                for viejo in sorted(_PLAYERS.glob('*.js'), key=lambda p: p.stat().st_mtime)[:-3]:
                    viejo.unlink()
                tmp = archivo.with_suffix('.tmp')
                tmp.write_text(code, encoding='utf-8')
                tmp.replace(archivo)
            except OSError:
                pass
        return code

    YoutubeIE._load_player = _load_player
except Exception:  # yt-dlp cambió por dentro: se queda con su descarga de siempre
    pass
