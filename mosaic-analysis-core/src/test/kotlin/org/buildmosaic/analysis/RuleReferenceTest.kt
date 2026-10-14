package org.buildmosaic.analysis

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuleReferenceTest {
  @Test
  fun `rule reference matches registry and examples`() {
    val reference =
      File(System.getProperty("user.dir")).parentFile
        .resolve("website/src/content/docs/reference/analysis-configuration.md").readText()
    for (rule in MosaicRule.entries) {
      val row = reference.lineSequence().first { it.trimStart().startsWith("| `${rule.id}`") }
      val cells = row.split('|').drop(1).dropLast(1).map { it.trim().removeSurrounding("`") }
      assertEquals(
        listOf(
          rule.id,
          rule.title,
          rule.defaultSeverity.name,
          if (rule.configurable) "Yes" else "No",
          if (rule.suppressible) "Yes" else "No",
        ),
        cells,
      )
      if (rule.configurable) assertTrue(reference.contains("severity(\"${rule.id}\""))
      if (rule.suppressible) assertTrue(reference.contains("@Suppress(\"${rule.id}\")"))
    }
  }
}
