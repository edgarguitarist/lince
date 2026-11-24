// Corre dentro del WebView de Navegador.kt y entrega con Lince.listo(json) lo que la página tiene para bajar:
// `formats` (videos) o, si no hay, `images` (las fotos de la publicación). Con window.LINCE_SOLO_IMG solo busca fotos.
(async () => {
  if (window.__lince) return;
  window.__lince = true;
  const out = { formats: [], images: [] };
  const meta = s => (document.querySelector(s) || {}).content;
  const espera = ms => new Promise(r => setTimeout(r, ms));
  let debiles = false; // videos que solo salieron de buscar ".mp4" en el HTML: pueden ser de otra cosa

  if (!window.LINCE_SOLO_IMG) {
    const vistos = new Set();
    const add = (u, h) => {
      if (!u || typeof u !== 'string' || u.startsWith('blob:')) return;
      u = u.replace(/\\\//g, '/');
      if (vistos.has(u)) return;
      vistos.add(u);
      out.formats.push({ url: u, h: parseInt(h) || 0 });
    };
    // El reproductor a veces define flashvars después del onload: se espera hasta 8 s, pero solo si la página lo trae.
    let fv = null;
    const hayFv = /flashvars_\d+/.test(document.documentElement.innerHTML);
    for (let i = 0; i < 16 && !fv && hayFv; i++) {
      const k = Object.keys(window).find(k => /^flashvars_\d+$/.test(k));
      if (k) fv = window[k]; else await espera(500);
    }
    if (fv) {
      out.thumb = fv.image_url;
      out.dur = +fv.video_duration;
      out.title = fv.video_title;
      for (const d of (fv.mediaDefinitions || [])) {
        if (!d || !d.videoUrl) continue;
        if (d.videoUrl.includes('/video/get_media')) {
          try {
            const r = await fetch(d.videoUrl, { credentials: 'include' });
            for (const m of await r.json()) if (m && m.videoUrl) add(m.videoUrl, m.quality);
          } catch (e) {}
        } else add(d.videoUrl, Array.isArray(d.quality) ? 0 : d.quality);
      }
    }
    // Reddit: su API pide sesión y el <video> vive en un shadow DOM. El reproductor del post trae MP4 ya armados con
    // audio (packaged-media-json) y el HLS en `src`; los otros reproductores de la página son GIF de los comentarios.
    const rp = !out.formats.length && document.querySelector('shreddit-post shreddit-player, shreddit-post shreddit-player-2');
    if (rp) {
      try {
        const pm = JSON.parse(rp.getAttribute('packaged-media-json')).playbackMp4s;
        for (const x of pm.permutations) add(x.source.url, x.source.dimensions.height);
        out.dur = pm.duration;
      } catch (e) {}
      if (!out.formats.length) add(rp.getAttribute('src'));
      out.thumb = rp.getAttribute('poster');
    }
    if (!out.formats.length) {
      document.querySelectorAll('video, video source').forEach(v => add(v.currentSrc || v.src, v.videoHeight));
      add(meta('meta[property="og:video:secure_url"]'));
      add(meta('meta[property="og:video"]'));
      add(meta('meta[property="og:video:url"]'));
      if (!out.formats.length) {
        (document.documentElement.innerHTML.match(/https?:(?:\\?\/){2}[^"'\s<>]+?\.(?:mp4|m3u8)(?:\?[^"'\s<>]*)?/g) || []).forEach(u => add(u));
        debiles = out.formats.length > 0 && !document.querySelector('video');
      }
    }
  }

  if (!out.formats.length || debiles) {
    const esImg = u => /\.(jpe?g|png|webp|gif|avif)(\?|$)/i.test(u);
    const mayor = im => {
      let u = im.currentSrc || im.src, n = 0;
      for (const p of (im.srcset || '').split(',')) {
        const m = p.trim().match(/^(\S+)\s+([\d.]+)[wx]$/);
        if (m && +m[2] > n) { n = +m[2]; u = new URL(m[1], location.href).href; }
      }
      const a = im.closest('a[href]');
      return a && esImg(a.href) ? a.href : u;
    };
    // De 300 px para arriba: deja fuera íconos y avatares.
    let min = 300;
    const grandes = raiz => [...raiz.querySelectorAll('img')].filter(im => im.naturalWidth >= min && im.naturalHeight >= min);
    // Las que están dentro de un enlace a otra página son miniaturas de otras publicaciones.
    const aqui = location.href.split(/[?#]/)[0];
    const propia = im => { const a = im.closest('a[href]'); return !a || esImg(a.href) || a.href.split(/[?#]/)[0] === aqui; };
    const vistas = new Map();
    const juntar = () => {
      const post = [...document.querySelectorAll('shreddit-post, article')].find(e => grandes(e).length);
      let l = grandes(post || document);
      const p = l.filter(propia);
      if (p.length || debiles) l = p; // con un video dudoso, las miniaturas ajenas no cuentan como fotos
      for (const im of l) {
        const u = mayor(im);
        if (!/^https?:/.test(u)) continue;
        // El mismo archivo puede venir de dos servidores del CDN: se compara por la ruta.
        const ruta = new URL(u).pathname;
        const k = esImg(ruta) ? ruta : u;
        if (!vistas.has(k)) vistas.set(k, { url: u, w: im.naturalWidth, h: im.naturalHeight });
      }
      return vistas.size;
    };
    // Las páginas armadas con JS pintan las fotos después del onload: se espera a que dejen de aparecer (máx. 8 s).
    let antes = -1, iguales = 0;
    for (let i = 0; i < 16 && iguales < 3; i++) {
      await espera(500);
      const n = juntar();
      iguales = n && n === antes ? iguales + 1 : 0;
      antes = n;
    }
    if (!vistas.size) { min = 150; juntar(); } // páginas que solo traen miniaturas
    // Carruseles: las fotos siguientes solo se cargan al pasar de una a otra.
    for (let i = 0; i < 20; i++) {
      const sig = document.querySelector('button[aria-label="Next"], button[aria-label="Siguiente"]');
      if (!sig) break;
      sig.click();
      await espera(400);
      juntar();
    }
    out.images = [...vistas.values()];
    const og = meta('meta[property="og:image"]');
    if (!out.images.length && og && !out.formats.length) out.images = [{ url: new URL(og, location.href).href, w: 0, h: 0 }];
    if (out.images.length) out.formats = [];
  }

  out.title = meta('meta[name="twitter:title"]') || meta('meta[property="og:title"]') || out.title || document.title;
  out.thumb = out.thumb || meta('meta[property="og:image"]');
  Lince.listo(JSON.stringify(out));
})();
