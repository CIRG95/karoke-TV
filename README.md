# 🎤 Karaoke TV

App para Android TV: buscas una canción con el control remoto, se reproduce el video de YouTube
y la letra aparece encima, sincronizada y rellenándose línea a línea como en un karaoke.
Las letras salen de dos fuentes gratuitas, sin clave:

1. **[SimpMusic Lyrics](https://lyrics.simpmusic.org)**: busca por el ID del video de YouTube, así que
   si alguien sincronizó la letra para ese video exacto, los tiempos calzan con ese video.
   Algunas entradas son traducciones; si casi no comparte palabras con la letra de LRCLIB,
   se marca como "posible traducción" y queda al final.
2. **[LRCLIB](https://lrclib.net)**: busca por artista y título sacados del nombre del video.

Todas las letras encontradas quedan disponibles en "📝 Otra letra", indicando la fuente.
SimpMusic es un proyecto comunitario no oficial: si deja de responder, la app sigue usando LRCLIB.

## Cómo se usa

La interfaz imita YouTube en la TV: barra lateral (🏠 Inicio, 🔍 Buscar, ⚙️ Clave) y filas de miniaturas.

- **Inicio**: "Porque cantaste…" (videos similares a tus últimas canciones), "Seguir cantando"
  (tus recientes) y "Tendencias de música en Chile".
- **Buscar**: resultados en cuadrícula, con duración.
- **Reproduciendo**: al elegir un video se arma una cola tipo **Mix** con canciones similares.
  Al terminar aparece "A continuación en 8…" y pasa sola a la siguiente, como en YouTube.

| Pantalla | Botón | Qué hace |
|---|---|---|
| Inicio / Buscar | Flechas | Moverse; ◀ en la primera columna abre la barra lateral |
| Buscar | OK en el buscador | Abre el teclado |
| Reproduciendo | OK / Play-Pausa | Pausa o continúa |
| Reproduciendo | ◀ ▶ | Atrasa o adelanta la letra 0,5 s |
| Reproduciendo | ▼ | Abre "A continuación" (videos similares) |
| Reproduciendo | ▲ | Abre los controles: pausa, ajuste de letra, otra letra, reproducción automática |
| Reproduciendo | ⏪ ⏩ (si tu control los tiene) | Retrocede o avanza 10 s |
| Al terminar | OK / ATRÁS | Reproducir ya el siguiente / cancelar |
| Cualquiera | ATRÁS | Vuelve / sale |

El ajuste de desfase y la letra elegida se recuerdan para cada video.

### Sobre los videos similares

YouTube eliminó en 2023 la forma oficial de pedir "videos relacionados". La app usa el
**Mix** automático que YouTube arma para cada canción, leído con un reproductor invisible.
Esto no está documentado por YouTube y podría dejar de funcionar; si el Mix no carga,
la app busca más canciones del mismo artista (gasta 1 búsqueda de cuota).

Cuota diaria: buscar gasta ~101 de 10.000 unidades (≈ 100 búsquedas al día);
los Mix, las tendencias y reproducir casi no gastan.

---

## Paso 1 · Clave gratuita de YouTube (una sola vez, ~5 minutos)

1. Entra a https://console.cloud.google.com con tu cuenta de Google.
2. Crea un proyecto (arriba, selector de proyecto → **Proyecto nuevo**, nómbralo "Karaoke").
3. Busca **YouTube Data API v3** en la barra superior y pulsa **Habilitar**.
4. Ve a **APIs y servicios → Credenciales → Crear credenciales → Clave de API**.
5. Abre la clave creada → **Restricciones de API → Restringir clave → YouTube Data API v3** → Guardar.
6. Copia la clave (empieza con `AIza…`).

Es gratis y no pide tarjeta. Alcanza para unas **100 búsquedas al día**
(reproducir no gasta cuota, solo buscar).

## Paso 2 · Compilar el APK (GitHub lo hace solo)

1. Crea una cuenta en https://github.com si no tienes.
2. Crea un repositorio nuevo **público** (por ejemplo `karaoke-tv`).
3. Sube todo el contenido de esta carpeta (en el PC: "Add file → Upload files" y arrastra
   todas las carpetas y archivos, incluida la carpeta oculta `.github`).
4. Ve a la pestaña **Actions**: la compilación parte sola y tarda ~3 minutos.
5. Cuando termine, en **Releases** aparece `KaraokeTV.apk`. El enlace directo es siempre:

   `https://github.com/TU_USUARIO/karaoke-tv/releases/download/latest/KaraokeTV.apk`

Cada vez que cambies algo y lo subas, se genera una versión nueva en el mismo enlace.

> El repositorio puede ser público sin riesgo: la clave de YouTube **no** va en el código,
> se escribe dentro de la app en la TV.

## Paso 3 · Instalar en la TV

1. En la TV, instala la app **Downloader** (de AFTVnews) desde Google Play.
2. Ábrela, escribe el enlace directo del paso 2 y descarga.
3. Cuando lo pida, permite a Downloader **instalar apps desconocidas**.
4. Instala. "Karaoke TV" aparece en la fila de apps.

Para actualizar: repite el paso 2 de Downloader con el mismo enlace; se instala encima.

## Paso 4 · Primer uso

La app pide la clave. Escribirla con el control es lento, así que usa la app **Google TV**
(o Google Home → control remoto) en el celular: tiene teclado y puedes **pegar** la clave.

---

## Problemas comunes

- **"Este video no se puede reproducir fuera de YouTube"**: algunos sellos bloquean la
  reproducción en otras apps. Vuelve y elige otra versión del mismo tema (lyric video,
  audio, en vivo). La búsqueda ya filtra la mayoría de estos casos.
- **La letra va desfasada**: pasa con videos que tienen intro o escenas extra.
  Ajusta con ◀ ▶ (queda guardado para ese video). Escoger el "lyric video" o el
  "audio" del tema suele calzar perfecto.
- **Letra equivocada o sin letra**: prueba ▲ ▼ para ver otras versiones. Si no hay ninguna,
  la canción no está en LRCLIB todavía (cualquiera puede agregarla allá).
- **"La clave no funcionó"**: revisa que copiaste la clave completa y que habilitaste
  YouTube Data API v3 en ese mismo proyecto. Cámbiala con ⚙ Clave.

## Estructura

- `app/src/main/assets/index.html` — toda la interfaz: búsqueda, reproductor, sincronización.
- `app/src/main/java/.../MainActivity.java` — aloja el navegador interno y le da acceso a internet, teclado y memoria.
- `.github/workflows/build.yml` — compila y publica el APK.
- `app/karaoke.keystore` — firma fija para que las actualizaciones se instalen encima. Solo para uso personal.
