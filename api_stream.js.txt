// api/stream.js
// Proxy de audio streaming para Google Drive con soporte de Range y CORS

export default async function handler(req, res) {
  // Manejo de preflight CORS
  if (req.method === 'OPTIONS') {
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Methods', 'GET, OPTIONS');
    res.setHeader('Access-Control-Allow-Headers', 'Range, Content-Type');
    return res.status(200).end();
  }

  const { id } = req.query;
  if (!id || typeof id !== 'string') {
    return res.status(400).json({ error: 'Falta el parámetro ?id de Google Drive' });
  }

  // URL de descarga directa de Google Drive
  const driveUrl = `https://drive.usercontent.google.com/download?id=${encodeURIComponent(id)}&export=download&authuser=0&confirm=t`;

  try {
    const fetchHeaders = {};
    if (req.headers.range) {
      fetchHeaders['Range'] = req.headers.range;
    }

    const driveRes = await fetch(driveUrl, {
      headers: fetchHeaders,
      redirect: 'follow',
    });

    if (!driveRes.ok && driveRes.status !== 206) {
      // Reintento con endpoint alternativo uc
      const altUrl = `https://drive.google.com/uc?export=download&id=${encodeURIComponent(id)}&confirm=t`;
      const altRes = await fetch(altUrl, { headers: fetchHeaders, redirect: 'follow' });
      if (!altRes.ok && altRes.status !== 206) {
        return res.status(driveRes.status).json({
          error: 'No se pudo obtener el audio de Google Drive. Verifica que el archivo sea público ("Cualquier persona con el enlace").'
        });
      }
      return pipeStream(altRes, req, res);
    }

    return pipeStream(driveRes, req, res);
  } catch (err) {
    console.error('Error en stream.js:', err);
    return res.status(500).json({ error: err.message });
  }
}

function pipeStream(sourceRes, req, res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Accept-Ranges', 'bytes');
  res.setHeader('Cache-Control', 'public, max-age=86400');

  const contentType = sourceRes.headers.get('content-type') || 'audio/mpeg';
  res.setHeader('Content-Type', contentType);

  const contentLength = sourceRes.headers.get('content-length');
  if (contentLength) res.setHeader('Content-Length', contentLength);

  const contentRange = sourceRes.headers.get('content-range');
  if (contentRange) {
    res.setHeader('Content-Range', contentRange);
    res.status(206);
  } else {
    res.status(sourceRes.status || 200);
  }

  // Streaming del flujo de datos en chunks
  if (sourceRes.body) {
    const reader = sourceRes.body.getReader();
    const pump = async () => {
      try {
        while (true) {
          const { done, value } = await reader.read();
          if (done) {
            res.end();
            break;
          }
          if (!res.write(value)) {
            await new Promise(r => res.once('drain', r));
          }
        }
      } catch (err) {
        console.error('Error streaming chunk:', err);
        res.end();
      }
    };
    pump();
  } else {
    res.end();
  }
}
