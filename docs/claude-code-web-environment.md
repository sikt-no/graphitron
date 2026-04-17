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
  -f graphitron-rewrite-test/graphitron-rewrite-test-fixtures/src/main/resources/init.sql
```

The `ALTER USER` step is required because JDBC connects via 127.0.0.1 using scram-sha-256
authentication, while `sudo -u postgres psql` uses peer auth. The `local-db` Maven profile
uses `postgres`/`postgres` credentials.

## Building graphitron-rewrite

Run these commands in order from the repository root:

```bash
# 1. Build test fixtures against native Postgres (skips TestContainers)
mvn install -pl :graphitron-rewrite-test-fixtures -am -Plocal-db

# 2. Build graphitron-java-codegen and graphitron-maven-plugin
#    (skip Docker-dependent jooq codegen and test compilation)
mvn install -pl :graphitron-java-codegen,:graphitron-maven-plugin -am \
  -Djooq.codegen.skip=true -Dmaven.test.skip=true

# 3. Unit and structural tests (no DB needed)
mvn test -pl :graphitron-rewrite

# 4. Compilation test — generated code compiles against real jOOQ classes
mvn compile -pl :graphitron-rewrite-test-spec -Plocal-db

# 5. Execution tests — generated code runs against native PostgreSQL
mvn test -pl :graphitron-rewrite-test-spec -Plocal-db
```

### Notes

- The `local-db` profile is defined in `graphitron-rewrite-test-fixtures/pom.xml` and switches
  jOOQ codegen from `ContainerDatabaseDriver` to `org.postgresql.Driver` at `localhost:5432/rewrite_test`.
- `-Djooq.codegen.skip=true` skips the Docker-backed jOOQ test source generation in
  `graphitron-java-codegen` (configured via the `jooq.codegen.skip` property in its pom.xml).
- Maven is at `/opt/maven/bin/mvn`; Java 21 is the default JVM — both are pre-installed.
