---
id: R474
title: "Adopt mvnd in the web dev environment to cut Maven JVM warmup"
status: In Progress
bucket: improvement
theme: tooling
depends-on: []
created: 2026-07-13
last-updated: 2026-07-14
---

# Adopt mvnd in the web dev environment to cut Maven JVM warmup

> Install [mvnd (Apache Maven Daemon)](https://maven.apache.org/tools/mvnd.html) 1.0.6 in
> the web sandbox during SessionStart prereqs, run the R439 warm build through it so the
> daemon is hot before the first foreground command, and keep mvnd's default module-parallel
> execution with tests on. Parallel test tolerance becomes an enforced build property, not a
> sandbox-only shape: CI's verify job switches to `-T 1C` so a parallel-hostile test fails at
> the gate. Any test that cannot survive module-parallel execution is a bug in the test, not
> a reason to serialize. mvnd is a pure accelerator: no pom changes, identical build
> behaviour, fail-open fallback to plain `mvn` everywhere.

## Motivation

Every `mvn` invocation in an agent session pays a fresh JVM start plus plugin-classloader
loading and cold JIT, roughly 3 to 8 seconds of fixed overhead per command. Agent sessions
issue dozens of short Maven commands (single-module test runs, `roadmap-tool exec:java`,
incremental installs), so the fixed overhead adds up to minutes per session even though the
R439 warm build already removes the cold-compile cost. [mvnd (Apache Maven
Daemon)](https://maven.apache.org/tools/mvnd.html) keeps a resident, JIT-warm Maven JVM with
cached plugin classloaders between invocations and would eliminate most of this.

## Evidence (research sessions 2026-07-13/14, web sandbox, 4 cores / 15 GB)

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

**Parallel test execution is already green.** A full `mvnd install -P 'local-db,!docs'`
with tests on and mvnd's default `-T <cores-1>` (3 threads) passed all 13 modules in
2:23 wall clock, including `graphitron-sakila-example` execution tests against the live
`rewrite_test` PostgreSQL while sibling modules tested concurrently. The smart builder
reported 35% concurrency: the critical path (`graphitron` 47 s, then
`graphitron-maven-plugin` 27 s, then `graphitron-sakila-example` 42 s) is serial by
dependency, so parallelism is behaviour-safe today and its wall-clock gain grows with
core count rather than being large on this 4-core sandbox. Shared-state audit backing
this up: only `graphitron-sakila-example` tests touch the live database (mcp and
maven-plugin tests use fake JDBC URLs), and classifier-trace emission writes one
`leaf-coverage.jsonl` per module by design, "no reactor-shared file is needed"
(parent pom, leaf-coverage profile).

## Design

All five steps below are implemented on `claude/r474-spec-ready-review-2d34l2` (single
commit; the seams added no review value split apart). In-sandbox verification on landing:
mvnd 1.0.6 installed via the new step (idempotent re-run skips; simulated 404 emits the
one-line failure status, leaves no `/opt/mvnd` residue, and the hook continues); the
modified hook run end-to-end in async mode chose `/opt/mvnd/bin/mvnd` for the warm build
and reached `done`; the edited guard, driven with real PreToolUse JSON against a simulated
`warm-build` status, held `mvnd` and `mvn` commands and passed `psql` and unrelated
commands through; full `mvnd install -Plocal-db` with tests passed all 13 modules in 2:30
under default `-T <cores-1>` parallelism; warm-daemon `mvnd -pl roadmap-tool exec:java`
ran in 0.32 s with `mvnd --status` showing the hook-shaped daemon. The CI `-T 1C`
acceptance criterion is checked by the CI run this branch's push triggers.

1. **SessionStart prereq step: install mvnd.** New idempotent step in
   `.claude/scripts/session-start-web-env.sh` (alongside the libtree-sitter step and
   mirroring its shape): skip if `/opt/mvnd/bin/mvnd` already exists, otherwise fetch the
   24 MB `maven-mvnd-1.0.6-linux-amd64.tar.gz` from `downloads.apache.org` (reachable
   through the sandbox proxy), extract to `/opt/mvnd`, and symlink
   `/usr/local/bin/mvnd`. On any failure: emit a one-line status and continue; the session
   stays fully usable with plain `mvn`.
2. **Warm build runs through mvnd.** Step 5 of the hook prefers
   `mvnd` when the install succeeded and falls back to the existing
   `$(command -v mvn || echo /opt/maven/bin/mvn)` otherwise. This leaves a hot, JIT-warm
   daemon (3 h idle timeout) for the session's first foreground command; a cold daemon
   costs the same as plain `mvn`, so warming it here is where the benefit comes from. The
   status-file contract (`prereqs`/`warm-build`/`done`/`failed`, PID, timestamp) stays
   byte-identical; the guard must never wedge on a failed mvnd.
3. **Guard covers mvnd.** `.claude/scripts/wait-for-web-env.sh` currently matches
   `grep -qw mvn`, which does not match the word `mvnd`; an mvnd command issued during the
   background warm build would race it (catalog-jar clobber included). Change the match to
   cover both (`grep -qwE 'mvnd?'`).
4. **Module-parallel execution stays on, and CI enforces it.** No
   `.mvn/mvnd.properties` thread pinning: web sessions run mvnd's default
   `-T <cores-1>`, tests included. To make parallel tolerance an invariant with an
   enforcer rather than a sandbox-only aspiration, the `rewrite-build.yml` verify job
   switches to `mvn verify -Plocal-db --batch-mode -T 1C`; a test that fails only under
   module-parallel execution then fails at the gate, and the fix is the test, never
   serializing the build. This also keeps sandbox and CI exercising the same execution
   shape (the workflow explicitly mirrors `.claude/web-environment.md` "so no second
   profile drifts"). Within-module (surefire) parallelism is a separate axis and stays
   out of scope.
5. **Docs.** `CLAUDE.md` and `.claude/web-environment.md` gain "prefer `mvnd` in web
   sessions" guidance and the accepted-quirks list below. Every existing `mvn` command in
   both docs (catalog-clobber recovery, `-pl`/`-am` footgun) remains correct verbatim
   under either driver; the docs state that interchangeability explicitly. Local
   development keeps plain `mvn`; no pom changes anywhere.

## Acceptance criteria

- Fresh web session: after warm-up completes, `mvnd -pl roadmap-tool exec:java` finishes
  in under 1 s (warm daemon), and `mvnd --status` shows the daemon started by the hook.
- `wait-for-web-env.sh` holds `mvnd` commands during `prereqs` and `warm-build` exactly
  as it holds `mvn` commands (verifiable by the guard's own unit of behaviour: the grep
  matches both words).
- With the mvnd download made to fail (simulated), the hook completes the warm build via
  plain `mvn`, the status file reaches `done`, and the session works as today.
- CI `rewrite-build.yml` verify job is green with `-T 1C` on the PR that lands this.
- Full `mvnd install -Plocal-db` with tests passes in a web session under default
  parallelism (already demonstrated once; re-verified on landing).

## Accepted quirks (documented, not fixed here)

- **Maven version skew.** mvnd 1.0.6 bundles Maven 3.9.16 while `/opt/maven` is 3.9.11.
  The repo pins no Maven version anywhere (CI uses the runner's), so this is not a new
  category of drift; stated in `.claude/web-environment.md`.
- **Output buffering.** `mvnd -q ... exec:java` swallowed the tool's stdout in the
  research session (the work was done; the "wrote README" line never appeared). Agent
  docs note this; scripts that assert on stdout avoid `-q` under mvnd.
- **SNAPSHOT plugins.** mvnd excludes SNAPSHOT plugins from classloader caching, so the
  `10-SNAPSHOT` `graphitron-maven-plugin` used by `graphitron-sakila-example` reloads per
  build; no stale-plugin hazard, slightly less speedup for that module.
- **Daemon footprint.** ~1.35 GB RSS while alive; acceptable on the 15 GB sandbox and
  reclaimed at the 3 h idle timeout.

## Out of scope

- Within-module (surefire/JUnit) test parallelism; different axis, different item.
- mvnd for local development or as a repo-pinned build driver (`mvnw`-style wrapper).
- mvnd 2.x / Maven 4; revisit when the reactor moves to Maven 4.
