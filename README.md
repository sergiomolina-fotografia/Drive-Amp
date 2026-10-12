# Drive Amp

Reproductor web con estética de Winamp clásico que toca música desde **enlaces públicos de Google Drive** (archivo o carpeta) y busca carpetas públicas de Drive.

Hecho con [Floot](https://floot.com). Versión publicada: https://driveamp.floot.app

## Qué hace

- Pegás uno o varios enlaces públicos de Drive (archivo de audio o carpeta) y arma la lista de reproducción.
- Controles: anterior, play, pausa, stop, siguiente, aleatorio, repetir, posición y volumen.
- Barra de búsqueda al pie: busca carpetas públicas de Drive por tema; cada resultado se puede cargar a la lista o abrir en Drive.

## Estructura

```
pages/_index.tsx              Página única: reproductor, lista, carga de enlaces y búsqueda
pages/_index.module.css       Estilos Winamp (ventanas biseladas, LCD verde)
pages/_index.pageLayout.tsx   Layout vacío
endpoints/drive/resolve_POST  Convierte enlaces de Drive (archivo/carpeta) en pistas
endpoints/drive/stream_GET    Pasa el audio de Drive por el servidor (Drive bloquea el uso directo desde otras webs)
endpoints/drive/search_POST   Busca carpetas públicas de Drive (búsqueda web con IA de Floot)
components/Input, Textarea    Los dos componentes del kit de Floot que usa la página
base.css                      Colores, fuentes y variables del tema
dependencies.json             Dependencias resueltas del proyecto
```

## Importante: es código de Floot

Subirlo a GitHub sirve como respaldo y control de versiones, pero **no corre tal cual** en GitHub Pages ni en otro hosting:

- Los `endpoints/` son funciones de servidor con el formato de Floot (`handle(request)` + `.schema.ts`). Fuera de Floot necesitás un backend propio (Node, Vercel, Cloudflare Workers, etc.).
- `search_POST.ts` usa `@floot/ai`, que solo existe dentro de Floot y se cobra con créditos de Floot. Para otro entorno hay que cambiarlo por una API propia con búsqueda web.
- Faltan los demás archivos base que Floot agrega a cada proyecto (resto de componentes, `helpers/`, etc.); acá solo están los de esta app y los dos componentes que importa.

## Cómo funciona la lectura de Drive

- Para carpetas se lee la vista pública `drive.google.com/embeddedfolderview?id=...` y se filtran los archivos de audio por extensión.
- El audio se reproduce desde `/_api/drive/stream?id=...`, que descarga desde `drive.usercontent.google.com`.
- Todo requiere que el archivo o carpeta esté compartido como **"Cualquier persona con el enlace"**.
