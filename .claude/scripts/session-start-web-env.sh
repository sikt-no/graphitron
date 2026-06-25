#!/usr/bin/env bash
# SessionStart hook: ensure the Claude Code Web sandbox env is ready for graphitron-rewrite work.
# No-ops for local development (where PostgreSQL is already up or pg_ctlcluster is not present).
# See .claude/web-environment.md for the full bring-up procedure.

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
    emit "PostgreSQL bring-up failed. See .claude/web-environment.md."
  fi
fi

# 2. Maven settings: warn (don't auto-rewrite) if stale proxy lingers.
if [ -f "$HOME/.m2/settings.xml" ] && grep -q '21\.0\.0\.129' "$HOME/.m2/settings.xml" 2>/dev/null; then
  emit "Stale proxy entry in ~/.m2/settings.xml (21.0.0.129). Replace per .claude/web-environment.md before mvn install."
fi

# 3. libtree-sitter runtime for graphitron-lsp's jtreesitter 0.26 tests.
#    Ubuntu apt's libtree-sitter0 is pinned to 0.20.x (predates ts_language_abi_version),
#    so the modern runtime must be built from upstream source. A `git clone` of
#    tree-sitter/tree-sitter is refused in repo-scoped web sessions (the proxy only
#    serves repos in this session's GitHub scope), but the release tarball is reachable
#    over plain HTTPS, so fetch that instead. See .claude/web-environment.md.
TS_VERSION=v0.26.9
TS_SONAME=libtree-sitter.so.0.26
if command -v ldconfig >/dev/null 2>&1 && ldconfig -p 2>/dev/null | grep -q "$TS_SONAME"; then
  : # modern runtime already present, skip
elif command -v curl >/dev/null 2>&1 && command -v make >/dev/null 2>&1 && command -v cc >/dev/null 2>&1; then
  TS_TMP=$(mktemp -d)
  if curl -fsSL "https://github.com/tree-sitter/tree-sitter/archive/refs/tags/${TS_VERSION}.tar.gz" -o "$TS_TMP/ts.tgz" 2>/dev/null \
     && tar -xzf "$TS_TMP/ts.tgz" -C "$TS_TMP" 2>/dev/null; then
    TS_SRC="$TS_TMP/tree-sitter-${TS_VERSION#v}"
    if make -C "$TS_SRC" >/dev/null 2>&1 && sudo make -C "$TS_SRC" install >/dev/null 2>&1; then
      sudo ldconfig
      emit "Installed libtree-sitter ${TS_VERSION} (web sandbox). graphitron-lsp tests are ready."
    else
      emit "libtree-sitter build/install failed. See .claude/web-environment.md."
    fi
  else
    emit "libtree-sitter download failed (HTTPS to github.com). See .claude/web-environment.md."
  fi
  rm -rf "$TS_TMP"
fi

exit 0
