package org.buildmosaic.analysis

/** Severity of a configurable Mosaic policy rule, independent of uncertainty enforcement. */
enum class MosaicRuleSeverity {
  ERROR,
  WARNING,
  OFF,
}

enum class MosaicRuleClassification {
  CORRECTNESS,
  POLICY,
}

/** The authoritative public rule registry. IDs are also Kotlin suppression names. */
enum class MosaicRule(
  val title: String,
  val defaultSeverity: MosaicRuleSeverity,
  val configurable: Boolean,
  val suppressible: Boolean,
  val classification: MosaicRuleClassification,
) {
  MOSAIC_CYCLIC_TILE_DEPENDENCY(
    "Cyclic Tile dependency",
    MosaicRuleSeverity.ERROR,
    false,
    false,
    MosaicRuleClassification.CORRECTNESS,
  ),
  MOSAIC_RECURSIVE_TILE(
    "Recursive Tile dependency",
    MosaicRuleSeverity.ERROR,
    true,
    true,
    MosaicRuleClassification.POLICY,
  ),
  MOSAIC_RECURSIVE_MULTITILE(
    "Recursive MultiTile dependency",
    MosaicRuleSeverity.ERROR,
    true,
    true,
    MosaicRuleClassification.POLICY,
  ),
  ;

  val id: String get() = name

  companion object {
    fun configurable(id: String): MosaicRule {
      val rule = entries.firstOrNull { it.id == id }
      require(rule?.configurable == true) {
        "Mosaic rule '$id' is unknown or non-configurable. Valid configurable IDs: " +
          entries.filter { it.configurable }.joinToString { it.id }
      }
      return rule
    }
  }
}

/** Only participating Tile declarations own recursive suppressions; never a caller/root. */
data class MosaicSuppression(val ruleId: String, val site: SourceLocation)

/** Identity references only; Canvas availability remains a separate contract. */
sealed interface MosaicProvenance {
  data object Current : MosaicProvenance

  data class Established(val id: String) : MosaicProvenance

  data object Unknown : MosaicProvenance
}
