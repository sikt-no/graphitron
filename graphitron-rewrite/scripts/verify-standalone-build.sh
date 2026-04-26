#!/usr/bin/env bash
# Verify the rewrite aggregator builds standalone without any legacy Graphitron
# artifact in the local Maven repo.
#
# Runs `mvn install` against an empty local repo rooted at a fresh temp dir,
# then scans the resulting repo for any legacy `no.sikt:graphitron-*` artifact.
# Legacy coords that must NOT appear:
#   graphitron-common
#   graphitron-java-codegen
#   graphitron-maven-plugin
#   graphitron-schema-transform
#
# Usage: graphitron-rewrite/scripts/verify-standalone-build.sh [extra mvn args...]
#
# The script passes through anything after `--` to mvn (e.g. `-Plocal-db` for
# database-backed fixture generation). With no extra args the `-Pquick` profile
# is used and DB-dependent work is skipped.
#
# Exit codes:
#   0  clean build, no legacy artifact found
#   1  mvn build failed
#   2  mvn build succeeded but a forbidden legacy artifact was resolved

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AGGREGATOR_POM="${SCRIPT_DIR}/../pom.xml"

FORBIDDEN_COORDS=(
    graphitron-common
    graphitron-java-codegen
    graphitron-maven-plugin
    graphitron-schema-transform
)

TMPREPO="$(mktemp -d)"
trap 'rm -rf "$TMPREPO"' EXIT

echo "==> Standalone-build verification"
echo "    aggregator pom: $AGGREGATOR_POM"
echo "    local repo    : $TMPREPO"

MVN_ARGS=("-f" "$AGGREGATOR_POM" "-Dmaven.repo.local=$TMPREPO")
if [[ $# -eq 0 ]]; then
    MVN_ARGS+=("-Pquick")
else
    MVN_ARGS+=("$@")
fi

echo "==> Running: mvn install ${MVN_ARGS[*]}"
if ! mvn install "${MVN_ARGS[@]}"; then
    echo "==> mvn install FAILED"
    exit 1
fi

echo "==> Scanning local repo for forbidden legacy artifacts"
leaks=()
for coord in "${FORBIDDEN_COORDS[@]}"; do
    hits="$(find "$TMPREPO/no/sikt" -maxdepth 2 -type d -name "$coord" 2>/dev/null || true)"
    if [[ -n "$hits" ]]; then
        leaks+=("$coord")
        echo "    LEAK: $coord"
        echo "$hits" | sed 's/^/          /'
    fi
done

if [[ ${#leaks[@]} -gt 0 ]]; then
    echo "==> FAIL: ${#leaks[@]} forbidden artifact(s) resolved from the rewrite aggregator build"
    exit 2
fi

echo "==> OK: rewrite aggregator built standalone with no legacy artifacts"
