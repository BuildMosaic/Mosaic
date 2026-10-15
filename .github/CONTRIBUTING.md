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
3. Validate compatibility policies with `python3 -m unittest discover -s compatibility`.
4. Run `git diff --check` and, after staging, `git diff --cached --check`.

For website work, use [its development workflow](../website/README.md).

## Communication
- For general Q&A, use GitHub Issues.
- For bigger ideas or architectural discussions, join us in [GitHub Discussions](https://github.com/BuildMosaic/Mosaic/discussions).
