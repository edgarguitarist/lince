"""
Publicaciones de fotos de TikTok (`tiktok.com/@usuario/photo/<id>`), que yt-dlp no reconoce («Unsupported URL»).

Son la misma publicación que `/video/<id>`, pero ahí TikTok solo entrega la música. Este extractor deja además las
fotos en `_lince_fotos`, y el postprocesador `LinceFotos` arma con ellas y el audio un MP4.
"""
from yt_dlp.extractor.tiktok import TikTokIE
from yt_dlp.utils import traverse_obj, url_or_none


class LinceTikTokFotoIE(TikTokIE):
    IE_NAME = 'lince:tiktok:foto'
    _VALID_URL = r'https?://(?:www\.)?tiktok\.com/@(?P<user_id>[\w.-]+)?/photo/(?P<id>\d+)'
    _EMBED_REGEX = []
    _TESTS = []

    def _real_extract(self, url):
        video_id, user_id = self._match_valid_url(url).group('id', 'user_id')
        url = self._create_url(user_id, video_id)
        data, status = self._extract_web_data_and_status(url, video_id)
        if not data or status != 0:
            return self.url_result(url, TikTokIE)  # que el extractor de TikTok dé su propio error
        info = self._parse_aweme_video_web(data, url, video_id)
        info['title'] = info.get('title') or f'TikTok {video_id}'
        info['_lince_fotos'] = [
            {'url': u, 'ancho': img.get('imageWidth'), 'alto': img.get('imageHeight')}
            for img in traverse_obj(data, ('imagePost', 'images', ..., {dict}))
            if (u := traverse_obj(img, ('imageURL', 'urlList', 0, {url_or_none})))]
        return info
