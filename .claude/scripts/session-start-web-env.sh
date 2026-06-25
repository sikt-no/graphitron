#!/usr/bin/env bash
# SessionStart hook: bring a Claude Code Web sandbox up to a buildable state for
# graphitron-rewrite. Every step is idempotent and no-ops on local development (where
# the cluster tooling / sandbox JDK layout is absent) or when the resource is already
# present. See .claude/web-environment.md for the rationale behind each step.

set -u

emit() { printf '%s\n' "{\"systemMessage\":\"$1\"}"; }

# Repo root, resolved from this script's own location so paths work regardless of cwd.
REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)

# 1. JDK 25. The parent pom's enforcer requires Java >= 25. Older sandbox images default
#    to Java 21 and pin it via /etc/profile.d/java.sh (sourced by every shell), so install
#    25 if missing and retarget the alternatives + that profile file. Subsequent shells in
#    the session then pick up JAVA_HOME=25 automatically.
JDK25=/usr/lib/jvm/java-25-openjdk-amd64
if [ ! -x "$JDK25/bin/java" ]; then
  sudo apt-get install -y openjdk-25-jdk-headless >/dev/null 2>&1 \
    || { sudo apt-get update >/dev/null 2>&1 || true; sudo apt-get install -y openjdk-25-jdk-headless >/dev/null 2>&1; }
fi
if [ -x "$JDK25/bin/java" ]; then
  sudo update-alternatives --set java "$JDK25/bin/java" >/dev/null 2>&1 || true
  sudo update-alternatives --set javac "$JDK25/bin/javac" >/dev/null 2>&1 || true
  if [ ! -f /etc/profile.d/java.sh ] || ! grep -q 'java-25' /etc/profile.d/java.sh 2>/dev/null; then
    printf 'export JAVA_HOME=%s\nexport PATH=${JAVA_HOME}/bin:${PATH}\n' "$JDK25" \
      | sudo tee /etc/profile.d/java.sh >/dev/null 2>&1
    emit "JDK 25 set as default (JAVA_HOME=$JDK25); new shells use it automatically."
  fi
elif command -v java >/dev/null 2>&1 && ! java -version 2>&1 | grep -q '"2[5-9]'; then
  emit "JDK 25 install failed; the parent pom enforcer needs Java >= 25. See .claude/web-environment.md."
fi

# 2. PostgreSQL for -Plocal-db builds. Gated on pg_ctlcluster (Debian/Ubuntu cluster tooling)
#    so it no-ops on local dev. Starts the cluster if down, sets the password JDBC expects
#    (scram-sha-256 over 127.0.0.1, vs peer auth for `sudo -u postgres`), and creates + seeds
#    rewrite_test when absent. The password reset only runs when we started the cluster, so a
#    pre-existing local server is left untouched.
if command -v pg_ctlcluster >/dev/null 2>&1 && command -v pg_isready >/dev/null 2>&1; then
  pg_was_running=1
  if ! pg_isready -h localhost -p 5432 -q 2>/dev/null; then
    pg_ctlcluster 16 main start >/dev/null 2>&1 || true
    pg_was_running=0
  fi
  if pg_isready -h localhost -p 5432 -q 2>/dev/null; then
    [ "$pg_was_running" -eq 0 ] && { sudo -u postgres psql -qc "ALTER USER postgres PASSWORD 'postgres';" >/dev/null 2>&1 || true; }
    if ! sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='rewrite_test'" 2>/dev/null | grep -q 1; then
      if sudo -u postgres psql -qc "CREATE DATABASE rewrite_test;" >/dev/null 2>&1 \
         && sudo -u postgres psql -q -d rewrite_test \
              -f "$REPO_ROOT/graphitron-rewrite/graphitron-sakila-db/src/main/resources/init.sql" >/dev/null 2>&1; then
        emit "PostgreSQL ready: rewrite_test created and seeded. -Plocal-db builds are ready."
      else
        emit "rewrite_test create/seed failed. See .claude/web-environment.md."
      fi
    fi
  elif [ "$pg_was_running" -eq 0 ]; then
    emit "PostgreSQL bring-up failed. See .claude/web-environment.md."
  fi
fi

# 3. Maven settings: a stale proxy entry (legacy 21.0.0.129) blocks Maven Central. Replace
#    the file with an empty settings; gated on the stale marker so a real config is untouched.
if [ -f "$HOME/.m2/settings.xml" ] && grep -q '21\.0\.0\.129' "$HOME/.m2/settings.xml" 2>/dev/null; then
  mkdir -p "$HOME/.m2"
  cat > "$HOME/.m2/settings.xml" <<'XMLEOF'
<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.2.0 https://maven.apache.org/xsd/settings-1.2.0.xsd">
</settings>
XMLEOF
  emit "Removed stale proxy from ~/.m2/settings.xml."
fi

# 4. libtree-sitter runtime for graphitron-lsp. jtreesitter 0.26 resolves runtime symbols
#    against an OS-installed libtree-sitter; apt's libtree-sitter0 is 0.20.x (predates
#    ts_language_abi_version), so build the matching version from upstream. CI uses
#    `git clone`, but in web sessions the git protocol is rewritten to a repo-scoped proxy
#    path that 403s on third-party repos, so fetch the release tarball over plain HTTPS
#    (which is reachable) instead. Idempotent: skips when the 0.26 ABI is already present.
TS_VERSION=0.26.9
if ldconfig -p 2>/dev/null | grep -q 'libtree-sitter\.so\.0\.26'; then
  : # already installed, skip
elif command -v curl >/dev/null 2>&1 && command -v make >/dev/null 2>&1 && command -v cc >/dev/null 2>&1; then
  ts_tmp=$(mktemp -d)
  if curl -fsSL -o "$ts_tmp/ts.tgz" "https://github.com/tree-sitter/tree-sitter/archive/refs/tags/v${TS_VERSION}.tar.gz" \
     && tar -xzf "$ts_tmp/ts.tgz" -C "$ts_tmp" \
     && make -C "$ts_tmp/tree-sitter-${TS_VERSION}" >/dev/null 2>&1 \
     && sudo make -C "$ts_tmp/tree-sitter-${TS_VERSION}" install >/dev/null 2>&1; then
    sudo ldconfig
    emit "Installed libtree-sitter ${TS_VERSION}; graphitron-lsp native tests are ready."
  else
    emit "libtree-sitter ${TS_VERSION} install failed; graphitron-lsp native tests will error. See .claude/web-environment.md."
  fi
  rm -rf "$ts_tmp"
fi

exit 0
