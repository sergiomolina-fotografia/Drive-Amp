import { schema } from "./stream_GET.schema";

const UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

function errorResponse(message: string, status: number) {
  return new Response(JSON.stringify({ error: message }), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

export async function handle(request: Request) {
  try {
    const url = new URL(request.url);
    const { id } = schema.parse({ id: url.searchParams.get("id") });

    const upstreamUrl = `https://drive.usercontent.google.com/download?id=${encodeURIComponent(
      id
    )}&export=download&confirm=t`;

    const headers: Record<string, string> = { "User-Agent": UA };
    const range = request.headers.get("range");
    if (range) headers["Range"] = range;

    const upstream = await fetch(upstreamUrl, { headers, redirect: "follow" });

    if (!upstream.ok && upstream.status !== 206) {
      return errorResponse(
        `Drive respondió ${upstream.status}. Verifica que el archivo sea público.`,
        upstream.status === 404 ? 404 : 502
      );
    }

    const upstreamType = upstream.headers.get("content-type") ?? "";
    if (upstreamType.includes("text/html")) {
      return errorResponse(
        "Drive devolvió una página en lugar del audio (archivo privado o con restricciones).",
        403
      );
    }

    const out = new Headers();
    out.set(
      "Content-Type",
      upstreamType && !upstreamType.includes("octet-stream") ? upstreamType : "audio/mpeg"
    );
    for (const h of ["content-length", "content-range", "accept-ranges", "etag", "last-modified"]) {
      const v = upstream.headers.get(h);
      if (v) out.set(h, v);
    }
    if (!out.has("accept-ranges")) out.set("Accept-Ranges", "bytes");
    out.set("Cache-Control", "private, max-age=3600");

    return new Response(upstream.body, { status: upstream.status, headers: out });
  } catch (error) {
    const message = error instanceof Error ? error.message : "Error desconocido";
    return errorResponse(message, 400);
  }
}
