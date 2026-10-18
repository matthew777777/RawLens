// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

/**
 * First-install ordering: welcome content first, then one full-screen
 * permissions page (camera grant + photo-folder grant + the keep-up-to-date
 * ask), then lens discovery inside [MainActivity].
 *
 * The update ask lives on [Step.PERMISSIONS] only — never over the welcome
 * content and never inside lens discovery — so a fresh install never shows
 * two layers at once. Pure and host-testable.
 */
object WelcomeFlow {

    enum class Step {
        WELCOME,
        PERMISSIONS,
        LENS_DISCOVERY,
    }

    /** Fixed first-run order. */
    fun steps(): List<Step> =
        listOf(Step.WELCOME, Step.PERMISSIONS, Step.LENS_DISCOVERY)

    /**
     * True only on the permissions page of updater builds (GitHub builds;
     * F-Droid leaves updates to the store). In particular this is always
     * false on [Step.WELCOME], which is what used to be covered by the
     * consent dialog.
     */
    fun showsUpdateAsk(step: Step, updaterEnabled: Boolean): Boolean =
        step == Step.PERMISSIONS && updaterEnabled

    /**
     * The keep-up-to-date ask also waits past lens discovery: while the
     * lens setup is still incomplete, discovery owns the screen and the
     * consent dialog must not layer over it. Covered by unit test.
     */
    fun shouldDeferUpdateConsent(lensSetupComplete: Boolean): Boolean =
        !lensSetupComplete
}
