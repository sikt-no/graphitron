# Claude Code Web Environment Setup

This document describes the build environment for Claude Code Web agent sessions.
It is not relevant when building locally or in CI.

## Key Differences from Standard Setup

- **No proxy required.** Direct internet access to Maven Central. Do not configure proxy
  entries in `~/.m2/settings.xml`. The proxy at 21.0.0.129 previously used is no longer available.
- **Docker is unavailable.** `dockerd` fails to start because iptables/nft is not supported
  (kernel too old). Use native PostgreSQL instead of TestContainers wherever possible.

## One-Time Environment Preparation

### Maven settings

If `~/.m2/settings.xml` contains stale proxy entries, replace it with an empty settings file:

```bash
mkdir -p ~/.m2
cat > ~/.m2/settings.xml << 'XMLEOF'
<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.2.0 https://maven.apache.org/xsd/settings-1.2.0.xsd">
</settings>
XMLEOF
```

### PostgreSQL setup

```bash
pg_ctlcluster 16 main start
sudo -u postgres psql -c "ALTER USER postgres PASSWORD 'postgres';"
sudo -u postgres psql -c "CREATE DATABASE rewrite_test;"
sudo -u postgres psql -d rewrite_test \
  -f graphitron-rewrite/graphitron-fixtures/src/main/resources/init.sql
```

The `ALTER USER` step is required because JDBC connects via 127.0.0.1 using scram-sha-256
authentication, while `sudo -u postgres psql` uses peer auth. The `local-db` Maven profile
uses `postgres`/`postgres` credentials.

## Building graphitron-rewrite

The rewrite aggregator builds standalone from `graphitron-rewrite/pom.xml`; no
legacy module (`graphitron-common`, `graphitron-java-codegen`,
`graphitron-maven-plugin`, `graphitron-schema-transform`) needs to exist in the
local repo first. Run commands from the repository root:

```bash
# 1. Build the full rewrite aggregator (javapoet, rewrite, fixtures, maven, test).
#    -Plocal-db switches jooq codegen in fixtures from TestContainers to native Postgres.
mvn install -f graphitron-rewrite/pom.xml -Plocal-db

# 2. Unit and structural tests for graphitron (no DB needed)
mvn test -f graphitron-rewrite/pom.xml -pl :graphitron

# 3. Compilation test: generated code compiles against real jOOQ classes
mvn compile -f graphitron-rewrite/pom.xml -pl :graphitron-test -Plocal-db

# 4. Execution tests: generated code runs against native PostgreSQL
mvn test -f graphitron-rewrite/pom.xml -pl :graphitron-test -Plocal-db
```

To verify the aggregator stays standalone (no legacy artifact leaks into the
build):

```bash
graphitron-rewrite/scripts/verify-standalone-build.sh -Plocal-db
```

Runs `mvn install` against a fresh empty local repo and fails if any
`no.sikt:graphitron-common`, `graphitron-java-codegen`,
`graphitron-maven-plugin`, or `graphitron-schema-transform` artifact is
resolved. The rewrite tree publishes its own `graphitron-javapoet` at the
rewrite version (10-SNAPSHOT), distinct from the legacy 9-SNAPSHOT coord.

### Notes

- The `local-db` profile is defined in `graphitron-fixtures/pom.xml`
  and switches jOOQ codegen from `ContainerDatabaseDriver` to
  `org.postgresql.Driver` at `localhost:5432/rewrite_test`.
- Maven is at `/opt/maven/bin/mvn`; Java 21 is the default JVM. Both are
  pre-installed.
- The legacy repo-root `mvn install` still works as before, but it no longer
  builds the rewrite tree (dropped from the root reactor as part of the
  aggregator-standalone work).

## Fixtures-jar clobber — symptoms and recovery

A cascade of `*PipelineTest` / `GraphitronSchemaBuilderTest` failures with `UnclassifiedType`,
`NoSuchElement`, or `table … could not be resolved in the jOOQ catalog` means the
`graphitron-fixtures` jar in `~/.m2` is missing `DefaultCatalog` — it was installed
before the database was up, or re-installed without `-Plocal-db`. Don't bisect trunk; it's your
local repo. Recover with:

```bash
mvn install -pl :graphitron-fixtures -Plocal-db
```

**Footgun:** any later `mvn install` that hits `graphitron-fixtures` without
`-Plocal-db` — e.g. `mvn install -pl X -am` that transitively rebuilds fixtures, or a full-tree
install — silently re-emits the jar with an empty jOOQ catalog and re-triggers the cascade. After
any broad install, re-run the `-Plocal-db` fixtures install as a final step before testing.
