package org.buildmosaic.analysis

internal object PolicyEvaluator {
  fun evaluate(
    status: ConfigurationStatus,
    findings: List<Finding>,
    policy: AnalysisPolicy,
    approvedAssumptions: Set<String>,
  ): PolicyDecision {
    val activeFindings = findings.filter { it.suppressedAt.isEmpty() }
    val errors =
      activeFindings.filter { finding ->
        if (finding.rule != null) {
          finding.severity == MosaicRuleSeverity.ERROR
        } else {
          status == ConfigurationStatus.UNCONFIGURED ||
            finding.certainty == Certainty.MISSING ||
            policy == AnalysisPolicy.STRICT && finding.certainty == Certainty.UNVERIFIED ||
            policy == AnalysisPolicy.STRICT && finding.hasUnapprovedAssumption(approvedAssumptions)
        }
      }
    val warnings =
      activeFindings.filter { finding ->
        finding !in errors &&
          if (finding.rule != null) {
            finding.severity == MosaicRuleSeverity.WARNING
          } else {
            finding.certainty == Certainty.UNVERIFIED
          }
      }
    return PolicyDecision(errors.isEmpty(), errors, warnings)
  }

  private fun Finding.hasUnapprovedAssumption(approvedAssumptions: Set<String>): Boolean =
    EvidenceKind.EXTERNAL_ASSUMPTION in evidence && !approvedAssumptions.containsAll(assumptionIds)
}
