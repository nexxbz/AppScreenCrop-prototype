package com.appscreencrop

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.concurrent.thread
import kotlin.math.roundToInt

private val Navy = Color(0xFF101D36)
private val Blue = Color(0xFF287BE6)
private val Soft = Color(0xFF9EB8DC)
private val Warn = Color(0xFFFFD58A)

data class LaunchableApp(val label: String, val packageName: String)

class MainActivity : ComponentActivity() {
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val displayCtl by lazy { DisplayController(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        val pending = displayCtl.hasAppliedChange()
        val maxRes = DisplayController.deviceMax(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Blue, background = Navy, surface = Color(0xFF1B2C49))) {
                Home(
                    apps = loadLaunchableApps(),
                    pendingChange = pending,
                    maxRes = maxRes,
                    onOpenOverlay = { app, cfg -> openWithCrop(app, cfg) },
                    onUpdateOverlay = { cfg -> startOverlay(cfg) },
                    onStopOverlay = { stopService(Intent(this, CropOverlayService::class.java)) },
                    onApplySystem = { w, h, dpi, done -> runAsync({ displayCtl.apply(w, h, dpi) }, done) },
                    onRestore = { done -> runAsync({ displayCtl.restore() }, done) },
                    onLaunch = { app -> launchApp(app) },
                    elevatedState = { Elevated.state() },
                    onRequestElevated = { Elevated.requestPermission() },
                    onOpenShizuku = { openShizuku() }
                )
            }
        }
    }

    private fun runAsync(work: () -> DisplayController.Result, done: (DisplayController.Result) -> Unit) {
        thread {
            val r = work()
            runOnUiThread { done(r) }
        }
    }

    private fun startOverlay(c: CropConfig) {
        val i = Intent(this, CropOverlayService::class.java)
            .putExtra(CropOverlayService.EXTRA_ORIENT, c.orientation)
            .putExtra(CropOverlayService.EXTRA_RATIO, c.ratio)
            .putExtra(CropOverlayService.EXTRA_SCALE, c.scale)
            .putExtra(CropOverlayService.EXTRA_TARGET, c.target)
            .putExtra(CropOverlayService.EXTRA_COVER, c.cover)
            .putExtra(CropOverlayService.EXTRA_PREVIEW, c.previewMs)
            .putExtra(CropOverlayService.EXTRA_SYSTEM, c.system)
            .putExtra(CropOverlayService.EXTRA_W, c.sizeW)
            .putExtra(CropOverlayService.EXTRA_H, c.sizeH)
            .putExtra(CropOverlayService.EXTRA_DPI, c.dpi)
        runCatching { startForegroundService(i) }
    }

    private fun openShizuku() {
        val i = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        if (i != null) startActivity(i)
        else Toast.makeText(this, "Shizuku no está instalado. Búscalo en Google Play o en github.com/RikkaApps/Shizuku", Toast.LENGTH_LONG).show()
    }

    private fun launchApp(app: LaunchableApp) {
        packageManager.getLaunchIntentForPackage(app.packageName)?.let { startActivity(it) }
    }

    private fun openWithCrop(app: LaunchableApp?, cfg: CropConfig): Boolean {
        if (!cfg.system && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Activa «Mostrar sobre otras apps» para AppScreen Crop y vuelve a pulsar", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return false
        }
        if (!hasUsageAccess()) {
            Toast.makeText(this, "Activa el acceso de uso para AppScreen Crop: así el recorte solo se muestra en la app elegida", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return false
        }
        startOverlay(cfg)
        app?.let { launchApp(it) }
        return true
    }

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName) ==
            AppOpsManager.MODE_ALLOWED
    }

    @Suppress("DEPRECATION")
    private fun loadLaunchableApps(): List<LaunchableApp> {
        val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { LaunchableApp(it.loadLabel(packageManager).toString(), it.activityInfo.packageName) }
            .filter { it.packageName != this@MainActivity.packageName }
            .distinctBy { it.packageName }
            .sortedWith(compareByDescending<LaunchableApp> { it.label.contains("xuper", ignoreCase = true) }.thenBy { it.label.lowercase() })
    }
}

private fun ratioOf(s: String) = when (s) { "16:9" -> 16f / 9f; "4:3" -> 4f / 3f; else -> 16f / 10f }

/** Base 1280 px de ancho; 16:10 → 1280×800 (objetivo principal). El alto sale de la proporción. */
private fun baseSize(ratio: Float, scale: Float): Pair<Int, Int> {
    val w = ((1280f * scale) / 2f).roundToInt() * 2
    val h = ((w / ratio) / 2f).roundToInt() * 2
    return w to h
}

@Composable
private fun Home(
    apps: List<LaunchableApp>,
    pendingChange: Boolean,
    maxRes: Pair<Int, Int>,
    onOpenOverlay: (LaunchableApp?, CropConfig) -> Boolean,
    onUpdateOverlay: (CropConfig) -> Unit,
    onStopOverlay: () -> Unit,
    onApplySystem: (Int, Int, Int, (DisplayController.Result) -> Unit) -> Unit,
    onRestore: ((DisplayController.Result) -> Unit) -> Unit,
    onLaunch: (LaunchableApp) -> Unit,
    elevatedState: () -> Int,
    onRequestElevated: () -> Unit,
    onOpenShizuku: () -> Unit
) {
    var selected by remember { mutableStateOf<LaunchableApp?>(null) }
    var automatic by remember { mutableStateOf(true) }
    var ratio by remember { mutableStateOf("16:10") }
    var customW by rememberSaveable { mutableStateOf("1280") }
    var customH by rememberSaveable { mutableStateOf("800") }
    var orientation by remember { mutableIntStateOf(0) }
    var scalePct by remember { mutableFloatStateOf(100f) }
    var coverPct by remember { mutableFloatStateOf(100f) }
    var stretch by rememberSaveable { mutableStateOf(false) }
    // Valores de la tablet del usuario: ancho mínimo 600 dp con 1280×800 → 213 dpi. «Auto» los borra.
    var dpiText by rememberSaveable { mutableStateOf("213") }
    var swText by rememberSaveable { mutableStateOf("600") }
    var method by remember { mutableIntStateOf(0) }
    var active by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var elev by remember { mutableIntStateOf(elevatedState()) }
    LaunchedEffect(Unit) {
        while (true) {
            elev = elevatedState()
            delay(1500)
        }
    }
    var confirmSeconds by remember { mutableStateOf<Int?>(null) }
    var status by remember {
        mutableStateOf(if (pendingChange) "La app dejó una resolución modificada. Pulsa «Restaurar resolución original»." else "")
    }

    val isCustom = ratio == "Personalizada"
    val cw = customW.toIntOrNull()
    val ch = customH.toIntOrNull()
    val maxShort = maxRes.first
    val maxLong = maxRes.second
    val customSize: Pair<Int, Int>? =
        if (isCustom && cw != null && ch != null && minOf(cw, ch) >= 320 && minOf(cw, ch) <= maxShort && maxOf(cw, ch) <= maxLong) cw to ch else null
    val effRatio = customSize?.let { it.first.toFloat() / it.second } ?: ratioOf(ratio)
    val scale = if (automatic || isCustom) 1f else scalePct / 100f
    val config = CropConfig(orientation, effRatio, scale.coerceAtMost(1f), selected?.packageName, coverPct / 100f)
    val raw = customSize ?: baseSize(effRatio, scale)
    val fit = minOf(1f, maxShort.toFloat() / minOf(raw.first, raw.second), maxLong.toFloat() / maxOf(raw.first, raw.second))
    val limited = customSize == null && fit < 1f
    val base = if (limited) (((raw.first * fit) / 2f).roundToInt() * 2) to (((raw.second * fit) / 2f).roundToInt() * 2) else raw
    val chosen = if (orientation == 2) base.second to base.first else base
    // Estirar: se conserva el lado corto de la resolución elegida y el lado largo toma la proporción de la pantalla del teléfono.
    val stretchOn = stretch && method != 1
    val target = if (stretchOn) {
        val ps = minOf(chosen.first, chosen.second)
        val pl = ((ps * maxLong.toFloat() / maxShort) / 2f).roundToInt() * 2
        if (chosen.first >= chosen.second) pl to ps else ps to pl
    } else chosen

    val dpiValue = dpiText.toIntOrNull()?.takeIf { it in 72..800 } ?: 0
    val dpiInvalid = dpiText.isNotEmpty() && dpiValue == 0
    val shortSide = minOf(target.first, target.second)
    val activeConfig = if (method == 0) config.copy(system = true, sizeW = target.first, sizeH = target.second, dpi = dpiValue) else config

    val doRestore: () -> Unit = {
        busy = true
        onRestore { r -> busy = false; status = r.message }
    }

    val applyNow: () -> Unit = {
        val app = selected
        if (isCustom && customSize == null) {
            status = "Escribe un ancho y un alto válidos (mínimo 320; máximo de este teléfono ${maxLong}×${maxShort})."
        } else if (dpiInvalid && method != 1) {
            status = "La densidad debe estar entre 72 y 800 dpi (o déjala vacía para automática)."
        } else when (method) {
            0 -> {
                if (app == null) {
                    status = "Selecciona la app (por ejemplo Xuper TV)."
                } else if (onOpenOverlay(app, activeConfig)) {
                    active = true
                    status = "Modo TV para ${app.label}: se aplicará ${target.first} × ${target.second} solo mientras esté abierta y se restaurará al salir. Si Android no lo permite, verás un aviso."
                }
            }
            1 -> {
                if (app == null) {
                    status = "Selecciona la app (por ejemplo Xuper TV) para que el recorte se aplique solo en ella."
                } else if (onOpenOverlay(app, config)) {
                    active = true
                    status = "Barras activas solo en ${app.label}: ${target.first} × ${target.second}. Se muestran únicamente cuando esa app está en primer plano."
                }
            }
            else -> {
                onStopOverlay(); active = false
                busy = true; status = "Aplicando…"
                onApplySystem(target.first, target.second, dpiValue) { r ->
                    busy = false; status = r.message
                    if (r.ok) confirmSeconds = 15
                }
            }
        }
    }

    LaunchedEffect(automatic, ratio, customW, customH, orientation, scalePct, coverPct, selected, stretch, dpiText) {
        if (active && method <= 1) onUpdateOverlay(activeConfig)
    }

    val confirming = confirmSeconds != null
    LaunchedEffect(confirming) {
        if (confirming) {
            while (true) {
                delay(1000)
                val c = confirmSeconds ?: break
                if (c <= 1) { confirmSeconds = null; doRestore(); break }
                confirmSeconds = c - 1
            }
        }
    }

    confirmSeconds?.let { secs ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("¿Mantener esta resolución?") },
            text = { Text("Si no confirmas, se restaurará la resolución original en $secs s.") },
            confirmButton = { TextButton(onClick = { confirmSeconds = null; selected?.let(onLaunch) }) { Text("Mantener") } },
            dismissButton = { TextButton(onClick = { confirmSeconds = null; doRestore() }) { Text("Restaurar ahora") } }
        )
    }

    Surface(Modifier.fillMaxSize(), color = Navy) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = Blue, shape = RoundedCornerShape(14.dp)) { Icon(Icons.Default.Tv, null, Modifier.padding(12.dp), tint = Color.White) }
                    Spacer(Modifier.width(12.dp))
                    Column { Text("AppScreen Crop", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White); Text("SMART DISPLAY CONTROL", fontSize = 11.sp, color = Soft) }
                }
            }
            item {
                Surface(
                    color = Color(0xFF0B0B0B),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, Color(0xFF2A2A2A)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Configuración de pantalla", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Surface(color = Color(0xFF262626), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Resolución seleccionada", color = Color(0xFFBDBDBD), fontSize = 14.sp)
                                Text("${target.first} × ${target.second}", color = Color(0xFF00C853), fontSize = 38.sp, fontWeight = FontWeight.Bold)
                                Text("Formato ${if (isCustom) "personalizado" else ratio}" + if (stretchOn) " · llena la pantalla" else "", color = Color(0xFFBDBDBD), fontSize = 14.sp)
                                Text("Máximo de este teléfono: $maxLong × $maxShort" + if (limited) " · valor ajustado al máximo" else "", color = Color(0xFF8A8A8A), fontSize = 12.sp)
                            }
                        }
                        Text(
                            when (method) {
                                0 -> "Modo TV: ${target.first} × ${target.second} solo mientras ${selected?.label ?: "la app elegida"} está abierta"
                                1 -> "Barras negras solo sobre: ${selected?.label ?: "— elige una app abajo"}"
                                else -> "Afecta a TODO el teléfono, no solo a una app"
                            },
                            color = Soft, fontSize = 12.sp
                        )
                        Button(
                            onClick = { applyNow() },
                            enabled = !busy,
                            shape = RoundedCornerShape(50),
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                        ) { Text("Aplicar resolución", fontWeight = FontWeight.SemiBold) }
                        OutlinedButton(
                            onClick = { onStopOverlay(); active = false; doRestore() },
                            enabled = !busy,
                            shape = RoundedCornerShape(50),
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                        ) {
                            Icon(Icons.Default.Refresh, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Restaurar resolución original", fontWeight = FontWeight.SemiBold)
                        }
                        if (status.isNotEmpty()) Text(status, color = Warn, fontSize = 12.sp)
                        Text("Si algo queda mal, desde un PC: adb shell wm size reset  y  adb shell wm density reset", color = Soft, fontSize = 11.sp)
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Aplicación objetivo", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(selected?.label ?: "Selecciona una aplicación instalada", color = Color(0xFFB9CBE4))
                }
            }
            items(apps) { app ->
                Surface(onClick = { selected = app }, color = if (selected?.packageName == app.packageName) Color(0xFF244D80) else Color(0xFF1B2C49), shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Apps, null, tint = Color(0xFF8DBEFF))
                        Spacer(Modifier.width(10.dp)); Text(app.label, color = Color.White, modifier = Modifier.weight(1f))
                        if (selected?.packageName == app.packageName) Text("SELECCIONADA", color = Color(0xFF8DBEFF), fontSize = 10.sp)
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider(color = Color(0xFF344968))

                    Text("Método", color = Color.White, fontWeight = FontWeight.SemiBold)
                    FilterChip(selected = method == 0, onClick = { method = 0 }, label = { Text("Modo TV solo en la app (recomendado)") })
                    FilterChip(selected = method == 1, onClick = { method = 1 }, label = { Text("Barras negras (sin permisos especiales)") })
                    FilterChip(selected = method == 2, onClick = { method = 2 }, label = { Text("Todo el sistema (avanzado)") })
                    Text(
                        when (method) {
                            0 -> "Cambia la resolución a ${target.first}×${target.second} únicamente mientras la app elegida está abierta y la restaura al salir. La app se reorganiza como en un Smart TV en lugar de quedar tapada. Cómo se ajusta a tu pantalla (estirada o con bordes) lo decide el fabricante. Requiere permiso elevado (Shizuku, root o ADB) y «Acceso de uso». Si Android lo niega, verás un aviso y no se cambia nada."
                            1 -> "Superpone barras negras sobre la app elegida mientras está abierta. Solo tapa: no reorganiza la app, por eso puede cortar menús. Pide «Mostrar sobre otras apps» y «Acceso de uso»."
                            else -> "Esto NO es por app: cambia la resolución LÓGICA de todo el sistema y ajusta la densidad en proporción. No modifica el panel físico. Requiere permiso elevado (ADB/root)."
                        },
                        color = Soft, fontSize = 12.sp
                    )

                    if (method != 1) {
                        Surface(color = Color(0xFF1B2C49), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    "Permiso elevado (Shizuku): " + when (elev) { 2 -> "listo ✓"; 1 -> "falta conceder permiso"; else -> "Shizuku no está activo" },
                                    color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp
                                )
                                when (elev) {
                                    2 -> Text("Ya puedes usar el Modo TV sin PC.", color = Soft, fontSize = 12.sp)
                                    1 -> Button(onClick = { onRequestElevated() }, modifier = Modifier.fillMaxWidth()) { Text("Conceder permiso a AppScreen Crop") }
                                    else -> {
                                        Text("1) Instala Shizuku. 2) Actívalo: en Android 11 o superior, con «Depuración inalámbrica» desde el propio teléfono; en Android 10 hace falta un PC con ADB tras cada reinicio. 3) Vuelve aquí y concede el permiso. Con root no necesitas Shizuku.", color = Soft, fontSize = 12.sp)
                                        OutlinedButton(onClick = { onOpenShizuku() }, modifier = Modifier.fillMaxWidth()) { Text("Abrir Shizuku") }
                                    }
                                }
                            }
                        }
                    }
                    Text("Modo de ajuste", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = automatic, onClick = { automatic = true }, label = { Text("Automático") })
                        FilterChip(selected = !automatic, onClick = { automatic = false }, label = { Text("Manual") })
                    }

                    Text("Orientación", color = Color(0xFFB9CBE4))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = orientation == 0, onClick = { orientation = 0 }, label = { Text("Auto") })
                        FilterChip(selected = orientation == 1, onClick = { orientation = 1 }, label = { Text("Horizontal") })
                        FilterChip(selected = orientation == 2, onClick = { orientation = 2 }, label = { Text("Vertical") })
                    }
                    if (method != 1) Text("En los modos de resolución, el tamaño se aplica en la orientación natural del dispositivo y gira con él.", color = Soft, fontSize = 12.sp)

                    Text("Formato (se conserva la proporción)", color = Color(0xFFB9CBE4))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        listOf("16:10", "16:9", "4:3", "Personalizada").forEach { v -> FilterChip(selected = ratio == v, onClick = { ratio = v }, label = { Text(v) }) }
                    }
                    if (isCustom) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = customW,
                                onValueChange = { v -> customW = v.filter { c -> c.isDigit() }.take(4) },
                                label = { Text("Ancho") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                            Text("×", color = Color.White, fontSize = 20.sp)
                            OutlinedTextField(
                                value = customH,
                                onValueChange = { v -> customH = v.filter { c -> c.isDigit() }.take(4) },
                                label = { Text("Alto") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            val presets = (listOf(maxLong to maxShort, 1920 to 1080, 1366 to 768, 1280 to 800, 1280 to 720))
                                .filter { it.second <= maxShort && it.first <= maxLong }
                                .distinct()
                            presets.forEach { (w, h) ->
                                AssistChip(
                                    onClick = { customW = "$w"; customH = "$h" },
                                    label = { Text(if (w == maxLong && h == maxShort) "Máx. $w×$h" else "$w×$h") }
                                )
                            }
                        }
                        Text(
                            if (customSize == null) "Escribe valores entre 320 y $maxLong. Máximo de este teléfono: $maxLong×$maxShort."
                            else "Se aplicará exactamente ${customSize.first}×${customSize.second} (la proporción sale de esos valores).",
                            color = Soft, fontSize = 12.sp
                        )
                    }
                    Text("Principal: 16:10 = 1280×800.", color = Soft, fontSize = 12.sp)

                    if (!automatic && !isCustom) {
                        Text("Escala del área: ${scalePct.toInt()}%", color = Color(0xFFB9CBE4))
                        Slider(value = scalePct, onValueChange = { scalePct = it }, valueRange = 50f..150f)
                        if (method == 1) Text("En barras negras, más de 100% equivale a 100% (el máximo que cabe en pantalla).", color = Soft, fontSize = 12.sp)
                    }

                    if (method != 1) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Llenar pantalla sin deformar", color = Color.White, fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (stretch) "Se mantiene la altura de tu resolución (${minOf(chosen.first, chosen.second)} px) y el ancho se adapta a la proporción de tu pantalla: la app ocupa todo, sin bordes ni imagen deformada. Se aplicará ${target.first}×${target.second}."
                                    else "Desactivado: se aplica la proporción elegida tal cual (${target.first}×${target.second}). Aquí es tu teléfono quien decide si estira la imagen para llenar la pantalla o deja bordes: pruébalo con «Aplicar resolución» y mira qué hace.",
                                    color = Soft, fontSize = 12.sp
                                )
                                Text("El «Estirar» de la galería es un modo de su propio reproductor. Una app no puede deformar a otra: solo cambia la resolución y el sistema decide cómo la muestra.", color = Soft, fontSize = 11.sp)
                            }
                            Switch(checked = stretch, onCheckedChange = { stretch = it })
                        }
                    }

                    if (method != 1) {
                        Text("Densidad (para que la app se acomode como en tu tablet)", color = Color.White, fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = swText,
                            onValueChange = { v ->
                                swText = v.filter { c -> c.isDigit() }.take(4)
                                swText.toIntOrNull()?.takeIf { it in 200..1500 }?.let { dpiText = (shortSide * 160f / it).roundToInt().toString() }
                            },
                            label = { Text("Ancho mínimo de la tablet (dp)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = dpiText,
                            onValueChange = { v -> dpiText = v.filter { c -> c.isDigit() }.take(3) },
                            label = { Text("Densidad en dpi (vacío = automática)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            AssistChip(onClick = { dpiText = ""; swText = "" }, label = { Text("Auto") })
                            listOf(160, 213, 240, 320).forEach { d -> AssistChip(onClick = { dpiText = "$d"; swText = "" }, label = { Text("$d dpi") }) }
                        }
                        Text(
                            if (dpiValue > 0) "Con $dpiValue dpi el ancho mínimo queda en ${shortSide * 160 / dpiValue} dp. Si coincide con el de tu tablet, Xuper TV se acomodará igual."
                            else "Automática: conserva el tamaño actual de los elementos del teléfono. Para copiar tu tablet: en ella, Ajustes → Opciones de desarrollador → «Ancho mínimo»; escribe ese número arriba y la densidad se calcula sola.",
                            color = Soft, fontSize = 12.sp
                        )
                    }

                    if (method == 1) {
                        Text("Ancho de las barras: ${coverPct.toInt()}%", color = Color(0xFFB9CBE4))
                        Slider(value = coverPct, onValueChange = { coverPct = it }, valueRange = 0f..100f)
                        Text("Las barras tapan una parte de la app. Si cortan menús o textos, baja este valor hasta que se vea todo.", color = Soft, fontSize = 12.sp)
                        OutlinedButton(
                            onClick = {
                                if (selected == null) {
                                    status = "Selecciona primero la app."
                                } else if (onOpenOverlay(null, config.copy(previewMs = 5000L))) {
                                    active = true
                                    status = "Vista previa de 5 s sobre esta pantalla."
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Probar barras 5 s") }
                    }
                    Text("Los ajustes se ven al abrir la app elegida ; mientras estás en AppScreen Crop se ocultan, salvo en «Probar barras».", color = Soft, fontSize = 12.sp)
                    OutlinedButton(onClick = { onStopOverlay(); active = false }, modifier = Modifier.fillMaxWidth()) { Text("Quitar recorte visual") }
                }
            }
        }
    }
}
