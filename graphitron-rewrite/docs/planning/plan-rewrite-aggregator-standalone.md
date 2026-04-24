# Plan: Self-contained rewrite aggregator build

> **Status:** In Progress
>
> Sub-item of the "Retire `graphitron-maven-plugin` + `graphitron-schema-transform`"
> umbrella (Phase 2). Partial landing in commits `7df7638` + `aa0f0b7`:
> reparent of `graphitron-rewrite-parent`, drop of `graphitron-rewrite`
> from the root reactor, switch of the rewrite tree from
> `${revision}${changelist}` to hardcoded `9-SNAPSHOT`. Remaining work:
> javapoet fork, standalone-build verification, docs. See §Remaining
> work below.

## Goal

`mvn install` (and `mvn install -Plocal-db`) inside
`graphitron-rewrite/` produces a complete, installable build of
`graphitron-rewrite`, `graphitron-rewrite-fixtures`,
`graphitron-rewrite-test`, `graphitron-rewrite-maven`, and an
aggregator-local javapoet module without requiring any artifact
outside the rewrite aggregator to already be present in the local
repo. Legacy modules (`graphitron-common`, `graphitron-java-codegen`,
`graphitron-maven-plugin`, `graphitron-schema-transform`,
`graphitron-example`) are not on the reactor path and not on any
compile classpath.

Driving principle: the rewrite aggregator is a self-contained Maven
project. Someone who clones this repo and cares only about rewrite
code should be able to `cd graphitron-rewrite && mvn install` cold
and get a working build.

## What landed

Commits `7df7638` (root-reactor drop, interim `install-file` plumbing)
and `aa0f0b7` (hardcoded versions, inlined parent management, walk
back to `invoker:install`):

- `graphitron-rewrite/pom.xml` is now a self-declared top-level POM.
  No `<parent>` tag. Inlines `project.build.*Encoding`,
  `version.org.jooq`, `version.org.junit`, `version.ch.qos.logback`,
  `version.postgresql`, `version.testcontainers.postgresql`, a
  narrower `<dependencyManagement>` (junit-bom, jOOQ, graphql-java,
  logback, slf4j, assertj, maven-plugin-api, maven-core,
  maven-plugin-annotations), a `<pluginManagement>` block with all
  relevant plugin versions pinned, a `<build><plugins>` block with
  compiler (`<release>21</release>`, `-Xlint:all`) + enforcer, the
  `quick` profile, plus `<scm>` / `<licenses>` / `<url>`. Legacy
  `graphitron-parent` is no longer required in the local repo to
  resolve rewrite's parent chain.
- Root `pom.xml` drops `<module>graphitron-rewrite</module>`.
  `mvn install` at repo root no longer builds rewrite; the legacy
  build is otherwise byte-identical.
- All five rewrite poms (`graphitron-rewrite/pom.xml` and the four
  child modules) replace `${revision}${changelist}` with hardcoded
  `9-SNAPSHOT`. Signed off as acceptable: CI-friendly property
  versioning is gone on the rewrite tree; the legacy tree keeps it.
  Trade-off: rewrite-tree version bumps now touch five poms instead
  of one property.
- Invoker-plugin IT path simplified. The flatten-plugin + antrun
  workaround that was compensating for `${revision}${changelist}` in
  the plugin-descriptor install flow disappears; `invoker:install`
  works directly. `graphitron-rewrite-maven/pom.xml` loses the
  `maven-antrun-plugin` block and (after the `aa0f0b7` walk-back)
  the temporary `maven-install-plugin` execution.

The `graphitron-rewrite-test` off-legacy exit check from the original
§3 of this plan landed as a prerequisite
(`plan-rewrite-maven-plugin.md`) and was confirmed at landing.

## Remaining work

**Plan §Goal is not yet met.** Empirical verification against an
empty local repo:

```
mvn install -f graphitron-rewrite/pom.xml -Plocal-db -Dmaven.repo.local=$(mktemp -d)
...
[INFO] no.sikt:graphitron-rewrite-parent .................. SUCCESS
[INFO] no.sikt:graphitron-rewrite-fixtures ................ SUCCESS
[INFO] no.sikt:graphitron-rewrite ......................... FAILURE
[ERROR] dependency: no.sikt:graphitron-javapoet:jar:9-SNAPSHOT
[ERROR] 	Could not find artifact no.sikt:graphitron-javapoet:jar:9-SNAPSHOT
```

The rewrite main module still pulls legacy `no.sikt:graphitron-javapoet`,
built by the root reactor's `graphitron-codegen-parent/graphitron-javapoet/`.
Until javapoet lives inside the aggregator, the standalone build only
works if `graphitron-javapoet:9-SNAPSHOT` is already in `~/.m2` from a
prior root-reactor build.

### R1. Fork `graphitron-javapoet` into the aggregator

Duplicate, do not move. Legacy's
`graphitron-codegen-parent/graphitron-javapoet/` stays exactly where
it is with coord `no.sikt:graphitron-javapoet` and parent
`graphitron-codegen-parent`. A second copy lands inside the rewrite
aggregator for rewrite's own use.

Layout:

```
graphitron-rewrite/graphitron-javapoet/           # NEW, rewrite's copy
graphitron-codegen-parent/graphitron-javapoet/    # unchanged, legacy's copy
```

Mechanics:

- New module at `graphitron-rewrite/graphitron-javapoet/`. Files
  copied verbatim from the legacy directory including
  `LICENSE-JAVAPOET.txt` and the full `src/` tree.
- New coord: `no.sikt:graphitron-rewrite-javapoet`. Distinct from
  legacy so there is no coord collision in the local repo and the
  rewrite aggregator's resolve is unambiguous.
- Package stays `no.sikt.graphitron.javapoet`. Rewrite main's
  existing imports (`import no.sikt.graphitron.javapoet.JavaFile;`
  etc.) keep working without a rename pass. Both copies publish the
  same package; they never coexist on a single classpath (legacy
  consumers depend on `graphitron-javapoet`; rewrite consumers on
  `graphitron-rewrite-javapoet`; the rewrite aggregator's build
  resolves only the latter).
- Parent on the new copy: `graphitron-rewrite-parent` version
  `9-SNAPSHOT` (hardcoded, matching the rest of the rewrite tree).
- `graphitron-rewrite/pom.xml` `<modules>` gains
  `graphitron-javapoet` as the first entry so it builds before
  `graphitron-rewrite`, `-fixtures`, `-test`, `-maven`.
- `graphitron-rewrite/graphitron-rewrite/pom.xml` swaps its
  `graphitron-javapoet` dep for `graphitron-rewrite-javapoet`.
- Legacy `graphitron-codegen-parent/pom.xml`,
  `graphitron-codegen-parent/graphitron-java-codegen/pom.xml`, and
  `graphitron-codegen-parent/graphitron-javapoet/` stay byte-identical.

**Drift between the two copies.** The javapoet tree is a vendored
fork with no upstream sync pressure; drift is unlikely. Mitigation
if drift becomes a concern: a one-shot `diff -rq` in CI between the
two `src/` trees, flagged on mismatch. Not wired in this plan; add
only if a real divergence lands.

**Eventual collapse.** The umbrella's closing item,
"Retire legacy and unnest the rewrite aggregator", deletes the
legacy javapoet module along with the rest of the legacy tree. The
rewrite copy becomes the only copy at that point and may reclaim
the shorter `graphitron-javapoet` coord. Out of scope for this plan.

### R2. Standalone-build verification

Add `graphitron-rewrite/scripts/verify-standalone-build.sh`
(or a `mise` task; pick whichever matches the existing verification
surface). The script runs:

```bash
mvn install -f graphitron-rewrite/pom.xml -Pquick \
  -Dmaven.repo.local="$(mktemp -d)"
```

against an empty local repo and asserts (via `mvn dependency:list`
on each module, filtered):

- No artifact under `no.sikt:graphitron-common`,
  `no.sikt:graphitron-java-codegen`,
  `no.sikt:graphitron-maven-plugin`, or
  `no.sikt:graphitron-schema-transform` is resolved.
- `no.sikt:graphitron-rewrite-javapoet` resolves from the fresh
  reactor build, not from a pre-populated legacy install. Legacy
  `no.sikt:graphitron-javapoet` must NOT be resolved.

Runs on every PR that touches `graphitron-rewrite/**` or the
top-level parent. Catches re-introduction of a legacy dep before it
lands on trunk. This is the regression guard that would have caught
the current gap (rewrite-main depending on legacy javapoet) at
review time.

### R3. Docs

Three files:

- `graphitron-rewrite/docs/claude-code-web-environment.md`:
  add a paragraph naming `mvn install -f graphitron-rewrite/pom.xml`
  as the supported rewrite-only build entry point. Keep `-Plocal-db`
  documentation; the profile works identically under the aggregator.
  Remove or update the existing instruction to run `mvn install` at
  the repo root first (it was a workaround for the pre-standalone
  state).
- `graphitron-rewrite/docs/rewrite-design-principles.md`:
  reference the self-contained build as the enforcement mechanism
  behind the "no legacy dependencies from rewrite" invariant.
- Repo root `README.md`: one-line mention under the build section
  pointing contributors at the aggregator entry point for
  rewrite-only work.

## Tests

Verification is entirely at the build-topology layer:

- **Aggregator smoke (cold)**: `mvn install -f graphitron-rewrite/pom.xml -Pquick`
  on an empty local repo exits 0. All five modules (javapoet,
  rewrite, fixtures, test, maven) build and install. This is the
  test that currently fails; it is the acceptance criterion for R1.
- **`-Plocal-db` smoke (cold)**: same command with `-Plocal-db` and
  a pre-running PostgreSQL per the existing profile contract exits 0.
- **Legacy-forbidden-artifact check**: the R2 script asserts no
  `graphitron-common` / `-java-codegen` / `-maven-plugin` /
  `-schema-transform` artifact was resolved during the aggregator
  build.
- **Full-repo regression**: `mvn install` at the repo root still
  exits 0, with the legacy build byte-identical. The javapoet fork
  is additive so this check should be a no-op; listed as a guard-rail
  only. Already verified on the partial landing.

## Deliverable

One commit covering three regions:

1. **Javapoet fork.** Copy `graphitron-codegen-parent/graphitron-javapoet/`
   to `graphitron-rewrite/graphitron-javapoet/` (new files). Update
   the copy's POM: `<artifactId>graphitron-rewrite-javapoet</artifactId>`,
   `<parent>graphitron-rewrite-parent</parent>` pinned at `9-SNAPSHOT`.
   Update `graphitron-rewrite/graphitron-rewrite/pom.xml` to depend
   on `graphitron-rewrite-javapoet` and
   `graphitron-rewrite/pom.xml` `<modules>` to list
   `graphitron-javapoet` first. Legacy tree untouched.
2. **Standalone-build verification.** Add
   `graphitron-rewrite/scripts/verify-standalone-build.sh` (or
   equivalent `mise` task); wire into CI for the rewrite-tests
   workflow.
3. **Docs.** Update the three files in R3.

Expected diff: ~400 lines added for the copied javapoet source tree
plus the verification script (~50 lines). Legacy tree: zero lines
changed.

## Rollout

Post-landing, update the roadmap:

- Move this plan's umbrella entry from the sub-item list to the
  `## Done` section with a one-line summary citing the landing shas
  (`7df7638`, `aa0f0b7`, plus the R1 + R2 + R3 follow-up sha).
- Delete the Cleanup-section entry "Drop `graphitron-common`
  build dependency from `graphitron-rewrite`" if the
  schema-loading plan didn't already absorb it.
- Delete this plan file on Done per the workflow convention.

## Open decisions

**D1. Javapoet: move vs. duplicate.** Resolved: duplicate. Legacy's
`graphitron-codegen-parent/graphitron-javapoet/` stays at its
current path and coord; rewrite gets an additive copy at
`graphitron-rewrite/graphitron-javapoet/` under coord
`graphitron-rewrite-javapoet`. Alternatives considered:
- **Move**, updating the legacy reactor to reach into the rewrite
  tree. Rejected: a move reshapes the legacy reactor order and makes
  the legacy build depend on the rewrite aggregator. The user-facing
  goal of this plan is exactly the reverse of that coupling.
- **External-module reference**, where the aggregator lists
  `../graphitron-codegen-parent/graphitron-javapoet` as a
  `<module>`. Rejected: the aggregator's build still reaches into
  the legacy tree, defeating the "self-contained" goal.

Duplication costs ~1k lines of vendored source held in two places;
drift is unlikely per the mitigation note in R1. Single-copy
collapse happens when legacy retires.

**D2. Reparent strategy.** Resolved: self-parent with inlined
management blocks. No `<import>` of the legacy parent POM.
Delivered in `aa0f0b7`.

**D3. Versioning strategy.** Resolved (sign-off received): drop
CI-friendly `${revision}${changelist}` on the rewrite tree in favor
of hardcoded `9-SNAPSHOT`. Simplifies the Invoker-IT install path
(no flatten-plugin / antrun workaround). Cost: rewrite-tree version
bumps become a five-pom grep-replace instead of one property edit.
Legacy tree keeps CI-friendly versioning unchanged. Delivered in
`aa0f0b7`.

**D4. Release-profile coverage.** Resolved: out of scope. The
rewrite artifacts publish today via the top-level `release` profile
on `graphitron-parent`. Standalone-aggregator releases are a future
item if they ever become relevant.

**D5. `graphitron-rewrite-parent` artifactId.** Resolved: it doesn't
change. Keep `graphitron-rewrite-parent` as the aggregator POM's
artifactId even after reparenting. Renaming would force a coord
change on every child POM and any external BOM consumer. The
reparenting is internal; the coord stays stable.
