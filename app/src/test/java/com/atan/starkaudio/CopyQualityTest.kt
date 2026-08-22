package com.atan.starkaudio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class CopyQualityTest {
    @Test fun visibleCopyHasNoBannedGeneratedStyleMarkers() {
        val roots = listOf(File("src/main/res/values/strings.xml"), File("src/main/java/com/atan/starkaudio/ui"))
        val files = roots.flatMap { if (it.isDirectory) it.walkTopDown().filter(File::isFile).toList() else listOf(it) }
        val text = files.joinToString("\n") { it.readText(Charsets.UTF_8) }
        assertFalse("Visible copy contains an em dash.", text.contains('\u2014'))
        assertFalse("Visible copy contains replacement characters.", text.contains('\uFFFD'))
        assertFalse("Visible copy contains known malformed UTF-8 sequences.", Regex("(\\u00e2\\u20ac|\\u00ef\\u00bb\\u00bf)", RegexOption.IGNORE_CASE).containsMatchIn(text))
        assertFalse("Visible copy contains mojibake.", text.contains("â€") || text.contains("Ã") || text.contains('\uFEFF'))
        assertFalse("Visible copy contains placeholder language.", Regex("(?i)\\b(lorem ipsum|placeholder copy|todo copy|coming soon)\\b").containsMatchIn(text))
        assertFalse("Visible copy contains decorative emoji.", Regex("[✨🚀🤖🎉🔥💡✅❌]").containsMatchIn(text))
    }

    @Test fun composeDoesNotEmbedSimpleStaticTextLiterals() {
        val source = File("src/main/java/com/atan/starkaudio/ui").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText(Charsets.UTF_8) }
        val staticText = Regex("Text\\(\\\"([^\\\"$]*)\\\"\\)").findAll(source).map { it.groupValues[1] }.filter(String::isNotBlank).toList()
        assertTrue("Move static Compose copy to string resources: $staticText", staticText.isEmpty())
    }

    @Test fun stringResourcesAreWellFormedAndMetadataSpacingIsDeliberate() {
        val resource = File("src/main/res/values/strings.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(resource)
        val strings = document.getElementsByTagName("string")
        val names = (0 until strings.length).map { strings.item(it).attributes.getNamedItem("name").nodeValue }
        assertTrue("String resource names must be unique.", names.distinct().size == names.size)
        val text = resource.readText(Charsets.UTF_8)
        assertFalse("Use XML entities instead of smart quotation characters.", text.contains('\u201c') || text.contains('\u201d'))
        assertFalse("String resources must not contain an invalid format marker.", Regex("%(?!%|(?:\\d+\\$)?[ds])").containsMatchIn(text))
        assertFalse("Use a single deliberate separator spacing.", text.contains("  &#183;  ") || text.contains("  •  "))
    }
}
