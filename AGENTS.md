# Repository guide

Mosaic is a Kotlin framework for composable backend orchestration. The public
runtime API in `mosaic-core` and `mosaic-test` is independent of the optional
analysis tooling.

## Modules

- `mosaic-core`: runtime Canvas, Mosaic, Tile, and MultiTile APIs.
- `mosaic-test`: runtime tile testing support.
- `mosaic-opentelemetry`: optional execution tracing through OpenTelemetry.
- `mosaic-bom`: runtime dependency alignment.
- `mosaic-analysis-core`: contract model, codec, evaluator, and policy.
- `mosaic-compiler-plugin`: Kotlin IR to contract extraction.
- `mosaic-gradle-plugin`: optional build integration and verification.
- `examples`: separate Gradle build for Spring, Ktor, Micronaut, and shared tiles.

## Documentation skills

- For adoption-focused README/project docs and public summaries, read
  [public-docs](.agents/skills/public-docs/SKILL.md).
- For guides, reference, and explanations in `docs/` or module documentation, read
  [technical-docs](.agents/skills/technical-docs/SKILL.md). Route by reader purpose;
  use both when a change spans both audiences.

## Architecture skills

- When designing or materially restructuring a module, interface, or ownership
  model, especially foundational or public behavior, use
  [codebase-design](.agents/skills/codebase-design/SKILL.md).
- After a substantial feature/refactor is behaviorally correct, use
  [zero-tech-debt](.agents/skills/zero-tech-debt/SKILL.md) to check whether its
  final shape still reflects the historical route taken to get there.

Neither skill is required for routine edits or authorizes unrelated cleanup.

## Focused agent skills

- For reported bugs or performance regressions, use
  [diagnosing-bugs](.agents/skills/diagnosing-bugs/SKILL.md).
- When creating or materially editing guidance for coding agents, use
  [writing-for-agents](.agents/skills/writing-for-agents/SKILL.md).
- Only on explicit user request, use [retro](.agents/skills/retro/SKILL.md) for
  session/environment recommendations or
  [architecture-audit](.agents/skills/architecture-audit/SKILL.md) for architectural
  improvement opportunities. Both stop at recommendations without editing files.

## Analysis invariants

- Support exactly Kotlin compiler and Gradle plugin 2.4.20. Reject unsupported
  project configurations conservatively.
- Mosaic analysis runs inside normal `main` Kotlin compilation. Production
  builds launch no separate Mosaic compiler process.
- The compiler writes source-relative shards for affected files. Shards are
  internal state; packaged dependency metadata is one complete summary.
- `extractMosaicMain` only assembles current-source shards. It produces an
  explicit empty complete summary when Kotlin reports `NO_SOURCE`.
- Dependency summary invalidation is separate from source compilation.
- Incremental and clean builds must produce the same summary and verification
  result. Preserve the current unknown boundaries, metadata compatibility,
  publication behavior, and public API matrix.

## Tests and validation

Put evaluator, model, and codec semantics in `mosaic-analysis-core` tests; IR
to contract fidelity in `mosaic-compiler-plugin` tests; and wiring, incremental
build behavior, and publication in Gradle TestKit tests. Use the cheapest layer
that decisively proves a regression. Keep direct K2 compiler fixtures: they test
IR extraction even though production uses normal Kotlin compilation.

Run before submitting:

```bash
./gradlew clean build
./gradlew clean build -p examples
git diff --check
```

After staging all changes, run `git diff --cached --check` as a separate command
to include newly added files. Resolve any failures before committing or finalizing.

Use a sibling worktree for substantial changes. Do not modify another active
worktree or remove it; remove only the clean worktree created for your task.
