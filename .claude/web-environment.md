# Claude Code Web Environment Setup

This document describes the build environment for Claude Code Web agent sessions.
It is not relevant when building locally or in CI.

## Key Differences from Standard Setup

- **No proxy required.** Direct internet access to Maven Central. Do not configure proxy
  entries in `~/.m2/settings.xml`. The proxy at 21.0.0.129 previously used is no longer available.
- **Docker is unavailable.** `dockerd` fails to start because iptables/nft is not supported
  (kernel too old). Use native PostgreSQL instead of TestContainers wherever possible.

## Automatic setup (SessionStart hook)

`.claude/scripts/session-start-web-env.sh` runs at the start of every web session and brings the
sandbox to a buildable state. Each step is idempotent and no-ops on local development. It:

- **Installs JDK 25 and makes it the default.** The parent pom's enforcer requires Java >= 25, but
  older sandbox images default to Java 21 and pin it via `/etc/profile.d/java.sh`. The hook installs
  `openjdk-25-jdk-headless` if absent and retargets the alternatives plus that profile file, so
  `JAVA_HOME` resolves to `/usr/lib/jvm/java-25-openjdk-amd64` in every new shell.
- **Brings up PostgreSQL for `-Plocal-db`.** Starts the cluster, sets the `postgres`/`postgres`
  password (JDBC connects over 127.0.0.1 with scram-sha-256, while `sudo -u postgres` uses peer
  auth), and creates + seeds the `rewrite_test` database from
  `graphitron-rewrite/graphitron-sakila-db/src/main/resources/init.sql`.
- **Clears a stale Maven proxy.** Replaces `~/.m2/settings.xml` with an empty settings file if it
  still carries the legacy `21.0.0.129` proxy that blocks Maven Central.
- **Builds libtree-sitter `0.26.9`.** `graphitron-lsp`'s jtreesitter 0.26 resolves runtime symbols
  against an OS-installed `libtree-sitter`; apt's is 0.20.x, which predates `ts_language_abi_version`.
  The hook fetches the release tarball over HTTPS and builds it. It uses the tarball rather than
  `git clone` because in repo-scoped web sessions the git protocol is rewritten to a proxy path that
  `403`s on third-party repos; plain HTTPS to `github.com` is unaffected. CI
  (`.github/workflows/rewrite-build.yml`) clones the same `v0.26.9` directly (it has unrestricted
  GitHub access), so the sandbox and CI exercise the same runtime version.

Each step prints a one-line status message on success or failure. If a step fails, re-run the script
by hand (`.claude/scripts/session-start-web-env.sh`) or apply that step manually from its description
above.

## Building graphitron-rewrite

The rewrite aggregator builds standalone from `graphitron-rewrite/pom.xml`; no
legacy module (`graphitron-common`, `graphitron-java-codegen`,
`graphitron-maven-plugin`, `graphitron-schema-transform`) needs to exist in the
local repo first. Run commands from the repository root:

```bash
# 1. Build the full rewrite aggregator (javapoet, rewrite, sakila-db, sakila-service, maven, sakila-example, ...).
#    -Plocal-db switches jooq codegen in graphitron-sakila-db from TestContainers to native Postgres.
mvn install -f graphitron-rewrite/pom.xml -Plocal-db

# 2. Unit and structural tests for graphitron (no DB needed)
mvn test -f graphitron-rewrite/pom.xml -pl :graphitron

# 3. Compilation test: generated code compiles against real jOOQ classes
mvn compile -f graphitron-rewrite/pom.xml -pl :graphitron-sakila-example -Plocal-db

# 4. Execution tests: generated code runs against native PostgreSQL
mvn test -f graphitron-rewrite/pom.xml -pl :graphitron-sakila-example -Plocal-db
```

For what each tier asserts, where its files live, and the `@UnitTier` / `@PipelineTier` / `@CompilationTier` / `@ExecutionTier` meta-annotations, see [`graphitron-rewrite/docs/testing.adoc`](../graphitron-rewrite/docs/testing.adoc).

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

- The `local-db` profile is defined in `graphitron-sakila-db/pom.xml`
  and switches jOOQ codegen from `ContainerDatabaseDriver` to
  `org.postgresql.Driver` at `localhost:5432/rewrite_test`.
- Maven is at `/opt/maven/bin/mvn`; the SessionStart hook ensures Java 25 is the
  default JVM (see [Automatic setup](#automatic-setup-sessionstart-hook)). The parent
  pom's enforcer rule fails fast with a clear message if the JVM is older than 25.
- The legacy repo-root `mvn install` still works as before, but it no longer
  builds the rewrite tree (dropped from the root reactor as part of the
  aggregator-standalone work).

## Catalog-jar clobber — symptoms and recovery

A cascade of `*PipelineTest` / `GraphitronSchemaBuilderTest` failures with `UnclassifiedType`,
`NoSuchElement`, or `table … could not be resolved in the jOOQ catalog` means the
`graphitron-sakila-db` jar in `~/.m2` is missing `DefaultCatalog` — it was installed
before the database was up, or re-installed without `-Plocal-db`. Don't bisect trunk; it's your
local repo. Recover with:

```bash
mvn install -pl :graphitron-sakila-db -Plocal-db
```

**Footgun:** any later `mvn install` that hits `graphitron-sakila-db` without
`-Plocal-db` — e.g. `mvn install -pl X -am` that transitively rebuilds the catalog, or a full-tree
install — silently re-emits the jar with an empty jOOQ catalog and re-triggers the cascade. After
any broad install, re-run the `-Plocal-db` install for the catalog as a final step before testing.
