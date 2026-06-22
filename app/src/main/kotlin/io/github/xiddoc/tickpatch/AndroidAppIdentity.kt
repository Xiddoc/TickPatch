/*
 * Fills a Rosetta [AppIdentity] from Android's PackageManager.
 *
 * rosetta-xposed's :xposed module stays Android-free on purpose (it must not
 * compile against android.jar), so the irreducible PackageManager read — the
 * SDK-version branch that pulls versionCode / versionName and the raw signing
 * certificate bytes — lives here in the consuming module. The hashing +
 * AppIdentity assembly is delegated to the shipping, tested
 * `io.github.xiddoc.rosetta.android.AndroidIdentities` (the :android-runtime
 * module).
 *
 *   - `versionCode` is the O(1) map selection key (longVersionCode on API 28+).
 *   - the cert byte arrays become `signerSha256s`; the map's `signer_sha256`
 *     guard (TickTick's real signing cert) is matched against ANY of them, so a
 *     repackaged build that shares the version_code fails closed.
 *
 * This is a near-verbatim copy of the rosetta-xposed example module's
 * AndroidAppIdentity — the canonical way to wire identity into a real module.
 */
package io.github.xiddoc.tickpatch

import android.content.pm.PackageManager
import android.os.Build
import io.github.xiddoc.rosetta.android.AndroidIdentities
import io.github.xiddoc.rosetta.xposed.AppIdentity

internal object AndroidAppIdentity {
    fun of(
        pm: PackageManager,
        packageName: String,
    ): AppIdentity {
        val versionCode: Long
        val versionName: String?
        val certs: List<ByteArray>

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            versionCode = info.longVersionCode
            versionName = info.versionName
            certs =
                info.signingInfo
                    ?.apkContentsSigners
                    ?.map { it.toByteArray() }
                    .orEmpty()
        } else {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            // Pre-28 has no longVersionCode: the legacy int `versionCode` IS the
            // low 32 bits (major == 0). Compose it through the helper so a
            // high-bit versionCode widens UNSIGNED rather than sign-extending.
            @Suppress("DEPRECATION")
            versionCode = AndroidIdentities.longVersionCode(info.versionCode, 0)
            versionName = info.versionName
            @Suppress("DEPRECATION")
            certs =
                info.signatures
                    ?.map { it.toByteArray() }
                    .orEmpty()
        }

        return AndroidIdentities.build(
            packageName = packageName,
            versionCode = versionCode,
            versionName = versionName,
            signerCertsDer = certs,
        )
    }
}
