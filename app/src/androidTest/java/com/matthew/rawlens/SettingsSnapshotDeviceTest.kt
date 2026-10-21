// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Settings UI parity snapshot for the MainActivity decomposition. Opens the
 * real settings dialog on every tab and records each control in order (class,
 * text, checked, range value) through the accessibility tree, the same view
 * of the window a user's screen reader gets. Writes one file per run to the
 * app's external files dir (`settings-snapshot-<label>.txt`); two runs on two
 * builds must match line for line. Opt-in: `-e rawlensSettingsSnapshot <label>`.
 */
class SettingsSnapshotDeviceTest {
    @Test
    fun snapshotEveryTab() {
        val args = InstrumentationRegistry.getArguments()
        val label = args.getString("rawlensSettingsSnapshot")
        assumeTrue("Pass -e rawlensSettingsSnapshot <label>", label != null)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} android.permission.CAMERA").close()
        context.getSharedPreferences("rawlens_settings", Context.MODE_PRIVATE)
            .edit().putBoolean("lens_setup_complete", true).commit()
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        SystemClock.sleep(3000) // camera open settles; status lines in tabs read live state
        val lines = mutableListOf<String>()
        try {
            for (tab in 0 until TAB_COUNT) {
                instrumentation.runOnMainSync { activity.showSettings(tab) }
                instrumentation.waitForIdleSync()
                SystemClock.sleep(800)
                val root = automation.rootInActiveWindow
                assertTrue("tab $tab: no active window", root != null)
                lines += "== tab $tab"
                walk(root!!, lines)
                automation.executeShellCommand("input keyevent KEYCODE_BACK").close()
                instrumentation.waitForIdleSync()
                SystemClock.sleep(400)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
        val out = File(context.getExternalFilesDir(null), "settings-snapshot-$label.txt")
        out.writeText(lines.joinToString("\n") + "\n")
        Log.i("RawLensSettingsSnapshot", "wrote ${lines.size} lines to $out")
        assertTrue("snapshot too small", lines.size > TAB_COUNT * 3)
    }

    private fun walk(node: AccessibilityNodeInfo, out: MutableList<String>) {
        val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
        val text = node.text?.toString()?.replace('\n', '⏎')
        if (text != null || node.isCheckable || node.rangeInfo != null) {
            val checked = if (node.isCheckable) " checked=${node.isChecked}" else ""
            val range = node.rangeInfo?.let { " range=${it.current}/${it.max}" } ?: ""
            val enabled = if (!node.isEnabled) " disabled" else ""
            out += "$cls \"${text ?: ""}\"$checked$range$enabled"
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, out) }
    }

    private companion object {
        /** General, Photo, Video, Exposure, Burst, Denoise, Lenses, Debug, About. */
        const val TAB_COUNT = 9
    }
}
