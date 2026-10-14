package org.buildmosaic.analysis

class MosaicAnalyzer {
  fun analyze(request: AnalysisRequest): AnalysisReport {
    request.ruleSeverities.keys.forEach(MosaicRule::configurable)
    val registry = SelectedContractRegistry(listOf(request.program) + request.selectedDependencies)
    val deferred = registry.reusableContractIds().filterNot { id -> request.roots.any { it.target == id } }
    if (request.roots.isEmpty()) return unconfiguredReport(request, deferred)

    val assumptions = request.assumptions.groupBy { it.id }
    val rootReports =
      request.roots
        .sortedBy { it.id }
        .map { root -> RootEvaluator(registry, assumptions, request.limits, root, request.ruleSeverities).evaluate() }
    val findings = rootReports.flatMap { it.findings }
    val decision =
      PolicyEvaluator.evaluate(
        ConfigurationStatus.CONFIGURED,
        findings,
        request.policy,
        request.approvedAssumptions,
      )
    return AnalysisReport(
      configurationStatus = ConfigurationStatus.CONFIGURED,
      roots = rootReports,
      findings = findings,
      deferredContracts = deferred,
      unknownBoundaries = findings.filter { it.rule == null && it.certainty == Certainty.UNVERIFIED },
      limits = request.limits,
      policy = request.policy,
      policyDecision = decision,
    )
  }

  private fun unconfiguredReport(
    request: AnalysisRequest,
    deferred: List<String>,
  ): AnalysisReport {
    val site = SourceLocation("analysis", "<configuration>", 1, 1)
    val finding =
      Finding(
        rootId = "<none>",
        obligationId = "no-roots",
        kind = FindingKind.CONFIGURATION,
        certainty = Certainty.UNVERIFIED,
        site = site,
        reason = "No verification roots were selected",
      )
    val findings = listOf(finding)
    return AnalysisReport(
      configurationStatus = ConfigurationStatus.UNCONFIGURED,
      roots = emptyList(),
      findings = findings,
      deferredContracts = deferred,
      unknownBoundaries = findings,
      limits = request.limits,
      policy = request.policy,
      policyDecision =
        PolicyEvaluator.evaluate(
          ConfigurationStatus.UNCONFIGURED,
          findings,
          request.policy,
          request.approvedAssumptions,
        ),
    )
  }
}
