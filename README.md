# ⚡ Drive-Amp (Android Native & Android Auto)

**Drive-Amp** es un reproductor multimedia nativo para Android inspirado fielmente en el aspecto clásico de **Winamp** y en [Drive-Amp-Web](https://github.com/sergiomolina-fotografia/Drive-Amp-Web), diseñado para funcionar tanto en teléfonos móviles como en **Android Auto** y mediante su **Widget de pantalla de inicio**.

Permite buscar, explorar y reproducir música en streaming directamente desde **carpetas y archivos públicos de Google Drive** (100% autónomo desde el dispositivo, sin depender de servidores proxy ni API Keys externas), **enlaces HTTP directos** y **archivos/carpetas de audio locales**.

---

## 🚀 Características Principales

1. **Estética Clásica Winamp (`DRIVE-AMP` Skin en Jetpack Compose):**
   - Ventana principal con barra de título metálica rayada, botones superiores funcionales de minimizar (`_`) y cerrar (`✕`), y paneles acoplables desplegables.
   - **Pantalla LCD Retro:** Indicador de estado (`▶`, `❚❚`, `■`), reloj digital verde brillante, marquesina de pista actual, origen y contador de pistas.
   - **Visualizador de Audio Rítmico en Tiempo Real (PCM + FFT):** Analiza internamente el flujo de audio de ExoPlayer (`TeeAudioProcessor`) sin requerir permiso de micrófono. Incluye 6 modos seleccionables con el botón `◐` (`PUNTOS`, `BARRAS`, `ONDA`, `ESPEJO`, `RADIAL`, `APAGADO`).
   - **Controles de Transporte 3D Biselados:** `⏮`, `▶`, `⏸`, `⏹`, `⏭`, `⏏` (archivos) y `📁` (carpetas), además de barra de progreso y volumen deslizante.

2. **Ecualizador Gráfico de 8 Bandas en Tiempo Real (`ECUALIZADOR 🎚`):**
   - Procesamiento digital Biquad en tiempo real integrado en el pipeline de audio (`60Hz`, `170Hz`, `310Hz`, `600Hz`, `1K`, `3K`, `6K`, `14K`) con rango de `-12 dB` a `+12 dB`.
   - Pantalla LCD con la curva de respuesta en frecuencia en vivo.
   - **7 Presets incluidos:** `PLANO`, `ROCK`, `POP`, `BASS+`, `JAZZ`, `CLÁSICA` y `ELECTRO`, con guardado persistente.

3. **Motor Autónomo de Google Drive Público y Fuentes de Red:**
   - **`BUSCAR DRIVE 🔍`:** Busca carpetas públicas de música en Google Drive directamente desde el dispositivo, inspecciona sus títulos reales y permite navegar subcarpetas (`📂 ABRIR`), cargar pistas (`+ CARGAR`) o guardarlas en Favoritos (`★`).
   - **`ABRIR DRIVE ✔`:** Resuelve enlaces públicos de archivos o carpetas de Google Drive (`embeddedfolderview`) y reproduce mediante streaming directo.
   - **`ABRIR RED 🔗`:** Reproducción de enlaces directos HTTP/HTTPS (MP3, FLAC, OGG, WAV, M4A), servidores multimedia (NAS, Jellyfin, Navidrome) o radios online.
   - **Audio Local / Offline:** Carga de archivos sueltos (`+ ARCHIVOS`), carpetas completas (`+ CARPETA`) y escaneo automático de subcarpetas musicales a Favoritos (`ESCANEAR 📂★`).

4. **Listas Guardadas, Favoritos Permanentes y Avance Continuo:**
   - **`FAVORITOS ★`:** Guarda listas completas (locales, Drive o mixtas) y pistas favoritas de forma permanente e independiente del botón `VACIAR` de la cola actual.
   - **Reproducción Continua entre Listas:** Al finalizar la última canción de una lista activa, limpia la cola y continúa automáticamente con la siguiente lista guardada en Favoritos.

5. **Integración Completa con Android Auto y Widget:**
   - Implementado con **Jetpack Media3 (`MediaLibraryService` + `ExoPlayer`)**.
   - Exploración de `Lista de reproducción`, `Favoritos ★` y `Listas guardadas` desde la pantalla del auto y control desde el volante (**Siguiente**, **Anterior**, **Play/Pause**).
   - **Widget Retro en Pantalla de Inicio** construido con Jetpack Glance.

---

## 🛠️ Arquitectura y Tecnologías
- **Lenguaje:** Kotlin
- **UI:** Jetpack Compose & Material 3 (Winamp Classic Bevel Skin)
- **Audio, DSP y Auto:** AndroidX Media3 (`ExoPlayer`, `MediaLibraryService`, `TeeAudioProcessor`, `AudioProcessor` Biquad EQ + FFT Analyzer)
- **Concurrencia:** Kotlin Coroutines
- **Widget:** Jetpack Glance

---

## 📦 Compilación y Generación del APK
Podés compilar el proyecto desde Android Studio o por consola:
```bash
./gradlew :app:assembleDebug
```
Al finalizar la compilación, el instalable se genera automáticamente con el nombre:
`app/build/outputs/apk/debug/DriveAmp.apk`

---
*Inspirado en el proyecto [Drive-Amp-Web](https://github.com/sergiomolina-fotografia/Drive-Amp-Web).*
