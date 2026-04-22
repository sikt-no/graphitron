# Plan — Consolidate rewrite modules under `graphitron-rewrite/`

> **Status:** Spec

Gather the three rewrite modules under a single root directory so that "the rewrite world" is one `cd` away and the aggregator pom can host shared properties and profiles. Pure structural refactor — no behaviour change, no new code, no new dependencies.

Roadmap reference: `docs/planning/rewrite-roadmap.md` — "Consolidate rewrite modules under `graphitron-rewrite/`".

---

## Current layout

```
graphitron-rewrite/                             jar         generator
graphitron-rewrite-test/                        pom         aggregator
  graphitron-rewrite-test-fixtures/             jar         Sakila-flavoured jOOQ fixtures + init.sql
  graphitron-rewrite-test-spec/                 jar         compile + execution test harness
```

Root `pom.xml` lists `graphitron-rewrite` and `graphitron-rewrite-test` as siblings. `graphitron-rewrite-test/pom.xml` is a thin aggregator that carries `<jooqPackage>` and nothing else.

## Target layout

```
graphitron-rewrite/                             pom         aggregator (NEW)
  graphitron-rewrite/                           jar         generator (unchanged source)
  graphitron-rewrite-fixtures/                  jar         renamed from -test-fixtures
  graphitron-rewrite-test/                      jar         renamed from -test-spec
```

Root `pom.xml` lists one module: `graphitron-rewrite`.

## Naming decision

The parent directory and the generator module would both want `graphitron-rewrite` as artifactId. Maven forbids that collision. Resolution:

- **Parent artifactId:** `graphitron-rewrite-parent` (directory stays `graphitron-rewrite/`). Mirrors the existing `graphitron-codegen-parent` precedent — directory and artifactId intentionally differ on aggregator poms.
- **Generator artifactId:** stays `graphitron-rewrite`. No dependency-coordinate churn for anything that `<dependency>`s on it.
- **Fixtures artifactId:** `graphitron-rewrite-test-fixtures` → `graphitron-rewrite-fixtures`. Drops the redundant `-test-` segment; the fixtures aren't only a test artifact (they're also used by `graphitron-maven-plugin` at codegen time in `-test-spec`).
- **Spec artifactId:** `graphitron-rewrite-test-spec` → `graphitron-rewrite-test`. Frees up the directory name we want; the old `graphitron-rewrite-test` aggregator pom goes away with nothing depending on it by coordinate.

Trade-off considered and rejected: parent-keeps-`graphitron-rewrite`, rename generator to `graphitron-rewrite-core` or similar. Rejected because the generator is the public artifact — every external plan, doc, and `-pl` command references it by name and the cost of churning that is far higher than adding a `-parent` suffix on a pom nobody ever references by coordinate.

---

## Step 1 — POM surgery

Five pom files touched; one new, one deleted, three edited.

### `pom.xml` (root) — edit

Replace the two rewrite entries in `<modules>` with a single `graphitron-rewrite`.

### `graphitron-rewrite/pom.xml` (NEW aggregator) — create

```xml
<project …>
    <parent>
        <groupId>no.sikt</groupId>
        <artifactId>graphitron-parent</artifactId>
        <version>${revision}${changelist}</version>
    </parent>
    <artifactId>graphitron-rewrite-parent</artifactId>
    <packaging>pom</packaging>
    <name>${project.groupId}:${project.artifactId}</name>

    <modules>
        <module>graphitron-rewrite</module>
        <module>graphitron-rewrite-fixtures</module>
        <module>graphitron-rewrite-test</module>
    </modules>

    <properties>
        <jooqPackage>no.sikt.graphitron.rewrite.test.jooq</jooqPackage>
    </properties>
</project>
```

Module order matters for reactor: fixtures → generator → test. Maven reorders based on `<dependency>` edges, so listing order is cosmetic, but keep generator before test for readability.

`jooqPackage` migrates verbatim from the deleted `graphitron-rewrite-test/pom.xml`.

### `graphitron-rewrite/graphitron-rewrite/pom.xml` (MOVED from root `graphitron-rewrite/pom.xml`) — edit

- `<parent>`: switch `graphitron-parent` → `graphitron-rewrite-parent` (same groupId/version).
- Test dependency: `graphitron-rewrite-test-fixtures` → `graphitron-rewrite-fixtures` (artifactId only).
- All other content unchanged.

### `graphitron-rewrite/graphitron-rewrite-fixtures/pom.xml` (MOVED from `graphitron-rewrite-test/graphitron-rewrite-test-fixtures/pom.xml`) — edit

- `<artifactId>`: `graphitron-rewrite-test-fixtures` → `graphitron-rewrite-fixtures`.
- `<parent>`: already `graphitron-rewrite-test` → change to `graphitron-rewrite-parent`.
- Everything else (properties, local-db profile, jooq-codegen plugin block) unchanged.

### `graphitron-rewrite/graphitron-rewrite-test/pom.xml` (MOVED from `graphitron-rewrite-test/graphitron-rewrite-test-spec/pom.xml`) — edit

- `<artifactId>`: `graphitron-rewrite-test-spec` → `graphitron-rewrite-test`.
- `<parent>`: `graphitron-rewrite-test` → `graphitron-rewrite-parent`.
- Two dependency rewrites `graphitron-rewrite-test-fixtures` → `graphitron-rewrite-fixtures`: the main `<dependencies>` block, and the plugin-local `<dependencies>` under `graphitron-maven-plugin`.
- Everything else (Java-17 ratchet, local-db profile, graphitron-maven-plugin wiring) unchanged.

### `graphitron-rewrite-test/pom.xml` (old aggregator) — delete

Nothing depends on its artifactId (grep-confirmed — only self-references from its two children).

---

## Step 2 — Filesystem moves

Use `git mv` everywhere to preserve blame/history. Sequence matters because the destination `graphitron-rewrite/` directory initially conflicts with the existing generator module:

```bash
git mv graphitron-rewrite graphitron-rewrite-tmp
mkdir graphitron-rewrite
git mv graphitron-rewrite-tmp graphitron-rewrite/graphitron-rewrite
git mv graphitron-rewrite-test/graphitron-rewrite-test-fixtures graphitron-rewrite/graphitron-rewrite-fixtures
git mv graphitron-rewrite-test/graphitron-rewrite-test-spec       graphitron-rewrite/graphitron-rewrite-test
git rm graphitron-rewrite-test/pom.xml
rmdir graphitron-rewrite-test
```

Write the new aggregator `graphitron-rewrite/pom.xml` after the moves as a `git add` of a new file (it has no predecessor to `git mv` from — the old `graphitron-rewrite-test/pom.xml` is deleted, not relocated, since its contents are subsumed by the new aggregator).

Commit the move as a single commit so `git log --follow` reads cleanly on every file. The moved poms get small edits (1–2 lines: artifactId rename, parent switch) in the same commit; those edits stay well below git's default rename-detection similarity threshold, so follow still traverses the move.

---

## Step 3 — Reference updates outside poms

### Build and environment

- **`docs/claude-code-web-environment.md`** — every occurrence of `graphitron-rewrite-test/graphitron-rewrite-test-fixtures` → `graphitron-rewrite/graphitron-rewrite-fixtures` (one path at `:36`), every `:graphitron-rewrite-test-fixtures` → `:graphitron-rewrite-fixtures`, every `:graphitron-rewrite-test-spec` → `:graphitron-rewrite-test`. The clobber-recovery recipe at `:90` keeps its shape, only the coordinate changes.
- **`docs/rewrite-design-principles.md`** — two `-test-spec` references (`:75`, `:83`) → `-test`.

### Planning documents

Treat this as a mechanical pass. The substitution table:

| Old | New |
| --- | --- |
| `graphitron-rewrite-test/graphitron-rewrite-test-fixtures` (path) | `graphitron-rewrite/graphitron-rewrite-fixtures` |
| `graphitron-rewrite-test/graphitron-rewrite-test-spec` (path) | `graphitron-rewrite/graphitron-rewrite-test` |
| `:graphitron-rewrite-test-fixtures` (coord) | `:graphitron-rewrite-fixtures` |
| `:graphitron-rewrite-test-spec` (coord) | `:graphitron-rewrite-test` |
| `:graphitron-rewrite-test` (old aggregator coord) | drop — no replacement; the old thin aggregator is gone |

One special case: the `mvn install -pl :graphitron-rewrite-test,:graphitron-rewrite-test-fixtures,:graphitron-rewrite-test-spec -Plocal-db` recipe (several plans use it) collapses to `(cd graphitron-rewrite && mvn install -Plocal-db)`. Running Maven from the aggregator directory builds the aggregator and all its `<modules>` in dependency order — simpler than keeping a hand-maintained `-pl` list. Apply the same collapse to the `mvn test` / `mvn verify` variants.

Apply the substitution across `docs/`, then grep to confirm zero remaining matches. Callers are numerous (53 hits at time of writing across roadmap, plans, and environment docs); the grep gate in §Step 4 is the authoritative completeness check.

### Roadmap

The Backlog → Spec promotion already landed in the commit that introduced this plan. The remaining roadmap work is the substitution pass above, applied to every `-test-fixtures` / `-test-spec` occurrence — notably the Java-17 ratchet Done line (`-test-spec` coord) and the "Rebalance test pyramid" Backlog entry (`-test-fixtures` path).

On Done: delete the plan file, flip the roadmap entry `[Spec]` → `[Done]`, and add a one-liner under the Done section referencing the landing SHA (match the `Consolidate rewrite modules under graphitron-rewrite/ shipped at <sha>` style of existing Done entries).

---

## Step 4 — Verification

Build the graph fresh to confirm the aggregator wires everything. Run from inside the aggregator directory — Maven then processes the `<modules>` list in dependency order without needing a hand-maintained `-pl` enumeration:

```bash
(cd graphitron-rewrite && mvn install -Plocal-db)
(cd graphitron-rewrite && mvn test    -pl :graphitron-rewrite)
(cd graphitron-rewrite && mvn compile -pl :graphitron-rewrite-test -Plocal-db)
(cd graphitron-rewrite && mvn test    -pl :graphitron-rewrite-test -Plocal-db)
```

(`-pl :aggregator -am` does *not* descend into aggregator modules — `-am` pulls dependencies, not `<modules>` children. Hence the `cd` rather than the tempting one-liner.)

Full-build parity check: `mvn clean install -Pquick` (root) must still succeed end-to-end.

Git-history spot check: `git log --follow graphitron-rewrite/graphitron-rewrite-fixtures/src/main/resources/init.sql` should traverse the move into `graphitron-rewrite-test/graphitron-rewrite-test-fixtures/...` without gaps.

Grep gate: `grep -rn "graphitron-rewrite-test-fixtures\|graphitron-rewrite-test-spec" .` returns zero matches outside of generated-source directories and `.m2` caches.

---

## What we're NOT doing

- **Fixing the fixtures-jar-clobber footgun.** The roadmap entry mentions the clobber as motivation, but the clobber persists until either (a) `-Plocal-db` is made default, (b) the TestContainers default is removed, or (c) a pre-install sanity check refuses to publish a fixtures jar with an empty jOOQ catalog. All three are their own decisions with their own trade-offs — outside this plan's scope. Backlog follow-up to add: "Make fixtures default profile non-destructive" once this lands.
- **Changing runtime artifact coordinates for the generator.** `no.sikt:graphitron-rewrite` keeps its groupId/artifactId/version. Downstream consumers need zero changes.
- **Maven Central publish exclusions.** The root pom `excludeArtifacts` list covers only `graphitron-example-*`. The rewrite fixtures + test modules were already publishable under their old names and remain so under new names. Whether they *should* publish is a separate question — note it and let a future plan decide.
- **Changing test content or fixtures.** Source files move by `git mv` with zero content edits. Any `package` declarations stay on their Java path (package is driven by source-root-relative path, which doesn't change when the module root relocates).

---

## Why this order

POM surgery before filesystem moves would leave the tree in a non-buildable state (poms reference children at paths that don't exist yet). Filesystem moves before POM surgery leaves the old aggregator pointing at modules in new locations. The only clean sequence is: moves + new aggregator pom + edits to the moved poms, all in one commit. That commit is large by file count but small by logical change — one reviewer read should carry it.

## Estimate

Half a day end-to-end: POM edits + doc sweep + verification builds. No test authoring. Main risk is a stray reference to an old artifactId slipping through — the grep gate at the end of Step 4 catches that.

---

## References

- `docs/planning/rewrite-roadmap.md` line 68 — Backlog entry being promoted.
- `docs/claude-code-web-environment.md` — fixtures-jar-clobber footgun context.
- `graphitron-codegen-parent/pom.xml` — precedent for directory/artifactId divergence on aggregator poms.
