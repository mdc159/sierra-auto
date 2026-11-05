package com.mdc159.sierra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads the network security config from the source tree and checks the policy the lane
 * asks for: plain http only for *.ts.net, localhost, 127.0.0.1 and 10.0.2.2; https
 * everywhere else. The manifest must point at it, or Android ignores the file.
 */
class NetworkSecurityConfigTest {

    private fun source(path: String): File {
        // Gradle runs unit tests with the module directory as the working directory.
        val candidates = listOf(File(path), File("app", path))
        return candidates.firstOrNull { it.exists() } ?: error("missing $path (cwd ${File(".").absolutePath})")
    }

    private fun elements(parent: Element, tag: String): List<Element> {
        val nodes = parent.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    @Test
    fun cleartextOnlyForTailscaleAndLocalHosts() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(source("src/main/res/xml/network_security_config.xml"))
        val root = doc.documentElement
        assertEquals("network-security-config", root.tagName)

        val base = elements(root, "base-config").single()
        assertEquals("false", base.getAttribute("cleartextTrafficPermitted"))

        val cleartext = elements(root, "domain-config").filter { it.getAttribute("cleartextTrafficPermitted") == "true" }
        val domains = cleartext.flatMap { elements(it, "domain") }.associate {
            it.textContent.trim() to (it.getAttribute("includeSubdomains") == "true")
        }
        assertEquals(setOf("ts.net", "localhost", "127.0.0.1", "10.0.2.2"), domains.keys)
        assertTrue("*.ts.net must be covered", domains.getValue("ts.net"))
        assertTrue(listOf("localhost", "127.0.0.1", "10.0.2.2").none { domains.getValue(it) })
    }

    @Test
    fun manifestReferencesTheConfig() {
        val manifest = source("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:networkSecurityConfig=\"@xml/network_security_config\""))
        assertTrue(manifest.contains(".IdeaSettingsActivity"))
    }
}
