#!/usr/bin/env bash
# SessionStart hook: bring a Claude Code Web sandbox up to a buildable state for
# graphitron-rewrite. Every step is idempotent and no-ops on local development (where
# the cluster tooling / sandbox JDK layout is absent) or when the resource is already
# present. See .claude/web-environment.md for the rationale behind each step.
#
# In web sessions (CLAUDE_CODE_REMOTE=true) the hook runs in ASYNC mode: it tells the
# harness to start the session immediately and keeps working in the background, first
# the prerequisites below, then a Maven warm build of the whole reactor (R439). Progress
# is tracked in $STATUS_FILE and logged to $LOG_FILE; the PreToolUse guard
# (.claude/scripts/wait-for-web-env.sh) makes mvn/mvnd/psql commands wait on that status
# instead of racing the background work. On local development the hook stays fully
# synchronous and never creates the status file.

set -u

STATUS_FILE=/tmp/graphitron-web-env.status
LOG_FILE=/tmp/graphitron-web-env.log

ASYNC=0
[ "${CLAUDE_CODE_REMOTE:-}" = "true" ] && ASYNC=1

if [ "$ASYNC" -eq 1 ]; then
  # First stdout line switches the harness to async mode; the session starts now.
  printf '%s\n' '{"async": true, "asyncTimeout": 1800000}'
  printf 'prereqs %s %s\n' "$$" "$(date +%s)" > "$STATUS_FILE"
  # Everything below logs to the file; the session has already started.
  exec >>"$LOG_FILE" 2>&1
  # If the hook dies or is killed (asyncTimeout) mid-flight, don't leave a running
  # state behind: the guard would otherwise wait on a corpse until its PID check kicks in.
  cleanup() {
    if grep -qE '^(prereqs|warm-build) ' "$STATUS_FILE" 2>/dev/null; then
      printf 'failed %s %s\n' "$$" "$(date +%s)" > "$STATUS_FILE"
    fi
  }
  trap cleanup EXIT TERM INT
fi

emit() {
  if [ "$ASYNC" -eq 1 ]; then
    printf '[%s] %s\n' "$(date +%H:%M:%S)" "$1"
  else
    printf '%s\n' "{\"systemMessage\":\"$1\"}"
  fi
}

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
  # Agent shells don't always source /etc/profile.d, so also persist JAVA_HOME through
  # the harness env file; a stale inherited JAVA_HOME=21 otherwise trips the enforcer.
  if [ -n "${CLAUDE_ENV_FILE:-}" ] && ! grep -q "JAVA_HOME=$JDK25" "$CLAUDE_ENV_FILE" 2>/dev/null; then
    printf 'export JAVA_HOME=%s\n' "$JDK25" >> "$CLAUDE_ENV_FILE"
  fi
elif command -v java >/dev/null 2>&1 && ! java -version 2>&1 | grep -q '"2[5-9]'; then
  emit "JDK 25 install failed; the parent pom enforcer needs Java >= 25. See .claude/web-environment.md."
fi

# 2. PostgreSQL for -Plocal-db builds. Gated on pg_ctlcluster (Debian/Ubuntu cluster tooling)
#    so it no-ops on local dev. Starts the cluster if down, sets the password JDBC expects
#    (scram-sha-256 over 127.0.0.1, vs peer auth for `sudo -u postgres`), and drops + recreates +
#    seeds rewrite_test on every start so the DB is a pure function of the checked-out init.sql
#    (an existence guard would freeze a stale schema from an older init.sql, e.g. missing R389's
#    party_* tables, and -Plocal-db jOOQ codegen would then build a catalog off that stale DB).
#    DROP ... WITH (FORCE) (PG13+, cluster here is PG16) terminates any lingering backend so the
#    drop cannot fail on a connection a prior session left open. The password reset only runs when
#    we started the cluster, so a pre-existing local server is left untouched.
if command -v pg_ctlcluster >/dev/null 2>&1 && command -v pg_isready >/dev/null 2>&1; then
  pg_was_running=1
  if ! pg_isready -h localhost -p 5432 -q 2>/dev/null; then
    pg_ctlcluster 16 main start >/dev/null 2>&1 || true
    pg_was_running=0
  fi
  if pg_isready -h localhost -p 5432 -q 2>/dev/null; then
    [ "$pg_was_running" -eq 0 ] && { sudo -u postgres psql -qc "ALTER USER postgres PASSWORD 'postgres';" >/dev/null 2>&1 || true; }
    if sudo -u postgres psql -qc "DROP DATABASE IF EXISTS rewrite_test WITH (FORCE);" >/dev/null 2>&1 \
       && sudo -u postgres psql -qc "CREATE DATABASE rewrite_test;" >/dev/null 2>&1 \
       && sudo -u postgres psql -q -d rewrite_test \
            -f "$REPO_ROOT/graphitron-sakila-db/src/main/resources/init.sql" >/dev/null 2>&1; then
      emit "PostgreSQL ready: rewrite_test dropped, recreated, and seeded from init.sql. -Plocal-db builds are ready."
    else
      emit "rewrite_test drop/create/seed failed. See .claude/web-environment.md."
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

# 5. mvnd (Apache Maven Daemon) for fast repeat builds (R474). A resident, JIT-warm Maven
#    JVM with cached plugin classloaders removes the 3-8 s fixed JVM-start + classloader
#    cost every plain mvn invocation pays; agent sessions issue dozens of short Maven
#    commands, so that overhead adds up to minutes per session. Pure accelerator: no pom
#    changes, identical build behaviour, and everything falls back to plain mvn when this
#    install fails. Idempotent: skips when /opt/mvnd/bin/mvnd is already present.
MVND_VERSION=1.0.6
if [ -x /opt/mvnd/bin/mvnd ]; then
  : # already installed, skip
elif command -v curl >/dev/null 2>&1; then
  mvnd_tmp=$(mktemp -d)
  if curl -fsSL -o "$mvnd_tmp/mvnd.tgz" \
       "https://downloads.apache.org/maven/mvnd/${MVND_VERSION}/maven-mvnd-${MVND_VERSION}-linux-amd64.tar.gz" \
     && sudo mkdir -p /opt/mvnd \
     && sudo tar -xzf "$mvnd_tmp/mvnd.tgz" -C /opt/mvnd --strip-components=1 \
     && sudo ln -sf /opt/mvnd/bin/mvnd /usr/local/bin/mvnd; then
    emit "Installed mvnd ${MVND_VERSION} to /opt/mvnd; prefer mvnd over mvn for repeat builds."
  else
    sudo rm -rf /opt/mvnd
    emit "mvnd ${MVND_VERSION} install failed; the session stays fully usable with plain mvn."
  fi
  rm -rf "$mvnd_tmp"
fi

# 6. Warm build (web sessions only, R439). The repo is cloned fresh into every session, so
#    target/ is always empty and, on a cold container, so is ~/.m2. Build the reactor now,
#    in the background, so the agent's first real mvn command runs against a warm cache.
#    -Plocal-db keeps the jOOQ catalog jar correct (see the clobber footgun in
#    web-environment.md), !docs skips the AsciiDoctor render, -DskipTests skips test
#    execution but still compiles tests and runs fixture codegen. The PreToolUse guard
#    holds concurrent mvn/mvnd invocations until this finishes, so two Mavens never fight
#    over ~/.m2 or the same target/ directories. The build prefers mvnd (step 5, R474): a
#    cold daemon costs the same as plain mvn, so starting and JIT-warming it here (3 h idle
#    timeout) is what makes the session's first foreground mvnd command fast.
if [ "$ASYNC" -eq 1 ]; then
  printf 'warm-build %s %s\n' "$$" "$(date +%s)" > "$STATUS_FILE"
  export JAVA_HOME="$JDK25"
  export PATH="$JDK25/bin:$PATH"
  if [ -x /opt/mvnd/bin/mvnd ]; then
    MVN=/opt/mvnd/bin/mvnd
  else
    MVN=$(command -v mvn || echo /opt/maven/bin/mvn)
  fi
  emit "Warm build starting: $MVN -B -ntp install -P local-db,!docs -DskipTests"
  if (cd "$REPO_ROOT" && "$MVN" -B -ntp install -P 'local-db,!docs' -DskipTests); then
    emit "Warm build finished; reactor installed to ~/.m2 with a valid catalog jar."
    printf 'done %s %s\n' "$$" "$(date +%s)" > "$STATUS_FILE"
  else
    emit "Warm build FAILED; the first foreground build will surface the error. Log: $LOG_FILE"
    printf 'failed %s %s\n' "$$" "$(date +%s)" > "$STATUS_FILE"
  fi
fi

exit 0
