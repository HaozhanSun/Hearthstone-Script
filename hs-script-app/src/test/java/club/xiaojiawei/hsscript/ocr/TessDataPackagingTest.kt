package club.xiaojiawei.hsscript.ocr

import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TessDataPackagingTest {

    @Test
    fun assemblyIncludesAllTesseractLanguageDataInRuntimeResources() {
        val assembly = locateAssembly()
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(assembly.toFile())
        val fileSets = document.getElementsByTagNameNS("*", "fileSet")
        val tessDataSet = (0 until fileSets.length)
            .asSequence()
            .map { fileSets.item(it) }
            .firstOrNull { node ->
                val directories = node.childNodes.asSequence()
                    .mapNotNull { it.textContent?.trim() }
                directories.any { it.contains("src/main/resources/resources/tessdata") }
            }

        assertTrue(tessDataSet != null, "assembly must copy source tessdata")
        val outputDirectory = tessDataSet!!.childNodes.asSequence()
            .firstOrNull { it.localName == "outputDirectory" }
            ?.textContent
            ?.trim()
        assertEquals("resources/tessdata/", outputDirectory)

        val sourceDirectory = (assembly.parent ?: Path.of(".")).resolve("src/main/resources/resources/tessdata")
        val expected = setOf("chi_sim.traineddata", "chi_sim_vert.traineddata", "eng.traineddata")
        val actual = Files.list(sourceDirectory).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .map { it.fileName.toString() }
                .filter { it.endsWith(".traineddata") }
                .toList()
                .toSet()
        }
        assertTrue(actual.containsAll(expected), "missing tessdata=${expected - actual}")
    }

    private fun locateAssembly(): Path {
        val candidates = listOf(
            Path.of("hs-script-app", "assembly.xml"),
            Path.of("assembly.xml"),
        )
        return candidates.firstOrNull(Files::isRegularFile)
            ?: error("could not locate hs-script-app/assembly.xml from ${Path.of("").toAbsolutePath()}")
    }
}

private fun org.w3c.dom.NodeList.asSequence(): Sequence<org.w3c.dom.Node> =
    (0 until length).asSequence().map { item(it) }
