"""
Deja el video en H.264 + AAC-LC, lo único que reproduce todo el mundo. Instagram y Facebook sirven HEVC (y a veces
audio HE-AAC): en un estado de WhatsApp el iPhone lo muestra sin sonido. Si el archivo ya viene así, no hace nada.
Se activa con `--use-postprocessor LinceCompat:when=after_move`.
"""
import os
import re
import subprocess

from yt_dlp.postprocessor.ffmpeg import FFmpegPostProcessor
from yt_dlp.utils import Popen


def analizar(salida_ffmpeg):
    """(códec de video, lado corto, pix_fmt, códec de audio, perfil de audio) leídos de la salida de `ffmpeg -i`."""
    v = re.search(r'Stream #0:\d+.*?: Video: (\w+)[^,\n]*, (\w+)[^\n]*?, (\d+)x(\d+)', salida_ffmpeg)
    a = re.search(r'Stream #0:\d+.*?: Audio: (\w+)(?: \(([^)]+)\))?', salida_ffmpeg)
    return (v and v.group(1), v and min(int(v.group(3)), int(v.group(4))), v and v.group(2), a and a.group(1), a and a.group(2))


class LinceCompatPP(FFmpegPostProcessor):
    def run(self, info):
        ruta = info.get('filepath')
        if not ruta or not os.path.exists(ruta):
            return [], info
        # `ffmpeg -i` en vez de ffprobe: en el teléfono no siempre hay ffprobe.
        _, err, _ = Popen.run([self.executable, '-hide_banner', '-i', ruta], text=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        vcodec, lado, pix, acodec, perfil = analizar(err or '')
        if not vcodec:
            return [], info
        # shortcut: pasado 1080p (lado corto; los verticales son 1080x1920) no hay H.264 en YouTube y recodificar 4K en el teléfono tardaría minutos; se deja.
        video = vcodec != 'h264' or pix != 'yuv420p'
        video = video and (lado or 0) <= 1080
        audio = acodec is not None and not (acodec == 'aac' and perfil == 'LC')
        if not video and not audio:
            return [], info

        base = os.path.splitext(ruta)[0]
        tmp, salida = base + '.lince.mp4', base + '.mp4'
        self.to_screen(f'Pasando a H.264/AAC para que se vea en todos lados: "{salida}"')
        opts = ['-map', '0:v:0', '-map', '0:a?', '-map', '0:s?', '-map_metadata', '0', '-c:s', 'mov_text']
        opts += (['-c:v', 'libx264', '-preset', 'veryfast', '-crf', '21', '-pix_fmt', 'yuv420p',
                  '-vf', 'scale=trunc(iw/2)*2:trunc(ih/2)*2'] if video else ['-c:v', 'copy'])
        opts += ['-c:a', 'aac', '-b:a', '192k', '-ac', '2'] if audio else ['-c:a', 'copy']
        try:
            self.real_run_ffmpeg([(ruta, [])], [(tmp, opts + ['-movflags', '+faststart'])])
        except Exception as e:  # mejor el original que perder la descarga
            self.report_warning(f'No se pudo convertir, queda el original: {e}')
            if os.path.exists(tmp):
                os.remove(tmp)
            return [], info
        os.remove(ruta)
        os.replace(tmp, salida)
        info['filepath'] = salida
        info['ext'] = 'mp4'
        return [], info
