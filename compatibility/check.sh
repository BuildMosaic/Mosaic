#!/usr/bin/env bash
# Check exact Maven artifacts; MOSAIC_COMPAT_REPOSITORY may supply local candidates.
set -euo pipefail
[[ $# == 3 ]] || { echo "Usage: $0 <runtime-version> <analysis-version|-> <kotlin-version>" >&2; exit 2; }
cd "$(dirname "$0")/.."
args=(--no-configure-on-demand --no-configuration-cache --console=plain --max-workers=2)
if [[ -n ${MOSAIC_JAVA_INSTALLATIONS:-} ]]; then
  args+=("-Porg.gradle.java.installations.paths=$MOSAIC_JAVA_INSTALLATIONS")
fi
./gradlew :mosaic-gradle-plugin:testClasses :mosaic-compiler-plugin:testClasses :mosaic-analysis-core:testClasses "${args[@]}"
exec ./gradlew -p compatibility check "${args[@]}" \
  "-Pcompat.runtime=$1" "-Pcompat.analysis=$2" "-Pcompat.kotlin=$3"
