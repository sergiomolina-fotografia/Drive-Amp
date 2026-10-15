<!DOCTYPE html>

<html lang="en">
<head>
<meta charset="utf-8"/>
<meta content="width=device-width,initial-scale=1.0" name="viewport"/>
<script src="https://cdn.tailwindcss.com?plugins=forms,container-queries"></script>
<style>
    * { margin: 0; padding: 0; box-sizing: border-box; }
    html, body {
      width: 100%;
      height: 100%;
      overflow: hidden;
      background: #000;
    }
  </style>
</head>
<body>
<!-- STITCH_THREEJS_START:ANIMATION_34 class="fixed inset-0 w-full h-full bg-transparent" -->
<div class="fixed inset-0 w-full h-full bg-transparent" style="display:block;">
<script src="https://ajax.googleapis.com/ajax/libs/threejs/r125/three.min.js"></script>
<div id="threejs-container-ANIMATION_34" style="width:100%;height:100%"></div>
<script>
(function() {
  const container = document.getElementById('threejs-container-ANIMATION_34');
  const devicePixelRatio = window.devicePixelRatio || 1;
  // api/resolve.js
// Parser universal para Google Drive en Vercel Serverless
// Devuelve SIEMPRE JSON estructurado, evitando errores de "!<DOCTYPE html>"

module.exports = async function handler(req, res) {
  // 1. Cabeceras CORS universales y Content-Type JSON estricto
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Range');
  res.setHeader('Content-Type', 'application/json; charset=utf-8');

  if (req.method === 'OPTIONS') {
    return res.status(200).end();
  }

  // 2. Extraer parámetros tanto de GET (?url= o ?id=) como de POST body
  let url = req.query.url || req.query.id;
  if (!url && req.body) {
    let body = req.body;
    if (typeof body === 'string') {
      try { body = JSON.parse(body); } catch (e) {}
    }
    if (body) {
      url = body.url || body.id;
      if (body.links && Array.isArray(body.links) && body.links.length > 0) url = body.links[0];
      if (body.json && body.json.links && Array.isArray(body.json.links) && body.json.links.length > 0) {
        url = body.json.links[0];
      }
    }
  }

  if (!url) {
    return res.status(400).json({ error: 'Falta el parámetro de enlace o ID de Google Drive (?url= o ?id=).' });
  }

  // Soporte para ping
  if (url === 'ping') {
    return res.status(200).json({ ok: true, tracks: [] });
  }

  // 3. Extraer fileId o folderId
  let fileId = null;
  let folderId = null;

  const fileMatch = url.match(/\/file\/d\/([a-zA-Z0-9_-]+)/) || url.match(/[?&]id=([a-zA-Z0-9_-]+)/);
  const folderMatch = url.match(/\/folders\/([a-zA-Z0-9_-]+)/);

  if (folderMatch) {
    folderId = folderMatch[1];
  } else if (fileMatch && !url.includes('/folders/')) {
    fileId = fileMatch[1];
  } else {
    const genericMatch = url.match(/([a-zA-Z0-9_-]{25,})/);
    if (genericMatch) folderId = genericMatch[1];
  }

  // ==========================================
  // CASO 1: Archivo individual de Google Drive
  // ==========================================
  if (fileId) {
    const name = `Audio Drive (${fileId.substring(0, 6)}...)`;
    return res.status(200).json({
      type: 'file',
      tracks: [{
        id: fileId,
        title: name,
        name: name,
        streamUrl: `/api/stream?id=${fileId}`
      }]
    });
  }

  if (!folderId) {
    return res.status(400).json({ error: 'No se reconoció un ID o URL válida de Google Drive.' });
  }

  // ==========================================
  // CASO 2: Carpeta de Google Drive (Scraping de vista embebida)
  // ==========================================
  try {
    const embedUrl = `https://drive.google.com/embeddedfolderview?id=${encodeURIComponent(folderId)}#list`;
    const response = await fetch(embedUrl, {
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
        'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
        'Accept-Language': 'es-ES,es;q=0.9,en;q=0.8'
      }
    });

    if (!response.ok) {
      return res.status(404).json({
        error: 'La carpeta es privada o no existe. Verifica que esté en modo "Cualquier persona con el enlace".'
      });
    }

    const html = await response.text();
    const tracks = [];
    const seenIds = new Set();
    const AUDIO_EXT = /\.(mp3|wav|flac|ogg|m4a|aac|opus|weba|wma)$/i;

    // Regex 1: Entradas HTML clásicas de embeddedfolderview
    const entryRegex = /id="entry-([a-zA-Z0-9_-]+)"[\s\S]*?<div class="folder-entry-name[^"]*">([^<]+)<\/div>/gi;
    let match;
    while ((match = entryRegex.exec(html)) !== null) {
      const fid = match[1];
      const fname = match[2].trim().replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>');
      if (!seenIds.has(fid) && AUDIO_EXT.test(fname)) {
        seenIds.add(fid);
        tracks.push({
          id: fid,
          title: fname,
          name: fname,
          streamUrl: `/api/stream?id=${fid}`
        });
      }
    }

    // Regex 2: Payloads JSON embebidos de Google Drive (window._DRIVE_IVD_)
    const jsonMatches = html.matchAll(/\["([a-zA-Z0-9_-]{25,})","([^"]+\.(mp3|wav|flac|ogg|m4a|aac|opus|weba|wma))"/gi);
    for (const m of jsonMatches) {
      const fid = m[1];
      const fname = m[2];
      if (!seenIds.has(fid)) {
        seenIds.add(fid);
        tracks.push({
          id: fid,
          title: fname,
          name: fname,
          streamUrl: `/api/stream?id=${fid}`
        });
      }
    }

    // Regex 3: Enlaces directos /file/d/
    const linkMatches = html.matchAll(/\/file\/d\/([a-zA-Z0-9_-]+)[^>]*>([^<]+)<\/a>/gi);
    for (const m of linkMatches) {
      const fid = m[1];
      const fname = m[2].trim();
      if (!seenIds.has(fid) && AUDIO_EXT.test(fname)) {
        seenIds.add(fid);
        tracks.push({
          id: fid,
          title: fname,
          name: fname,
          streamUrl: `/api/stream?id=${fid}`
        });
      }
    }

    if (tracks.length > 0) {
      return res.status(200).json({
        type: 'folder',
        count: tracks.length,
        tracks: tracks
      });
    }

    return res.status(404).json({
      error: 'No se encontraron pistas de audio en la raíz de la carpeta. Verifica que tengan extensión (.mp3, etc.) y estén compartidas públicamente.'
    });

  } catch (err) {
    return res.status(500).json({ error: 'Error al consultar Google Drive: ' + err.message });
  }
};
})();
</script>
</div>
<!-- STITCH_THREEJS_END:ANIMATION_34 -->
</body>
</html>
