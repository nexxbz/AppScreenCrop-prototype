package com.appscreencrop

import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Cambia la resolución LÓGICA del sistema (equivale a `wm size` / `wm density`).
 * No toca el panel físico. Android solo lo permite con privilegios elevados
 * (shell por ADB, root o un permiso concedido por ADB); si se deniega, devuelve el error real.
 * Antes de modificar nada guarda los valores originales en disco para poder restaurarlos.
 */
class DisplayController(context: Context) {

    private val ctx = context.applicationContext
    private val prefs = ctx.getSharedPreferences("display_state", Context.MODE_PRIVATE)

    data class Result(val ok: Boolean, val message: String)
    data class Snapshot(
        val physW: Int, val physH: Int, val physDensity: Int,
        val overrideSize: String?, val overrideDensity: Int?, val source: String
    )
    private data class Sh(val code: Int, val out: String, val via: String)

    fun hasAppliedChange(): Boolean = prefs.getBoolean(K_APPLIED, false)

    // ---------- Aplicar ----------
    /** dpi = 0 → densidad automática (proporcional); si no, la densidad indicada (72–800). */
    fun apply(targetW: Int, targetH: Int, dpi: Int = 0): Result = synchronized(LOCK) { applyInner(targetW, targetH, dpi) }

    private fun applyInner(targetW: Int, targetH: Int, dpi: Int): Result {
        val snap = snapshotOriginal()
        val short = minOf(targetW, targetH)
        val long = maxOf(targetW, targetH)
        val physShort = minOf(snap.physW, snap.physH)
        val physLong = maxOf(snap.physW, snap.physH)
        val devMax = deviceMax(ctx)
        val maxShort = maxOf(physShort, devMax.first)
        val maxLong = maxOf(physLong, devMax.second)
        if (short < 320 || short > maxShort || long > maxLong) {
            return Result(false, "Tamaño fuera de rango (${targetW}×${targetH}). Máximo de este teléfono: ${maxLong}×${maxShort}. No se cambió nada.")
        }
        // wm size se expresa en la orientación natural del dispositivo.
        val naturalPortrait = snap.physH >= snap.physW
        val fw = if (naturalPortrait) short else long
        val fh = if (naturalPortrait) long else short
        val newDensity = if (dpi > 0) dpi.coerceIn(72, 800)
            else (snap.physDensity.toFloat() * short / physShort).roundToInt().coerceAtLeast(72)
        val sizeStr = "${fw}x${fh}"

        val s = run("wm size $sizeStr")
        if (readCurrent().overrideSize != sizeStr) {
            restoreFrom(snap)
            return Result(
                false,
                "Android no permitió cambiar la resolución del sistema: la app no tiene permiso elevado " +
                    "(Shizuku, ADB o root). No se modificó nada. Detalle: ${s.out.take(140).ifBlank { "sin detalle" }}. " +
                    "Da el permiso una sola vez desde un PC: adb shell pm grant com.appscreencrop android.permission.WRITE_SECURE_SETTINGS (o usa root), o usa las barras negras."
            )
        }
        run("wm density $newDensity")
        if (readCurrent().overrideDensity != newDensity) {
            restoreFrom(snap)
            return Result(false, "No se pudo ajustar la densidad; se revirtió el cambio y todo quedó como estaba.")
        }
        prefs.edit().putBoolean(K_APPLIED, true).commit()
        return Result(
            true,
            "Resolución lógica del sistema cambiada a $sizeStr (densidad $newDensity, vía ${s.via}). " +
                "Es un cambio de la presentación del sistema, no del panel físico (${snap.physW}×${snap.physH})."
        )
    }

    // ---------- Restaurar ----------
    fun restore(): Result = synchronized(LOCK) { restoreInner() }

    private fun restoreInner(): Result {
        val snap = loadSnapshot()
        if (!hasAppliedChange() || snap == null) {
            return Result(true, "La resolución ya está restaurada: no hay cambios pendientes de esta app.")
        }
        return if (restoreFrom(snap)) {
            prefs.edit().clear().commit()
            Result(
                true,
                "Resolución original restaurada: ${snap.overrideSize ?: "${snap.physW}x${snap.physH} (física)"}, " +
                    "densidad ${snap.overrideDensity ?: snap.physDensity}."
            )
        } else {
            Result(
                false,
                "No se pudo restaurar desde la app (sin permiso elevado). Los valores originales siguen guardados. " +
                    "Desde un PC: adb shell wm size reset  y  adb shell wm density reset"
            )
        }
    }

    private fun restoreFrom(snap: Snapshot): Boolean {
        run(if (snap.overrideSize != null) "wm size ${snap.overrideSize}" else "wm size reset")
        run(if (snap.overrideDensity != null) "wm density ${snap.overrideDensity}" else "wm density reset")
        val now = readCurrent()
        return now.overrideSize == snap.overrideSize && now.overrideDensity == snap.overrideDensity
    }

    // ---------- Valores originales ----------
    private fun snapshotOriginal(): Snapshot {
        // Si ya hay un cambio aplicado por la app, los originales ya están guardados: no se sobrescriben.
        if (hasAppliedChange()) loadSnapshot()?.let { return it }
        val s = readCurrent()
        prefs.edit()
            .putInt(K_PW, s.physW).putInt(K_PH, s.physH).putInt(K_PD, s.physDensity)
            .putString(K_OS, s.overrideSize).putInt(K_OD, s.overrideDensity ?: -1)
            .putString(K_SRC, s.source)
            .commit()
        return s
    }

    private fun loadSnapshot(): Snapshot? {
        if (!prefs.contains(K_PW)) return null
        val os = prefs.getString(K_OS, null)?.takeIf { it.matches(Regex("\\d+x\\d+")) }
        val od = prefs.getInt(K_OD, -1).takeIf { it > 0 }
        return Snapshot(prefs.getInt(K_PW, 0), prefs.getInt(K_PH, 0), prefs.getInt(K_PD, 0), os, od,
            prefs.getString(K_SRC, "wm") ?: "wm")
    }

    @Suppress("DEPRECATION")
    private fun readCurrent(): Snapshot {
        val size = run("wm size").out
        val dens = run("wm density").out
        val phys = Regex("Physical size:\\s*(\\d+)x(\\d+)").find(size)
        val ovr = Regex("Override size:\\s*(\\d+x\\d+)").find(size)
        val pd = Regex("Physical density:\\s*(\\d+)").find(dens)
        val od = Regex("Override density:\\s*(\\d+)").find(dens)
        if (phys != null && pd != null) {
            return Snapshot(
                phys.groupValues[1].toInt(), phys.groupValues[2].toInt(), pd.groupValues[1].toInt(),
                ovr?.groupValues?.get(1), od?.groupValues?.get(1)?.toInt(), "wm"
            )
        }
        // Sin lectura por wm: se usan las métricas de pantalla (informativo).
        val dm = DisplayMetrics()
        (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        return Snapshot(minOf(dm.widthPixels, dm.heightPixels), maxOf(dm.widthPixels, dm.heightPixels),
            dm.densityDpi, null, null, "display")
    }

    // ---------- Shell ----------
    private fun exec(vararg argv: String): Sh = try {
        val p = ProcessBuilder(*argv).redirectErrorStream(true).start()
        if (!p.waitFor(8, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            Sh(-1, "tiempo de espera agotado", argv[0])
        } else {
            Sh(p.exitValue(), p.inputStream.bufferedReader().readText().trim(), argv[0])
        }
    } catch (e: Exception) {
        Sh(-1, e.message ?: "error", argv[0])
    }

    private fun run(cmd: String): Sh {
        val z = Elevated.exec(cmd)
        if (z != null && z.first == 0 && !z.second.contains("Exception")) return Sh(z.first, z.second, "Shizuku")
        val a = exec("sh", "-c", cmd)
        if (a.code == 0 && !a.out.contains("Exception")) return a.copy(via = "shell directo")
        val b = exec("su", "-c", cmd)
        if (b.code == 0 && !b.out.contains("Exception")) return b.copy(via = "root")
        return Sh(a.code, a.out.ifBlank { b.out }, "ninguna")
    }

    companion object {
        /** Resolución física máxima de la pantalla de ESTE teléfono: (lado corto, lado largo). */
        @Suppress("DEPRECATION")
        fun deviceMax(context: Context): Pair<Int, Int> {
            val d = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
            var s = 0
            var l = 0
            try {
                for (m in d.supportedModes) {
                    val ms = minOf(m.physicalWidth, m.physicalHeight)
                    val ml = maxOf(m.physicalWidth, m.physicalHeight)
                    if (ms.toLong() * ml > s.toLong() * l) { s = ms; l = ml }
                }
            } catch (e: Exception) {
            }
            if (s == 0) {
                val dm = DisplayMetrics()
                d.getRealMetrics(dm)
                s = minOf(dm.widthPixels, dm.heightPixels)
                l = maxOf(dm.widthPixels, dm.heightPixels)
            }
            return s to l
        }

        private val LOCK = Any()
        private const val K_APPLIED = "applied"
        private const val K_PW = "phys_w"
        private const val K_PH = "phys_h"
        private const val K_PD = "phys_d"
        private const val K_OS = "ovr_size"
        private const val K_OD = "ovr_d"
        private const val K_SRC = "source"
    }
}
