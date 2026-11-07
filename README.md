# AppScreen Crop — prototipo 0.1

Android Kotlin + Jetpack Compose. Android 10+.

Incluye interfaz azul oscuro, listado de aplicaciones lanzables, selección y apertura, controles iniciales automático/manual y workflow de GitHub Actions.

**Limitación importante:** esta versión todavía no recorta ni redimensiona realmente otras aplicaciones. Android no permite que una app normal cambie directamente el tamaño de la ventana de otra. MediaProjection captura la pantalla, pero mostrarla encima de la app objetivo y conservar el tacto requiere una arquitectura adicional, permisos y pruebas específicas. No se afirma que esa función ya esté implementada.

## Compilar en GitHub
1. Sube el contenido del ZIP a un repositorio.
2. Abre Actions.
3. Ejecuta "Build AppScreen Crop".
4. Descarga el artefacto `AppScreenCrop-debug`.
