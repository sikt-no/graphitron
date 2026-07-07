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
sandbox to a buildable state. In web sessions it runs **asynchronously** (R439): the session
starts immediately while the hook keeps working in the background, first the prerequisite steps
below, then a warm build of the whole reactor (`mvn install -P 'local-db,!docs' -DskipTests`),
so the first foreground `mvn` command runs against a warm `~/.m2` and compiled `target/`
directories instead of paying the cold build. Progress is tracked in
`/tmp/graphitron-web-env.status` (`prereqs` / `warm-build` / `done` / `failed`, plus the hook
PID and a start timestamp) and logged to `/tmp/graphitron-web-env.log`.

You normally never need to think about this: a PreToolUse hook
(`.claude/scripts/wait-for-web-env.sh`) automatically holds any Bash command involving `mvn`
until the warm-up has finished (and `psql` until the prerequisites have finished), so a
foreground build cannot race the background one; two concurrent Mavens sharing `~/.m2` and the
same `target/` directories would corrupt each other, including the catalog-jar clobber below.
The guard fails open if the hook died or the status file is stale, so a crashed warm-up can
never wedge the session; the foreground build then just runs cold. To wait by hand or check
progress, run `.claude/scripts/wait-for-web-env.sh` or `tail -f /tmp/graphitron-web-env.log`.

Each prerequisite step is idempotent and no-ops on local development (where the hook stays
fully synchronous and skips the warm build). It:

- **Installs JDK 25 and makes it the default.** The parent pom's enforcer requires Java >= 25, but
  older sandbox images default to Java 21 and pin it via `/etc/profile.d/java.sh`. The hook installs
  `openjdk-25-jdk-headless` if absent and retargets the alternatives plus that profile file, so
  `JAVA_HOME` resolves to `/usr/lib/jvm/java-25-openjdk-amd64` in every new shell.
- **Brings up PostgreSQL for `-Plocal-db`.** Starts the cluster, sets the `postgres`/`postgres`
  password (JDBC connects over 127.0.0.1 with scram-sha-256, while `sudo -u postgres` uses peer
  auth), and drops, recreates, and re-seeds the `rewrite_test` database from
  `graphitron-sakila-db/src/main/resources/init.sql` on every session start (with
  `DROP DATABASE ... WITH (FORCE)`, so a connection a prior session left open cannot block it).
  Re-seeding unconditionally, rather than only when the DB is absent, keeps the schema a pure
  function of the checked-out `init.sql`: a sandbox seeded from an older revision would otherwise
  keep that stale schema forever. Re-seeding is sub-second on this fixture.
- **Clears a stale Maven proxy.** Replaces `~/.m2/settings.xml` with an empty settings file if it
  still carries the legacy `21.0.0.129` proxy that blocks Maven Central.
- **Builds libtree-sitter `0.26.9`.** `graphitron-lsp`'s jtreesitter 0.26 resolves runtime symbols
  against an OS-installed `libtree-sitter`; apt's is 0.20.x, which predates `ts_language_abi_version`.
  The hook fetches the release tarball over HTTPS and builds it. It uses the tarball rather than
  `git clone` because in repo-scoped web sessions the git protocol is rewritten to a proxy path that
  `403`s on third-party repos; plain HTTPS to `github.com` is unaffected. CI
  (`.github/workflows/rewrite-build.yml`) clones the same `v0.26.9` directly (it has unrestricted
  GitHub access), so the sandbox and CI exercise the same runtime version.

Each step prints a one-line status message on success or failure (into
`/tmp/graphitron-web-env.log` in web sessions, as `systemMessage` output locally). If a step
fails, re-run the script by hand (`CLAUDE_CODE_REMOTE=true .claude/scripts/session-start-web-env.sh`
in a web sandbox) or apply that step manually from its description above. The JDK step also
appends `JAVA_HOME` to `$CLAUDE_ENV_FILE` when the harness provides one, so agent shells do not
inherit a stale `JAVA_HOME=java-21` that trips the enforcer even though `java` on the PATH is 25.

## Building the reactor

The reactor builds from the root `pom.xml`. Run commands from the repository root:

```bash
# 1. Build the full reactor (javapoet, graphitron, sakila-db, sakila-service, mcp, jakarta-rest, maven-plugin, sakila-example, ...).
#    -Plocal-db switches jooq codegen in graphitron-sakila-db from TestContainers to native Postgres.
mvn install -Plocal-db

# 2. Unit and structural tests for graphitron (no DB needed)
mvn test -pl :graphitron

# 3. Compilation test: generated code compiles against real jOOQ classes
mvn compile -pl :graphitron-sakila-example -Plocal-db

# 4. Execution tests: generated code runs against native PostgreSQL
mvn test -pl :graphitron-sakila-example -Plocal-db
```

For what each tier asserts, where its files live, and the `@UnitTier` / `@PipelineTier` / `@CompilationTier` / `@ExecutionTier` meta-annotations, see [`docs/architecture/how-to/testing.adoc`](../docs/architecture/how-to/testing.adoc).

### Notes

- The `local-db` profile is defined in `graphitron-sakila-db/pom.xml`
  and switches jOOQ codegen from `ContainerDatabaseDriver` to
  `org.postgresql.Driver` at `localhost:5432/rewrite_test`.
- Maven is at `/opt/maven/bin/mvn`; the SessionStart hook ensures Java 25 is the
  default JVM (see [Automatic setup](#automatic-setup-sessionstart-hook)). The parent
  pom's enforcer rule fails fast with a clear message if the JVM is older than 25.

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

**A second, now-eliminated cause of the same cascade** was a *stale sandbox DB* (as opposed to a
stale `.m2` catalog jar): a `rewrite_test` seeded from an older `init.sql` — e.g. one predating
R389's `party_*` tables — kept that outdated schema, so `-Plocal-db` codegen built its catalog
against a DB missing tables the current corpus expects. The SessionStart hook now drops and
re-seeds `rewrite_test` on every start (see "Brings up PostgreSQL" above), so a stale sandbox DB no
longer produces this cascade; if you still see it, it is the catalog-jar clobber above, not the DB.
