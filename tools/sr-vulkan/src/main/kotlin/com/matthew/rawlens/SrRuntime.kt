// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/** DESKTOP-OWNED public runtime facts for the CLIs (wraps internals). */
object SrRuntime {
    const val VERSION = "sr-vulkan/0.1-cpu"
    val workerCount: Int get() = RawSrWorkers.count
}
