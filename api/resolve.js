// api/resolve.js
// Vercel Serverless Function con ES Modules (100% compatible con api/stream.js)

export default async function handler(req, res) {
  // Cabeceras CORS universales y Content-Type JSON estricto
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Range');
  res.setHeader('Content-Type', 'application/json; charset=utf-8');

  if (req.method === 'OPTIONS') return res.status(200).end();

  // 1. Extraer URL o ID desde Query string (?url= o ?id=) o Body (POST)
  let url = req.query.url || req.query.id;
  if (!url && req.body) {
    let b = req.body;
    if (typeof b === 'string') {
      try { b = JSON.parse(b); } catch (e) {}
    }
    if (b) {
      url = b.url || b.id || (b.links && b.links[0]) || (b.json && b.json.links && b.json.links[0]);
    }
  }

  // Ping de prueba de salud
  if (!url || url === 'ping') {
    return res.status(200).json({ ok: true, tracks: [] });
  }

  // 2. Extraer ID de archivo o carpeta
  let fileId = null;
  let folderId = null;

  const fMatch = url.match(/\/file\/d\/([a-zA-Z0-9_-]+)/) || url.match(/[?&]id=([a-zA-Z0-9_-]+)/);
  const dMatch = url.match(/\/folders\/([a-zA-Z0-9_-]+)/);

  if (dMatch) {
    folderId = dMatch[1];
  } else if (fMatch && !url.includes('/folders/')) {
    fileId = fMatch[1];
  } else {
    const gen = url.match(/([a-zA-Z0-9_-]{25,})/);
    if (gen) folderId = gen[1];
  }

  // Si es un archivo individual de Google Drive
  if (fileId) {
    const name = `Audio Drive (${fileId.substring(0, 6)}...)`;
    return res.status(200).json({
      type: 'file',
      tracks: [{ id: fileId, title: name, name: name, streamUrl: `/api/stream?id=${fileId}` }]
    });
  }

  if (!folderId) {
    return res.status(400).json({ error: 'No se reconoció un enlace válido de Drive.' });
  }

  // Si es una carpeta de Google Drive: Scraping de vista embebida
  try {
    const embedUrl = `https://drive.google.com/embeddedfolderview?id=${encodeURIComponent(folderId)}#list`;
    const response = await fetch(embedUrl, {
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
        'Accept': 'text/html'
      }
    });

    if (!response.ok) {
      return res.status(404).json({ error: 'La carpeta es privada o no existe en Google Drive.' });
    }

    const html = await response.text();
    const tracks = [];
    const seen = new Set();
    const AUDIO_EXT = /\.(mp3|wav|flac|ogg|m4a|aac|opus|weba|wma)$/i;

    // Extracción 1: Elementos clásicos de entrada HTML
    const entryRegex = /id="entry-([a-zA-Z0-9_-]+)"[\s\S]*?<div class="folder-entry-name[^"]*">([^<]+)<\/div>/gi;
    let match;
    while ((match = entryRegex.exec(html)) !== null) {
      const fid = match[1];
      const fname = match[2].trim().replace(/&amp;/g, '&');
      if (!seen.has(fid) && AUDIO_EXT.test(fname)) {
        seen.add(fid);
        tracks.push({ id: fid, title: fname, name: fname, streamUrl: `/api/stream?id=${fid}` });
      }
    }

    // Extracción 2: Payloads JSON embebidos de Google Drive (_DRIVE_IVD_)
    const jsonMatches = html.matchAll(/\["([a-zA-Z0-9_-]{25,})","([^"]+\.(mp3|wav|flac|ogg|m4a|aac|opus|weba|wma))"/gi);
    for (const m of jsonMatches) {
      if (!seen.has(m[1])) {
        seen.add(m[1]);
        tracks.push({ id: m[1], title: m[2], name: m[2], streamUrl: `/api/stream?id=${m[1]}` });
      }
    }

    if (tracks.length > 0) {
      return res.status(200).json({ type: 'folder', count: tracks.length, tracks });
    }

    return res.status(404).json({ error: 'No se encontraron audios visibles (.mp3, etc.) en la raíz de la carpeta.' });
  } catch (err) {
    return res.status(500).json({ error: 'Error al consultar Google Drive: ' + err.message });
  }
}
