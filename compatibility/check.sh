#!/usr/bin/env bash
set -euo pipefail
if [[ $# != 3 ]]; then
  echo "Usage: $0 <runtime> <analysis|-> <kotlin>" >&2
  exit 2
fi
runtime=$1 analysis=$2 kotlin=$3
for value in "$runtime" "$kotlin" "$analysis"; do
  [[ "$value" == - || "$value" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]] || exit 2
done
root=$(cd -- "$(dirname -- "$0")/.." && pwd)
mkdir -p "$root/build/compatibility"
app=$(mktemp -d "$root/build/compatibility/consumer.XXXXXX")
echo "Checking Runtime $runtime / Analysis $analysis / Kotlin $kotlin in $app"
cp "$root/compatibility/settings.gradle" "$app/settings.gradle"
{
  echo "plugins {"
  echo "  id 'org.jetbrains.kotlin.jvm' version '$kotlin'"
  [[ "$analysis" == - ]] || echo "  id 'org.buildmosaic.analysis' version '$analysis'"
  echo "  id 'application'"
  echo "}"
  cat "$root/compatibility/build.gradle"
} > "$app/build.gradle"
mkdir -p "$app/src/main/kotlin"
cp "$root/compatibility/fixtures/RuntimeConsumer.kt" "$app/src/main/kotlin/"
[[ "$analysis" == - ]] || cp "$root/compatibility/fixtures/AnalysisConsumer.kt" "$app/src/main/kotlin/"
args=("-Porg.gradle.java.installations.paths=${JAVA_HOME_17_X64:-},${JAVA_HOME_21_X64:-}" -p "$app" "-Pcompat.runtime=$runtime" "-Pcompat.analysis=$analysis" "-Pcompat.kotlin=$kotlin" --stacktrace --console=plain --no-configuration-cache --max-workers=2)
"$root/gradlew" "${args[@]}" build run
if [[ "$analysis" != - ]]; then
  if "$root/gradlew" "${args[@]}" verifyMosaicMain -Pcompat.missing=true > "$app/missing.log" 2>&1; then
    echo 'Analysis accepted a missing dependency' >&2
    exit 1
  fi
  cat "$app/missing.log"
  grep -q 'MISSING REQUIRED_LOOKUP' "$app/build/reports/mosaic-analysis/main.txt"
fi
