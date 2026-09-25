// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// DESKTOP HOST SHIM (sr-vulkan only). Minimal android.content.pm
// ApplicationInfo: only FLAG_DEBUGGABLE, read by the ported SR processor's
// tuning log gate via context.applicationInfo.flags.
package android.content.pm

class ApplicationInfo {
    var flags: Int = 0

    companion object {
        const val FLAG_DEBUGGABLE = 1 shl 1
    }
}
