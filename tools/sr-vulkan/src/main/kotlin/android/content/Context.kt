// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// DESKTOP HOST SHIM (sr-vulkan only). Minimal android.content.Context: only
// the asset-loading surface the ported ML processors use
// (getApplicationContext/getAssets). Construct with the directory that plays
// the APK assets role (the CLI passes the resources root / model dir).
package android.content

import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import java.io.File

open class Context(val assetRoot: File = File(".")) {
    // Property (not a fun) so Kotlin `.applicationContext` resolves; Java
    // callers still use the synthesized getApplicationContext() method.
    open val applicationContext: Context get() = this
    // Desktop behaves as a debuggable build (it is a developer tool), so the
    // ported tuning diagnostics stay on with identical gate lines.
    open val applicationInfo: ApplicationInfo =
        ApplicationInfo().apply { flags = ApplicationInfo.FLAG_DEBUGGABLE }
    // Property (not a fun) so Kotlin `.assets` resolves, mirroring the
    // synthetic property Java's getAssets() provides on Android; Java
    // callers still use the synthesized getAssets() method.
    open val assets: AssetManager get() = AssetManager(assetRoot)
}
