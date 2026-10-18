// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assume.assumeTrue
import java.io.File

/**
 * Gate for tests that serialize through the host TinyDNG native library.
 * The lib is built by :app:buildDngNativeHost, which is skipped on machines
 * without a C toolchain; there the rawlens.dnglib property is unset and
 * these tests skip instead of failing on the missing binary. An explicit
 * -Drawlens.dnglib=<path> is still honored verbatim, so a typo there fails
 * loudly at System.load rather than silently skipping.
 */
object HostDngLib {
    fun assumeAvailable() {
        assumeTrue(
            "host TinyDNG lib missing (needs a C compiler: :app:buildDngNativeHost)",
            System.getProperty("rawlens.dnglib")?.let { File(it).isFile } == true
        )
    }
}
