# Contributing to Mosaic

Thanks for your interest in contributing! 🎉

## How to Contribute
- **Issues**: Use GitHub Issues for bug reports or feature requests. Please include steps to reproduce bugs.
- **Pull Requests**: Fork the repo and branch from the intended target: `develop` for new features; `main` for hotfixes and maintenance, including compatibility implementation fixes. Target documentation changes at the branch whose behavior they describe. Link PRs to an issue when possible.
- **Code Style**: Styling is enforced by ktlint. Use `./gradlew ktlintFormat` to auto-fix most issues. Keep commits small and descriptive.
- **Tests**: Meet the Kover coverage requirements. Use the cheapest layer that proves meaningful behavior or a compatibility guarantee; avoid tests coupled to implementation details. See [AGENTS.md](../AGENTS.md#tests-and-validation).
- **Commit Messages**: Use clear, imperative titles (e.g., "Add X" not "Added X").

`main` owns the released code. Maintenance releases proceed directly from `main`, independently of features in progress on `develop`. Integrate ready new features from `develop` into `main` through a reviewed release PR, and carry applicable maintenance fixes back into `develop`.

## Development Setup
1. Clone the repo and open in IntelliJ IDEA.
2. Run `./gradlew clean build` and `./gradlew clean build -p examples`.
3. Validate compatibility records with `python3 compatibility/automation.py validate`.
4. Run `git diff --check` and, after staging, `git diff --cached --check`.

For website work, use [its development workflow](../website/README.md).

## Runtime ABI checks

`./gradlew check` includes Kotlin's built-in `checkKotlinAbi` for `mosaic-core`,
`mosaic-test`, and `mosaic-opentelemetry`. Their committed `api/*.api` files are
the reference for the intended 1.0 Runtime API and expand with accepted additions.
Analysis, benchmarks, examples, and BOM metadata are outside this check.

The checker reports every ABI snapshot difference, including compatible additions;
it does not classify changes as binary compatible or incompatible. For an additive
API change, run `./gradlew :MODULE:updateKotlinAbi`, review the reference diff for
unexpected removals or signature replacements, and commit it with the code. Then
run `./gradlew checkKotlinAbi` (or the full `check`). Do not regenerate references
just to silence an accidental break; preserve the existing signature instead.

These JVM snapshots do not cover behavior, full Kotlin source compatibility
(including reified inline APIs, generic types, and nullability), dependency
compatibility, or inline body changes. Additions to interfaces/SPIs and all
reference updates still need compatibility review.

## Communication
- For general Q&A, use GitHub Issues.
- For bigger ideas or architectural discussions, join us in [GitHub Discussions](https://github.com/BuildMosaic/Mosaic/discussions).
