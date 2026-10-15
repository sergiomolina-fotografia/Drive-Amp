// api/resolve.js
// Parser serverless de carpetas públicas de Google Drive sin necesidad de API Key

export default async function handler(req, res) {
  // Manejo de preflight CORS
  if (req.method === 'OPTIONS') {
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
    return res.status(200).end();
  }

  res.setHeader('Access-Control-Allow-Origin', '*');

  let folderId = req.query.id || (req.body && req.body.id);
  const url = req.query.url || (req.body && req.body.url);

  // Si pasan una URL completa, extraer el ID
  if (!folderId && url) {
    const m = url.match(/folders\/([a-zA-Z0-9_-]+)/) || url.match(/[?&]id=([a-zA-Z0-9_-]+)/);
    if (m) folderId = m[1];
  }

  // Si pasan un enlace directo de archivo suelto
  const fileMatch = url && (url.match(/\/file\/d\/([a-zA-Z0-9_-]+)/) || url.match(/[?&]id=([a-zA-Z0-9_-]+)/));
  if (fileMatch && (!url.includes('/folders/'))) {
    return res.status(200).json({
      type: 'file',
      tracks: [{
        id: fileMatch[1],
        name: 'Pista de Google Drive (' + fileMatch[1].substring(0, 6) + '...)',
        streamUrl: `/api/stream?id=${fileMatch[1]}`
      }]
    });
  }

  if (!folderId) {
    return res.status(400).json({ error: 'No se pudo identificar el ID de carpeta o archivo.' });
  }

  try {
    // Lectura de la carpeta pública de Google Drive sin API Key
    const embedUrl = `https://drive.google.com/embeddedfolderview?id=${encodeURIComponent(folderId)}#list`;
    const response = await fetch(embedUrl, {
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
      }
    });

    if (!response.ok) {
      return res.status(404).json({ error: 'Carpeta no accesible o privada. Asegúrate de compartirla como "Cualquier persona con el enlace".' });
    }

    const html = await response.text();
    const tracks = [];
    const seenIds = new Set();

    // Regex 1: Extracción por atributos data-id y títulos en el DOM embebido
    const entryRegex = /id="entry-([a-zA-Z0-9_-]+)"[\s\S]*?<div class="folder-entry-name[^"]*">([^<]+)<\/div>/g;
    let match;
    while ((match = entryRegex.exec(html)) !== null) {
      const id = match[1];
      const name = match[2].trim();
      if (!seenIds.has(id) && isAudio(name)) {
        seenIds.add(id);
        tracks.push({ id, name, streamUrl: `/api/stream?id=${id}` });
      }
    }

    // Regex 2 (Fallback JSON): Google Drive a menudo inyecta un array JSON con los datos de los archivos
    if (tracks.length === 0) {
      const jsonItems = html.matchAll(/\["([a-zA-Z0-9_-]{25,})","([^"]+\.(mp3|wav|flac|ogg|m4a|aac))"/gi);
      for (const m of jsonItems) {
        const id = m[1];
        const name = m[2];
        if (!seenIds.has(id)) {
          seenIds.add(id);
          tracks.push({ id, name, streamUrl: `/api/stream?id=${id}` });
        }
      }
    }

    // Regex 3 (Nombres de archivo estándar)
    if (tracks.length === 0) {
      const fallbackMatches = html.matchAll(/\/file\/d\/([a-zA-Z0-9_-]+)[^>]*>([^<]+)<\/a>/g);
      for (const m of fallbackMatches) {
        const id = m[1];
        const name = m[2].trim();
        if (!seenIds.has(id)) {
          seenIds.add(id);
          tracks.push({ id, name, streamUrl: `/api/stream?id=${id}` });
        }
      }
    }

    if (tracks.length === 0) {
      return res.status(404).json({
        error: 'No se encontraron pistas de audio en la carpeta. Verifica que la carpeta contenga archivos MP3/WAV/FLAC públicos.'
      });
    }

    return res.status(200).json({
      type: 'folder',
      folderId,
      count: tracks.length,
      tracks
    });

  } catch (err) {
    console.error('Error en resolve.js:', err);
    return res.status(500).json({ error: 'Error del servidor al leer Google Drive: ' + err.message });
  }
}

function isAudio(filename) {
  return /\.(mp3|wav|flac|ogg|m4a|aac|opus|webm|wma)$/i.test(filename);
}
