---
name: architecture-audit
description: Only on explicit user request, inspect a Mosaic area or codebase for architectural improvement opportunities. Rank recommendations without implementation.
---

# Architecture audit

Run only when the user explicitly requests architectural improvement opportunities
in an area or codebase. This is a read-only audit ending in recommendations.
[codebase-design](../codebase-design/SKILL.md) remains the authority for designing
the consequential change at hand; [zero-tech-debt](../zero-tech-debt/SKILL.md)
remains the final-shape pass for substantial completed work. Neither ordinary design
nor finalization implicitly invokes this audit.

1. Establish the requested scope before exploring. Honor a named area or pain point;
   for a whole-codebase request, use recent changes/hot code to prioritize inspection
   and state what was actually covered. Ask only if the scope cannot be established.
2. Read relevant current domain/architecture docs and the local codebase-design
   skill. Inspect actual owners, callers, state/lifecycle rules and contract tests;
   use history to explain present friction rather than justify preserving it.
3. Look for consequential duplicated ownership, behavior scattered among owners,
   shallow abstractions that burden callers, awkward seams, poor change locality,
   unnecessarily complex interfaces, tests reaching through the wrong abstraction,
   and historical architecture still shaping current code. Apply codebase-design's
   deletion test and criteria for leverage, locality and real variability. File size,
   helper count or an imaginable abstraction alone is not a finding.

Read affected module guides: [runtime](../../../mosaic-core/README.md),
[tracing](../../../mosaic-opentelemetry/README.md),
[analysis model/evaluator](../../../mosaic-analysis-core/README.md),
[compiler extraction](../../../mosaic-compiler-plugin/README.md) and
[Gradle integration](../../../mosaic-gradle-plugin/README.md), only as relevant.
Pay particular attention to their boundaries with runtime execution,
Canvas/configuration and public compatibility. Preserve deliberate conservative
unknowns and assess the [public API matrix](../../../mosaic-compiler-plugin/src/test/PUBLIC_API_MATRIX.md)
and [published compatibility](../../../website/src/content/docs/reference/compatibility.mdx).
Absence of local callers does not establish that consumers do not use a public API;
source, JVM binary and SPI obligations need separate consideration. A deeper module
does not automatically mean fewer internal types or tests: compiler/runtime
correspondence, IR fidelity, concurrency and cancellation invariants remain valid.

Rank only meaningful, evidenced candidates. For each give:

- Affected area and concrete files/callers supporting the finding.
- Current friction and the proposed architectural direction.
- Expected improvement in leverage/change locality and verification ownership.
- Risks, tradeoffs, compatibility/transition obligations and unresolved evidence.
- Recommendation strength: strong or worth exploring, with the reason.

Name the top recommendation and why it wins, or state that no meaningful candidate
was established. Present plain Markdown/chat and stop. Do not implement a candidate,
edit documentation, remove tests or begin a follow-on design interview without a
separate user authorization. No HTML report, glossary, ADR system or other supporting
infrastructure is required or created by this audit.

Conceptual source: Matt Pocock's [improve-codebase-architecture](https://github.com/mattpocock/skills/tree/v1.3.1/skills/engineering/improve-codebase-architecture)
(v1.3.1); rewritten for Mosaic's repository guidance.
