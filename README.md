# ⚡ Drive-Amp (Android Native & Android Auto)

**Drive-Amp** es un reproductor multimedia nativo para Android e inspirado en la estética clásica de **Winamp**, diseñado especialmente para integrarse tanto en teléfonos móviles como en sistemas de **Android Auto**.

Permite reproducir música en streaming desde **enlaces públicos de Google Drive** (a través de un servidor backend/proxy como Floot), **enlaces HTTP directos** y **archivos de audio locales/offline** del dispositivo.

---

## 🚀 Características Principales

1. **Estética Retro Winamp (Jetpack Compose):**
   - Interfaz clásica estilo reproductor de escritorio con barra de título, ventana de control y botones de transporte retro (`⏮`, `▶`, `❚❚`, `⏹`, `⏭`, `⏏`).
   - **Pantalla LED Digital:** Muestra el estado, fuente activa, temporizador y contador de pistas.
   - **Visualizador de Audio Interactivo:** Barras ecualizadoras animadas que cambian de estilo y colores al tocar la pantalla.
   - **Control de Volumen** deslizante con indicador porcentual.

2. **Fuentes de Audio Flexibles:**
   - **Google Drive Público:** Resolución y streaming de carpetas y archivos públicos de Google Drive mediante el servidor backend configurado.
   - **Enlaces HTTP / Streams:** Reproducción de enlaces directos de red, servidores multimedia (Jellyfin, Navidrome, Plex, Nextcloud) o radios online.
   - **Audio Local / Offline:** Selector de archivos individuales y escaneo recursivo completo de carpetas locales del dispositivo.
   - **Sistema de Favoritos:** Guardado y acceso rápido a tus pistas preferidas.

3. **Integración Nativa con Android Auto:**
   - Implementado con **Jetpack Media3 (`MediaLibraryService` + `ExoPlayer`)**.
   - Sincronización automática de la playlist para explorarla directamente desde la pantalla táctil del auto.
   - Control total desde los botones físicos del volante (**Siguiente**, **Anterior**, **Play/Pause**).

4. **Widget de Pantalla de Inicio:**
   - Mini reproductor retro integrado con Jetpack Glance para la pantalla de inicio de tu celular.

---

## 🛠️ Arquitectura y Tecnologías
- **Lenguaje:** Kotlin
- **UI:** Jetpack Compose & Material 3
- **Reproducción y Auto:** AndroidX Media3 (ExoPlayer + MediaSession + MediaLibraryService)
- **Concurrencia:** Kotlin Coroutines
- **Widgets:** Jetpack Glance

---

## ⚙️ Configuración del Servidor Backend (Proxy)
Para reproducir enlaces de Google Drive sin bloqueos de CORS, la app incluye un panel de configuración (**`⚙ CFG`**) donde puedes ingresar la URL base de tu servidor backend compatible con los endpoints:
- `/_api/drive/resolve`
- `/_api/drive/search`
- `/_api/drive/stream`

---

## 📦 Instalación y Compilación
Puedes compilar el proyecto directamente desde Android Studio o generar el APK de depuración:
```bash
./gradlew assembleDebug
```
El archivo instalable se generará en:
`app/build/outputs/apk/debug/app-debug.apk`

---
*Inspirado en el proyecto web [Drive-Amp Web Player](https://sergiomolina-fotografia.github.io/Drive-Amp/).*
