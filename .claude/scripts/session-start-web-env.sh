#!/usr/bin/env bash
# SessionStart hook: ensure the Claude Code Web sandbox env is ready for graphitron-rewrite work.
# No-ops for local development (where PostgreSQL is already up or pg_ctlcluster is not present).
# See graphitron-rewrite/docs/claude-code-web-environment.md for the full bring-up procedure.

set -u

emit() {
  printf '%s\n' "{\"systemMessage\":\"$1\"}"
}

# 1. PostgreSQL availability for -Plocal-db builds.
if command -v pg_isready >/dev/null 2>&1 && pg_isready -h localhost -p 5432 -q 2>/dev/null; then
  : # already running, skip
elif command -v pg_ctlcluster >/dev/null 2>&1; then
  pg_ctlcluster 16 main start >/dev/null 2>&1 || true
  if pg_isready -h localhost -p 5432 -q 2>/dev/null; then
    emit "Started PostgreSQL 16 (web sandbox). -Plocal-db builds are ready."
  else
    emit "PostgreSQL bring-up failed. See graphitron-rewrite/docs/claude-code-web-environment.md."
  fi
fi

# 2. Maven settings: warn (don't auto-rewrite) if stale proxy lingers.
if [ -f "$HOME/.m2/settings.xml" ] && grep -q '21\.0\.0\.129' "$HOME/.m2/settings.xml" 2>/dev/null; then
  emit "Stale proxy entry in ~/.m2/settings.xml (21.0.0.129). Replace per claude-code-web-environment.md before mvn install."
fi

exit 0
