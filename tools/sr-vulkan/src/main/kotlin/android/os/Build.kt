// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// DESKTOP HOST SHIM (sr-vulkan only). Mirrors the android.os.Build fields
// used by the ported DNG writers so those lines compile byte-identical. The
// CLI sets MANUFACTURER/MODEL from the SOURCE DNG tags before writing, which
// is more truthful than the host hardware; the writers' null-tolerant ascii()
// keeps "unknown" behavior identical when unset.
package android.os

object Build {
    @Volatile var MANUFACTURER: String = "Unknown"
    @Volatile var MODEL: String = "Unknown"

    object VERSION {
        const val SDK_INT = 35
    }

    object VERSION_CODES {
        const val UPSIDE_DOWN_CAKE = 34
    }
}
