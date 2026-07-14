#!/usr/bin/env bash
# PreToolUse guard for Bash: while the async SessionStart hook (R439) is still bringing the
# web sandbox up, hold mvn/mvnd and psql commands until the background work they would race
# is finished. Everything else passes through immediately, as does every command on local
# development (no status file) and once the hook has finished (state done/failed).
#
# Status file format (written by session-start-web-env.sh):
#   <state> <pid> <epoch>    state: prereqs | warm-build | done | failed
#
# Wait rules: mvn and mvnd wait through both phases (a concurrent build against a
# half-warmed ~/.m2 or the same target/ dirs corrupts both, including the catalog-jar
# clobber); psql waits only through prereqs (the rewrite_test drop/reseed window). Fail-open on a
# dead hook PID or a stale status file, so a crashed hook can never wedge the session.
#
# Also runnable by hand with no stdin: waits for full completion, tailing the log.

set -u

STATUS_FILE=/tmp/graphitron-web-env.status
LOG_FILE=/tmp/graphitron-web-env.log
DEADLINE_SECS=2400   # hook asyncTimeout is 30 min; fail open a bit after that

[ -f "$STATUS_FILE" ] || exit 0

# Manual invocation (terminal stdin) waits for everything; hook invocation extracts the
# Bash command from the PreToolUse JSON on stdin and only waits when it involves mvn/psql.
manual=0
if [ -t 0 ]; then
  manual=1
else
  cmd=$(jq -r '.tool_input.command // empty' 2>/dev/null || true)
  if printf '%s' "$cmd" | grep -qwE 'mvnd?'; then
    want=mvn
  elif printf '%s' "$cmd" | grep -qw psql; then
    want=psql
  else
    exit 0
  fi
fi

needs_wait() {
  local state pid start now
  read -r state pid start < "$STATUS_FILE" 2>/dev/null || return 1
  case "$state" in
    prereqs) ;;                                            # everyone waits
    warm-build) [ "$manual" -eq 1 ] || [ "$want" = mvn ] || return 1 ;;
    *) return 1 ;;                                         # done/failed/unknown
  esac
  kill -0 "$pid" 2>/dev/null || return 1                   # hook died; fail open
  now=$(date +%s)
  [ $((now - start)) -lt "$DEADLINE_SECS" ] || return 1    # stale; fail open
  return 0
}

waited=0
if [ "$manual" -eq 1 ] && needs_wait; then
  echo "Waiting for the background environment warm-up (log: $LOG_FILE)..."
fi
while needs_wait; do
  sleep 5
  waited=$((waited + 5))
done

if [ "$waited" -gt 0 ]; then
  state=$(cut -d' ' -f1 "$STATUS_FILE" 2>/dev/null || echo unknown)
  if [ "$manual" -eq 1 ]; then
    echo "Background warm-up finished with state: $state (waited ${waited}s)."
  else
    printf '%s\n' "{\"systemMessage\":\"Held this command ${waited}s for the background environment warm-up (final state: $state). See $LOG_FILE.\"}"
  fi
fi

exit 0
