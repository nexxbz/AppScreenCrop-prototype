package com.appscreencrop

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/** Acceso elevado (shell) mediante Shizuku. Solo funciona si el usuario instaló y activó Shizuku y concedió el permiso. */
object Elevated {
    /** 0 = Shizuku no activo, 1 = activo pero sin permiso, 2 = listo. */
    fun state(): Int = try {
        if (!Shizuku.pingBinder() || Shizuku.isPreV11()) 0
        else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) 2
        else 1
    } catch (e: Throwable) {
        0
    }

    fun requestPermission() {
        try {
            Shizuku.requestPermission(1001)
        } catch (e: Throwable) {
        }
    }

    /** Ejecuta un comando con el shell de Shizuku. Devuelve (código, salida) o null si no está disponible. */
    fun exec(cmd: String): Pair<Int, String>? {
        if (state() != 2) return null
        return try {
            // newProcess es privado en Shizuku API 13.1+, por eso se llama por reflexión.
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
            )
            m.isAccessible = true
            val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            val code = p.waitFor()
            code to (out + err).trim()
        } catch (e: Throwable) {
            null
        }
    }
}
