# AppScreen Crop — 0.3

Android Kotlin + Jetpack Compose. Android 10+. El recorte visual se aplica solo a la app elegida (p. ej. Xuper TV) y únicamente mientras está en primer plano (permisos: Mostrar sobre otras apps + Acceso de uso). Objetivo principal: **1280×800 (16:10)**; también 16:9 y 4:3. Orientación automática, horizontal o vertical; ajuste automático o manual conservando la proporción.

## Dos métodos (ninguno simulado)
- **Recorte visual:** superpone barras negras opacas sobre la otra app (permiso «Mostrar sobre otras apps»). No cambia la resolución del sistema; las zonas cubiertas no responden al tacto.
- **Resolución del sistema:** ejecuta el equivalente a `wm size` / `wm density`. Cambia la resolución *lógica* del sistema, no el panel físico. Android solo lo permite con privilegios elevados (root, o permiso concedido por ADB). Si se deniega, la app lo informa y no cambia nada.

## Restauración
Antes de cambiar nada se guardan en disco el tamaño y la densidad originales. El botón **Restaurar resolución original** los recupera y verifica. Tras aplicar, hay una cuenta atrás de 15 s para confirmar; si no confirmas, se restaura sola. Emergencia desde PC: `adb shell wm size reset` y `adb shell wm density reset`.

## Compilar en GitHub
Actions → «Build AppScreen Crop» → Run workflow → artefacto `AppScreenCrop-debug` (ZIP con `app-debug.apk`).

## Modo TV (recomendado)
Cambia la resolución lógica solo mientras la app elegida (p. ej. Xuper TV) está en primer plano y la restaura al salir. Necesita permiso elevado: **Shizuku** (sin PC en Android 11+), root o `pm grant ... WRITE_SECURE_SETTINGS` por ADB. Sin ese permiso Android lo niega y la app lo avisa sin cambiar nada.

## Resolución personalizada
En «Formato» elige **Personalizada** y escribe ancho y alto (320–4096) o usa un atajo (1280×800, 1280×720, 1366×768, 1920×1080). La proporción se calcula de esos valores y se usa tanto en el Modo TV como en las barras.

El límite de la resolución personalizada es la **resolución física máxima de cada teléfono** (se detecta al abrir la app). Si un valor la supera, se rechaza; los formatos automáticos se ajustan al máximo.
