package org.buildmosaic.gradle

import org.buildmosaic.analysis.AnalysisReport
import org.buildmosaic.analysis.Finding
import org.buildmosaic.analysis.RootSelection

internal object MosaicVerificationReport {
  fun library(enforcement: MosaicAnalysisEnforcement): String =
    """
      |Mosaic analysis
      |Role: LIBRARY
      |Enforcement: $enforcement
      |Roots selected: none; application verification not requested
      |Result: EXPORT_ONLY; local summary is complete and valid
      |
    """.trimMargin().trim()

  fun application(
    report: AnalysisReport,
    role: MosaicAnalysisRole,
    enforcement: MosaicAnalysisEnforcement,
  ): String {
    val result =
      when {
        !report.policyDecision.passed -> "FAILED"
        report.policyDecision.warnings.isNotEmpty() ->
          if (report.roots.any { it.status == org.buildmosaic.analysis.RootStatus.UNVERIFIED }) {
            "PASSED WITH WARNINGS; selected roots remain UNVERIFIED"
          } else {
            "PASSED WITH WARNINGS"
          }
        else -> "FULLY VERIFIED"
      }
    return buildString {
      appendLine("Mosaic analysis: ${report.configurationStatus}")
      appendLine("Role: $role")
      appendLine("Enforcement: $enforcement")
      val selection = report.roots.firstOrNull()?.root?.selection ?: RootSelection.EXPLICIT
      appendLine("Root selection: $selection")
      appendLine("Roots selected: ${report.roots.joinToString { it.root.id }}")
      appendLine("Result: $result")
      report.roots.forEach { root ->
        appendLine(
          "Root ${root.root.id}: ${root.status}; receiver ${root.root.receiverType ?: "none"}; " +
            "contracts ${root.specializedContracts.joinToString()}",
        )
      }
      report.findings.forEach { finding -> appendFinding(finding) }
    }
  }

  fun failure(
    role: MosaicAnalysisRole,
    enforcement: MosaicAnalysisEnforcement,
    roots: List<String>,
    category: String,
    reason: String?,
  ): String =
    """
      |Mosaic analysis
      |Role: $role
      |Enforcement: $enforcement
      |Roots selected: ${roots.joinToString().ifEmpty { "none" }}
      |Result: FAILED
      |$category: $reason
      |
    """.trimMargin().trim()

  private fun StringBuilder.appendFinding(finding: Finding) {
    finding.rule?.let { rule ->
      val status = if (finding.suppressedAt.isNotEmpty()) "SUPPRESSED" else finding.severity.toString()
      appendLine("${rule.id}: ${rule.title}: $status")
      appendLine("  ${finding.reason}")
      appendLine("  ${finding.dependencyPath.joinToString(" -> ") { it.label }}")
      finding.dependencyPath.forEach { node ->
        appendLine("  ${node.label}    ${node.site.path}:${node.site.line}")
      }
      finding.suppressedAt.forEach { appendLine("  Suppressed at ${it.path}:${it.line}") }
      return
    }
    appendLine(
      "${finding.certainty} ${finding.kind} ${finding.key ?: ""} at " +
        "${finding.site.path}:${finding.site.line} (${finding.site.owner})",
    )
    appendLine("  ${finding.reason}")
    appendLine("  dependency: ${finding.dependencyPath.joinToString(" -> ") { it.label }}")
    appendLine(
      "  canvas: ${finding.canvasPath.joinToString(" -> ") { it.layerId + "@" + (it.site?.path ?: "unknown") }}",
    )
  }
}
