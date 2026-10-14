// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Pins the no-backup contract: uninstall must reset. Follows the repo's
 * module-dir-relative File convention (see HdrRealBracketValidationTest).
 */
class BackupRulesTest {
    private fun moduleFile(path: String): File {
        val file = File(path)
        assertTrue("missing $path (run from :app)", file.isFile)
        return file
    }

    @Test fun manifestOptsOutOfBackupAndRestore() {
        val manifest = moduleFile("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/backup_rules\""))
    }

    @Test fun extractionRulesExcludeEveryDomainEverywhere() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
            moduleFile("src/main/res/xml/backup_rules.xml")
        )
        assertEquals(0, doc.getElementsByTagName("include").length)
        val domains = setOf("root", "file", "database", "sharedpref", "external")
        for (section in listOf("cloud-backup", "device-transfer")) {
            val nodes = doc.getElementsByTagName(section)
            assertEquals("missing <$section>", 1, nodes.length)
            val excluded = mutableSetOf<String>()
            val children = nodes.item(0).childNodes
            for (i in 0 until children.length) {
                val node = children.item(i)
                if (node.nodeName == "exclude") {
                    val domain = node.attributes.getNamedItem("domain").nodeValue
                    val path = node.attributes.getNamedItem("path").nodeValue
                    assertEquals("exclude $domain must cover all", ".", path)
                    excluded += domain
                }
            }
            assertEquals("incomplete <$section> excludes", domains, excluded)
        }
    }
}
