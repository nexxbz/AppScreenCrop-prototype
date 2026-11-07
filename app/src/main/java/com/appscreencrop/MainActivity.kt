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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Blue, background = Navy, surface = Color(0xFF1B2C49))) {
                Home(
                    apps = loadLaunchableApps(),
                    pendingChange = pending,
                    onOpenOverlay = { app, cfg -> openWithCrop(app, cfg) },
                    onUpdateOverlay = { cfg -> startOverlay(cfg) },
                    onStopOverlay = { stopService(Intent(this, CropOverlayService::class.java)) },
                    onApplySystem = { w, h, done -> runAsync({ displayCtl.apply(w, h) }, done) },
                    onRestore = { done -> runAsync({ displayCtl.restore() }, done) },
                    onLaunch = { app -> launchApp(app) }
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
        runCatching { startForegroundService(i) }
    }

    private fun launchApp(app: LaunchableApp) {
        packageManager.getLaunchIntentForPackage(app.packageName)?.let { startActivity(it) }
    }

    private fun openWithCrop(app: LaunchableApp?, cfg: CropConfig): Boolean {
        if (!Settings.canDrawOverlays(this)) {
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
    onOpenOverlay: (LaunchableApp?, CropConfig) -> Boolean,
    onUpdateOverlay: (CropConfig) -> Unit,
    onStopOverlay: () -> Unit,
    onApplySystem: (Int, Int, (DisplayController.Result) -> Unit) -> Unit,
    onRestore: ((DisplayController.Result) -> Unit) -> Unit,
    onLaunch: (LaunchableApp) -> Unit
) {
    var selected by remember { mutableStateOf<LaunchableApp?>(null) }
    var automatic by remember { mutableStateOf(true) }
    var ratio by remember { mutableStateOf("16:10") }
    var orientation by remember { mutableIntStateOf(0) }
    var scalePct by remember { mutableFloatStateOf(100f) }
    var method by remember { mutableIntStateOf(0) }
    var active by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var confirmSeconds by remember { mutableStateOf<Int?>(null) }
    var status by remember {
        mutableStateOf(if (pendingChange) "La app dejó una resolución modificada. Pulsa «Restaurar resolución original»." else "")
    }

    val scale = if (automatic) 1f else scalePct / 100f
    val config = CropConfig(orientation, ratioOf(ratio), scale.coerceAtMost(1f), selected?.packageName)
    val base = baseSize(ratioOf(ratio), scale)
    val target = if (orientation == 2) base.second to base.first else base

    val doRestore: () -> Unit = {
        busy = true
        onRestore { r -> busy = false; status = r.message }
    }

    val applyNow: () -> Unit = {
        val app = selected
        if (method == 0) {
            if (app == null) {
                status = "Selecciona la app (por ejemplo Xuper TV) para que el recorte se aplique solo en ella."
            } else if (onOpenOverlay(app, config)) {
                active = true
                status = "Recorte activo solo en ${app.label}: ${target.first} × ${target.second}. Se muestra únicamente cuando esa app está en primer plano."
            }
        } else {
            onStopOverlay(); active = false
            busy = true; status = "Aplicando…"
            onApplySystem(target.first, target.second) { r ->
                busy = false; status = r.message
                if (r.ok) confirmSeconds = 15
            }
        }
    }

    LaunchedEffect(automatic, ratio, orientation, scalePct, selected) {
        if (active && method == 0) onUpdateOverlay(config)
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
                                Text("Formato $ratio", color = Color(0xFFBDBDBD), fontSize = 14.sp)
                            }
                        }
                        Text(
                            if (method == 0) "Se aplica solo a: ${selected?.label ?: "— elige una app abajo"}" else "Afecta a TODO el teléfono, no solo a una app",
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
                    FilterChip(selected = method == 0, onClick = { method = 0 }, label = { Text("Solo en la app elegida (recomendado)") })
                    FilterChip(selected = method == 1, onClick = { method = 1 }, label = { Text("Todo el sistema (avanzado)") })
                    Text(
                        if (method == 0) "Superpone barras negras únicamente sobre la app elegida y solo mientras está abierta; en el resto del teléfono todo queda normal. Pide dos permisos: «Mostrar sobre otras apps» y «Acceso de uso». No cambia la resolución del sistema."
                        else "Esto NO es por app: cambia la resolución LÓGICA de todo el sistema y ajusta la densidad en proporción. No modifica el panel físico. Requiere permiso elevado (ADB/root); si Android lo deniega, se te dirá y no se cambia nada. El resultado de la proporción (estirado o con bordes) depende del fabricante.",
                        color = Soft, fontSize = 12.sp
                    )

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
                    if (method == 1) Text("En «Resolución del sistema», el tamaño se aplica en la orientación natural del dispositivo y gira con él.", color = Soft, fontSize = 12.sp)

                    Text("Formato (se conserva la proporción)", color = Color(0xFFB9CBE4))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("16:10", "16:9", "4:3").forEach { v -> FilterChip(selected = ratio == v, onClick = { ratio = v }, label = { Text(v) }) }
                    }
                    Text("Principal: 16:10 = 1280×800.", color = Soft, fontSize = 12.sp)

                    if (!automatic) {
                        Text("Escala del área: ${scalePct.toInt()}%", color = Color(0xFFB9CBE4))
                        Slider(value = scalePct, onValueChange = { scalePct = it }, valueRange = 50f..150f)
                        if (method == 0) Text("En recorte visual, más de 100% equivale a 100% (el máximo que cabe en pantalla).", color = Soft, fontSize = 12.sp)
                    }

                    Text("Los ajustes se ven al abrir la app elegida (mientras estás en AppScreen Crop las barras se ocultan).", color = Soft, fontSize = 12.sp)
                    OutlinedButton(onClick = { onStopOverlay(); active = false }, modifier = Modifier.fillMaxWidth()) { Text("Quitar recorte visual") }
                }
            }
        }
    }
}
