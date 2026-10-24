package com.qimingnan.couple.data.keepalive

import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku

enum class ShizukuAccess { NOT_RUNNING, UNSUPPORTED, NOT_AUTHORIZED, AUTHORIZED }

data class ShellResult(val command: String, val exitCode: Int, val output: String)

/** Runs shell commands as the shell user through Shizuku (public AIDL; Shizuku.newProcess is private in API 13). */
object ShizukuRunner {
    const val REQUEST_CODE = 0x4b41 // "KA"

    fun access(): ShizukuAccess = try {
        when {
            !Shizuku.pingBinder() -> ShizukuAccess.NOT_RUNNING
            Shizuku.isPreV11() -> ShizukuAccess.UNSUPPORTED
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> ShizukuAccess.AUTHORIZED
            else -> ShizukuAccess.NOT_AUTHORIZED
        }
    } catch (e: Throwable) {
        ShizukuAccess.NOT_RUNNING
    }

    fun requestPermission() {
        if (access() == ShizukuAccess.NOT_AUTHORIZED) runCatching { Shizuku.requestPermission(REQUEST_CODE) }
    }

    /** Blocking; call off the main thread. */
    fun run(command: String): ShellResult {
        val service = IShizukuService.Stub.asInterface(Shizuku.getBinder()) ?: error("Shizuku 未连接")
        val proc = service.newProcess(arrayOf("sh", "-c", command), null, null) ?: error("Shizuku 无法启动进程")
        try {
            runCatching { ParcelFileDescriptor.AutoCloseOutputStream(proc.outputStream).close() }
            val out = ParcelFileDescriptor.AutoCloseInputStream(proc.inputStream).use { it.readBytes().toString(Charsets.UTF_8) }
            val err = ParcelFileDescriptor.AutoCloseInputStream(proc.errorStream).use { it.readBytes().toString(Charsets.UTF_8) }
            val code = proc.waitFor()
            return ShellResult(command, code, (out + err).trim().take(300))
        } finally {
            runCatching { proc.destroy() }
        }
    }
}
