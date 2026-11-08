"""
Arma un MP4 con las fotos de una publicación de TikTok (`_lince_fotos`, del extractor `lince_tiktok`) y su audio:
cada foto dura lo mismo y entre todas cubren la canción. Se activa con `--use-postprocessor LinceFotos:when=after_move`;
si el video no trae fotos, no hace nada.
"""
import os
import shutil

from yt_dlp.networking import Request
from yt_dlp.postprocessor.ffmpeg import FFmpegPostProcessor


class LinceFotosPP(FFmpegPostProcessor):
    def run(self, info):
        fotos = info.get('_lince_fotos')
        audio = info.get('filepath')
        if not fotos or not audio:
            return [], info

        base = os.path.splitext(audio)[0]
        rutas = []
        for i, f in enumerate(fotos):
            ruta = f'{base}.foto{i}.jpg'
            self.to_screen(f'Bajando la foto {i + 1} de {len(fotos)}')
            with self._downloader.urlopen(Request(f['url'], headers=info.get('http_headers') or {})) as r, \
                    open(ruta, 'wb') as out:
                shutil.copyfileobj(r, out)
            rutas.append(ruta)

        # El lienzo es el de la primera foto, con el alto tope en 1920 para no eternizar el encode en el teléfono.
        ancho, alto = fotos[0].get('ancho') or 1080, fotos[0].get('alto') or 1920
        if alto > 1920:
            ancho, alto = ancho * 1920 // alto, 1920
        ancho, alto = ancho // 2 * 2, alto // 2 * 2

        total = self._get_real_video_duration(audio, fatal=False) or info.get('duration') or 3 * len(rutas)
        cada = total / len(rutas)
        filtros = ''.join(
            f'[{i}:v]scale={ancho}:{alto}:force_original_aspect_ratio=decrease,'
            f'pad={ancho}:{alto}:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=15,format=yuv420p[v{i}];'
            for i in range(len(rutas)))
        filtros += ''.join(f'[v{i}]' for i in range(len(rutas))) + f'concat=n={len(rutas)}:v=1:a=0[v]'

        salida = base + '.mp4'
        self.to_screen(f'Armando el video con {len(rutas)} foto(s): "{salida}"')
        self.real_run_ffmpeg(
            [(r, ['-loop', '1', '-t', f'{cada:.3f}']) for r in rutas] + [(audio, [])],
            [(salida, [
                '-filter_complex', filtros, '-map', '[v]', '-map', f'{len(rutas)}:a',
                '-map_metadata', str(len(rutas)),
                '-c:v', 'libx264', '-preset', 'veryfast', '-tune', 'stillimage',
                '-c:a', 'aac', '-b:a', '192k', '-shortest', '-movflags', '+faststart'])])

        info['filepath'] = salida
        info['ext'] = 'mp4'
        return [audio, *rutas], info
