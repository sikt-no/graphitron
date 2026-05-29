#!/usr/bin/env bash
#
# Claude Code on the web — Setup script for graphitron.
#
# WHERE THIS RUNS: paste the contents of this file into the "Setup script" field
# of the cloud environment (web UI: environment selector -> settings). It runs
# ONCE as root before Claude Code launches; its filesystem changes are then
# snapshotted and reused by later sessions (the cache stores files, not running
# processes). Re-runs only when the script or the network allowlist changes, or
# after the cache expires (~7 days).
#
# Pair it with:
#   - the JAVA_HOME environment variable (see .claude/web-environment.md / .env block), and
#   - the SessionStart hook .claude/scripts/session-start-web-env.sh, which starts
#     PostgreSQL on every session (processes are not part of the cached snapshot).
#
# This is the executable companion to .claude/web-environment.md; keep the two in sync.

set -euo pipefail

log() { printf '\n=== %s ===\n' "$1"; }

# ----------------------------------------------------------------------------
# 1. JDK 25 — generator code targets <release>25</release>; the parent pom's
#    requireJavaVersion enforcer fails the build on anything older. The base
#    image defaults to JDK 21, so install 25 and make it the default `java`.
# ----------------------------------------------------------------------------
log "Installing OpenJDK 25"
apt-get update -y
apt-get install -y openjdk-25-jdk-headless
JDK25=/usr/lib/jvm/java-25-openjdk-amd64
update-alternatives --set java "$JDK25/bin/java"
update-alternatives --set javac "$JDK25/bin/javac" 2>/dev/null || true
"$JDK25/bin/java" -version

# ----------------------------------------------------------------------------
# 2. Maven settings — the old sandbox proxy (21.0.0.129) is gone; direct access
#    to Maven Central is available. Ensure no stale proxy lingers.
# ----------------------------------------------------------------------------
log "Writing clean ~/.m2/settings.xml"
mkdir -p "$HOME/.m2"
cat > "$HOME/.m2/settings.xml" <<'XMLEOF'
<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.2.0 https://maven.apache.org/xsd/settings-1.2.0.xsd">
</settings>
XMLEOF

# ----------------------------------------------------------------------------
# 3. libtree-sitter from source — graphitron-lsp's NativeLibraryBundleTest needs
#    a runtime libtree-sitter matching the natives jar's parser ABI. Ubuntu apt
#    ships 0.20.x, which predates ts_language_abi_version. Mirrors the CI step.
# ----------------------------------------------------------------------------
log "Building libtree-sitter from source"
TS_VERSION=v0.26.9
TS_TMP=$(mktemp -d)
git clone --depth=1 --branch="$TS_VERSION" https://github.com/tree-sitter/tree-sitter "$TS_TMP/ts"
make -C "$TS_TMP/ts"
make -C "$TS_TMP/ts" install
ldconfig
rm -rf "$TS_TMP"

# ----------------------------------------------------------------------------
# 4. PostgreSQL — Docker is unavailable in the sandbox, so -Plocal-db uses the
#    native cluster. Create and seed the DB here so the data files land in the
#    cached snapshot; the SessionStart hook restarts the server each session.
#    JDBC connects over 127.0.0.1 with scram-sha-256, hence the password set.
# ----------------------------------------------------------------------------
log "Initializing PostgreSQL 16 + rewrite_test database"
pg_ctlcluster 16 main start
sudo -u postgres psql -c "ALTER USER postgres PASSWORD 'postgres';"
sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='rewrite_test'" \
  | grep -q 1 || sudo -u postgres psql -c "CREATE DATABASE rewrite_test;"

INIT_SQL="graphitron-rewrite/graphitron-sakila-db/src/main/resources/init.sql"
if [ -f "$INIT_SQL" ]; then
  sudo -u postgres psql -d rewrite_test -f "$INIT_SQL"
else
  echo "WARNING: $INIT_SQL not found in setup CWD ($(pwd)); the SessionStart" \
       "hook / first -Plocal-db build will need to seed the schema instead."
fi

log "Setup complete"
