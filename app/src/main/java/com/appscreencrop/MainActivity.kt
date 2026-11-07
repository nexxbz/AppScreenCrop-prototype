package com.appscreencrop

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Navy = Color(0xFF101D36)
private val Blue = Color(0xFF287BE6)
data class LaunchableApp(val label: String, val packageName: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Blue, background = Navy, surface = Color(0xFF1B2C49))) {
                Home(loadLaunchableApps()) { pkg -> packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) } }
            }
        }
    }
    @Suppress("DEPRECATION")
    private fun loadLaunchableApps(): List<LaunchableApp> {
        val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { LaunchableApp(it.loadLabel(packageManager).toString(), it.activityInfo.packageName) }
            .filter { it.packageName != this@MainActivity.packageName }
            .distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    }
}

@Composable
private fun Home(apps: List<LaunchableApp>, onLaunch: (String) -> Unit) {
    var selected by remember { mutableStateOf<LaunchableApp?>(null) }
    var automatic by remember { mutableStateOf(true) }
    var ratio by remember { mutableStateOf("16:9") }
    var horizontal by remember { mutableFloatStateOf(0f) }
    var vertical by remember { mutableFloatStateOf(0f) }
    Surface(Modifier.fillMaxSize(), color = Navy) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = Blue, shape = RoundedCornerShape(14.dp)) { Icon(Icons.Default.Tv, null, Modifier.padding(12.dp), tint = Color.White) }
                    Spacer(Modifier.width(12.dp))
                    Column { Text("AppScreen Crop", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White); Text("SMART DISPLAY CONTROL", fontSize = 11.sp, color = Color(0xFF9EB8DC)) }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Vista previa de formato", color = Color.White, fontWeight = FontWeight.SemiBold)
                Box(Modifier.fillMaxWidth().aspectRatio(when (ratio) { "16:10" -> 16f/10f; "4:3" -> 4f/3f; else -> 16f/9f }).background(Color(0xFF080F1C), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Tv, null, tint = Color(0xFF78B2FF), modifier = Modifier.size(42.dp))
                        Text(ratio, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        Text("Vista de demostración", color = Color(0xFF9EB8DC), fontSize = 12.sp)
                    }
                }
                Text("La vista previa es ilustrativa; todavía no muestra otra aplicación en directo.", color = Color(0xFF9EB8DC), fontSize = 12.sp)
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
                Text("Modo de ajuste", color = Color.White, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = automatic, onClick = { automatic = true }, label = { Text("Automático") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = !automatic, onClick = { automatic = false }, label = { Text("Manual") })
                }
                if (automatic) {
                    Text("Formato de pantalla", color = Color(0xFFB9CBE4))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("16:9", "16:10", "4:3").forEach { v -> FilterChip(selected = ratio == v, onClick = { ratio = v }, label = { Text(v) }) }
                    }
                } else {
                    Text("Ajuste horizontal: ${horizontal.toInt()}%", color = Color(0xFFB9CBE4))
                    Slider(value = horizontal, onValueChange = { horizontal = it }, valueRange = 0f..30f)
                    Text("Ajuste vertical: ${vertical.toInt()}%", color = Color(0xFFB9CBE4))
                    Slider(value = vertical, onValueChange = { vertical = it }, valueRange = 0f..30f)
                }
                Button(onClick = { selected?.let { onLaunch(it.packageName) } }, enabled = selected != null, modifier = Modifier.fillMaxWidth()) { Text("Abrir aplicación seleccionada") }
                Text("Prototipo 0.1: selección, apertura y controles visuales. La adaptación real de la pantalla y el tacto aún no están implementados.", color = Color(0xFFFFD58A), fontSize = 12.sp)
                }
            }
        }
    }
}
