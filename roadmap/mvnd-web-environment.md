---
id: R474
title: "Adopt mvnd in the web dev environment to cut Maven JVM warmup"
status: Backlog
bucket: improvement
theme: tooling
depends-on: []
created: 2026-07-13
last-updated: 2026-07-13
---

# Adopt mvnd in the web dev environment to cut Maven JVM warmup

Every `mvn` invocation in an agent session pays a fresh JVM start plus plugin-classloader
loading and cold JIT, roughly 3 to 8 seconds of fixed overhead per command. Agent sessions
issue dozens of short Maven commands (single-module test runs, `roadmap-tool exec:java`,
incremental installs), so the fixed overhead adds up to minutes per session even though the
R439 warm build already removes the cold-compile cost. [mvnd (Apache Maven
Daemon)](https://maven.apache.org/tools/mvnd.html) keeps a resident, JIT-warm Maven JVM with
cached plugin classloaders between invocations and would eliminate most of this.

## Findings from a research session (2026-07-13, web sandbox, 4 cores / 15 GB)

mvnd 1.0.6 (linux-amd64, embeds Maven 3.9.16) installs and runs cleanly on the sandbox's
JDK 25; the full reactor builds green through it. Measurements against the same warm state:

| Scenario | `mvn` 3.9.11 | `mvnd` warm daemon |
|---|---|---|
| No-change `install -P 'local-db,!docs' -DskipTests` (full reactor) | 28.9 s | ~21 s |
| `mvn -pl roadmap-tool exec:java -q` | 3.2 s | 0.3 s |
| `mvn test -pl :graphitron -Dtest=NodeIdLeafResolverTest` (11 tests) | 6.1 s | 2.4 s |

A cold daemon costs the same as plain `mvn` (28.8 s on the first scenario), so the win
requires the daemon to be started ahead of the first foreground command. Daemon RSS is
about 1.35 GB (fine on the 15 GB sandbox); default idle timeout is 3 hours. Surefire still
forks a fresh test JVM per module, so long test-heavy builds gain proportionally less; the
saving is concentrated in the many short commands.

## Integration points that need handling

- **PreToolUse guard bypass.** `.claude/scripts/wait-for-web-env.sh` matches commands with
  `grep -qw mvn`, which does not match `mvnd`; an `mvnd` command issued during the R439
  background warm build would race it (including the catalog-jar clobber). The guard must
  match both.
- **Daemon start belongs in the SessionStart hook.** Install mvnd during prereqs (24 MB
  tarball from `downloads.apache.org`, reachable through the sandbox proxy) and run the R439
  warm build itself through `mvnd`, leaving a hot daemon for the session's first foreground
  command. Otherwise the first mvnd invocation pays the cold-daemon cost and the benefit
  shrinks to later commands only.
- **Maven version skew.** mvnd 1.0.6 bundles Maven 3.9.16 while `/opt/maven` is 3.9.11.
  CI resolves Maven from the runner image, so the repo is not strictly pinned anyway, but
  the skew should be stated in `.claude/web-environment.md`.
- **Default parallelism.** mvnd defaults to `-T <cores-1>` module-parallel builds. On the
  no-change pass this changed nothing, and module-parallel test execution against the shared
  native PostgreSQL (`rewrite_test`) is unproven; adopt with `mvnd.threads=1` pinned in
  `.mvn/mvnd.properties` (mvnd reads project-local config) and evaluate parallelism
  separately.
- **Output differences.** `mvnd -q ... exec:java` swallowed the tool's stdout in the
  research session ("wrote README" did not appear even though the file was written); agent
  docs should note this or the invocation flags should avoid `-q` where output is asserted.
- **SNAPSHOT plugins.** mvnd excludes SNAPSHOT plugins from classloader caching, so the
  `10-SNAPSHOT` `graphitron-maven-plugin` used by `graphitron-sakila-example` is reloaded
  per build; no stale-plugin hazard, slightly less benefit for that module.

## Scope sketch

Extend `.claude/scripts/session-start-web-env.sh` (install mvnd, warm build via mvnd),
fix the guard match in `wait-for-web-env.sh`, add `.mvn/mvnd.properties`, and update
`CLAUDE.md` / `.claude/web-environment.md` command guidance to prefer `mvnd` in web
sessions. Local development and CI keep plain `mvn`; nothing in the build itself changes.
