import superjson from "superjson";
import { schema, OutputType, DriveTrack } from "./resolve_POST.schema";

const AUDIO_EXT = /\.(mp3|m4a|wav|flac|ogg|oga|opus|aac|wma|weba|mp4a)$/i;
const UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

function decodeEntities(s: string): string {
  return s
    .replace(/&#(\d+);/g, (_, n) => String.fromCodePoint(Number(n)))
    .replace(/&#x([0-9a-f]+);/gi, (_, n) => String.fromCodePoint(parseInt(n, 16)))
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&amp;/g, "&");
}

type Parsed = { kind: "folder" | "file"; id: string } | null;

function parseLink(raw: string): Parsed {
  const link = raw.trim();
  const folder = link.match(/\/folders\/([a-zA-Z0-9_-]{10,})/);
  if (folder) return { kind: "folder", id: folder[1] };
  const file = link.match(/\/file\/d\/([a-zA-Z0-9_-]{10,})/);
  if (file) return { kind: "file", id: file[1] };
  const q = link.match(/[?&]id=([a-zA-Z0-9_-]{10,})/);
  if (q) {
    if (/embeddedfolderview|drive\/folders/.test(link)) return { kind: "folder", id: q[1] };
    return { kind: "file", id: q[1] };
  }
  if (/^[a-zA-Z0-9_-]{25,}$/.test(link)) return { kind: "file", id: link };
  return null;
}

async function listFolder(id: string): Promise<DriveTrack[]> {
  const res = await fetch(
    `https://drive.google.com/embeddedfolderview?id=${encodeURIComponent(id)}#list`,
    { headers: { "User-Agent": UA, "Accept-Language": "es,en;q=0.8" } }
  );
  if (!res.ok) {
    throw new Error(
      "No se pudo leer la carpeta. Verifica que sea pública (\"Cualquier persona con el enlace\")."
    );
  }
  const html = await res.text();
  const tracks: DriveTrack[] = [];
  const entryRe =
    /<div class="flip-entry"[^>]*id="entry-([a-zA-Z0-9_-]+)"[\s\S]*?<div class="flip-entry-title">([\s\S]*?)<\/div>/g;
  let m: RegExpExecArray | null;
  while ((m = entryRe.exec(html))) {
    const title = decodeEntities(m[2].trim());
    if (AUDIO_EXT.test(title)) {
      tracks.push({ id: m[1], title: title.replace(AUDIO_EXT, "") });
    }
  }
  return tracks;
}

async function fileTitle(id: string): Promise<string> {
  try {
    const res = await fetch(`https://drive.google.com/file/d/${encodeURIComponent(id)}/view`, {
      headers: { "User-Agent": UA, "Accept-Language": "es,en;q=0.8" },
    });
    if (!res.ok) return "Pista sin título";
    const html = await res.text();
    const t = html.match(/<title>([\s\S]*?)<\/title>/i);
    if (!t) return "Pista sin título";
    const title = decodeEntities(t[1]).replace(/\s*-\s*Google\s+Drive\s*$/i, "").trim();
    return title.replace(AUDIO_EXT, "") || "Pista sin título";
  } catch {
    return "Pista sin título";
  }
}

export async function handle(request: Request) {
  try {
    const json = superjson.parse(await request.text());
    const { links } = schema.parse(json);

    const tracks: DriveTrack[] = [];
    const warnings: string[] = [];
    const seen = new Set<string>();

    for (const raw of links) {
      const parsed = parseLink(raw);
      if (!parsed) {
        warnings.push(`Enlace no reconocido: ${raw.slice(0, 60)}`);
        continue;
      }
      try {
        if (parsed.kind === "folder") {
          const list = await listFolder(parsed.id);
          if (list.length === 0) {
            warnings.push("La carpeta no contiene archivos de audio visibles.");
          }
          for (const t of list) {
            if (!seen.has(t.id)) {
              seen.add(t.id);
              tracks.push(t);
            }
          }
        } else if (!seen.has(parsed.id)) {
          seen.add(parsed.id);
          tracks.push({ id: parsed.id, title: await fileTitle(parsed.id) });
        }
      } catch (e) {
        warnings.push(e instanceof Error ? e.message : "Error leyendo un enlace.");
      }
    }

    return new Response(superjson.stringify({ tracks, warnings } satisfies OutputType));
  } catch (error) {
    const message = error instanceof Error ? error.message : "Error desconocido";
    return new Response(superjson.stringify({ error: message }), { status: 400 });
  }
}
