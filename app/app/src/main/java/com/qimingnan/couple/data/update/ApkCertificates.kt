package com.qimingnan.couple.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File
import java.security.MessageDigest

/** Reads signing certificates (SHA-256 of the DER) of the installed app and of a downloaded APK. */
object ApkCertificates {

    data class ArchiveInfo(val packageName: String, val versionCode: Long, val certs: Set<String>)

    fun installed(context: Context): Set<String> {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
        return certsOf(info)
    }

    fun archive(context: Context, apk: File): ArchiveInfo? {
        val pm = context.packageManager
        val path = apk.absolutePath
        val info: PackageInfo? = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)
        }
        info ?: return null
        var certs = certsOf(info)
        if (certs.isEmpty() && Build.VERSION.SDK_INT >= 28) {
            // Some API 28-32 builds leave signingInfo null for archives; fall back to the legacy field.
            @Suppress("DEPRECATION")
            certs = pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)?.let(::certsOf).orEmpty()
        }
        val vc = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        return ArchiveInfo(info.packageName, vc, certs)
    }

    private fun certsOf(info: PackageInfo): Set<String> {
        val sigs: Array<Signature>? = if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
            // Current signer(s): with key rotation this is the newest certificate, which is what updates must match.
            info.signingInfo!!.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        return sigs.orEmpty().map { sha256(it.toByteArray()) }.toSet()
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
