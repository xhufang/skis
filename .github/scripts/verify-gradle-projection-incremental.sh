#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <skis-version>" >&2
  exit 2
fi

skis_version="$1"
repository_root="$(cd "$(dirname "$0")/../.." && pwd)"
fixture="$repository_root/skis-integration-tests/src/consumer/gradle-incremental"
work_directory="$(mktemp -d)"
trap 'rm -rf "$work_directory"' EXIT

verify_failure() {
  local scenario="$1"
  local expected_diagnostic="$2"
  local scenario_directory="$work_directory/$scenario"
  cp -R "$fixture" "$scenario_directory"

  (
    cd "$scenario_directory"
    gradle --no-daemon --console=plain -q clean compileJava -PskisVersion="$skis_version"
  )

  local generated="$scenario_directory/build/generated/sources/annotationProcessor/java/main/example/skis/PetViewPro.java"
  if [[ ! -f "$generated" ]] || ! grep -q 'ProjectionSource.of(example.skis.PetMeta.NAME)' "$generated"; then
    echo "$scenario did not produce the expected initial special projection" >&2
    exit 1
  fi

  case "$scenario" in
    property-type)
      sed -i 's/String name/Long name/' "$scenario_directory/src/main/java/example/Pet.java"
      ;;
    property-nullability)
      sed -i 's/nullable = false/nullable = true/' "$scenario_directory/src/main/java/example/Pet.java"
      ;;
    property-rename)
      sed -i 's/String name/String label/' "$scenario_directory/src/main/java/example/Pet.java"
      ;;
    *)
      echo "unknown scenario: $scenario" >&2
      exit 2
      ;;
  esac

  set +e
  local output
  output="$(
    cd "$scenario_directory"
    gradle --no-daemon --console=plain -q compileJava -PskisVersion="$skis_version" 2>&1
  )"
  local status=$?
  set -e

  if [[ $status -eq 0 ]]; then
    echo "$scenario unexpectedly reused a stale special projection" >&2
    exit 1
  fi
  if ! grep -Eq "$expected_diagnostic" <<<"$output"; then
    echo "$scenario failed without the expected projection diagnostic" >&2
    echo "$output" >&2
    exit 1
  fi
}

verify_failure property-type '\[SKIS228\]'
verify_failure property-nullability '\[SKIS229\]'
verify_failure property-rename '\[SKIS(224|227|231)\]|cannot find symbol|PetTable.NAME'
