# Plan — Consolidate rewrite modules under `graphitron-rewrite/`

> **Status:** Ready

Gather the three rewrite modules — and the rewrite-specific subset of `docs/` — under a single root directory so that "the rewrite world" is one `cd` away and the aggregator pom can host shared properties and profiles. Pure structural refactor — no behaviour change, no new code, no new dependencies, no rewritten doc prose.

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

`jooqPackage` migrates verbatim from the deleted `graphitron-rewrite-test/pom.xml`. The `.test.` segment in its value (`no.sikt.graphitron.rewrite.test.jooq`) is deliberately preserved: it's the generated-code FQN for the fixtures jOOQ catalog and is a soft contract with the test-spec consumer; churning it would be coord noise, not a naming fix.

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
- `<outputPackage>` property stays put: it's local to this module (only consumed by the plugin execution in this same pom) and has no reason to hoist to the aggregator.
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
```

That single command runs the full lifecycle (compile + test + install) across fixtures → generator → test across the three children in reactor order. No separate `-pl :graphitron-rewrite-test …` re-run is needed; `install` already runs the test phase on every module in the reactor.

(`-pl :aggregator -am` does *not* descend into aggregator modules — `-am` pulls dependencies, not `<modules>` children. Hence the `cd` rather than the tempting root-level one-liner.)

Full-build parity check: `mvn clean install -Pquick` (root) must still succeed end-to-end.

Git-history spot check: `git log --follow graphitron-rewrite/graphitron-rewrite-fixtures/src/main/resources/init.sql` should traverse the move into `graphitron-rewrite-test/graphitron-rewrite-test-fixtures/...` without gaps.

Grep gate: `grep -rn "graphitron-rewrite-test-fixtures\|graphitron-rewrite-test-spec" .` returns zero matches outside of generated-source directories and `.m2` caches.

---

## Step 5 — Documentation relocation

Same "rewrite world is one `cd` away" logic applies to docs: most of `docs/` is rewrite-specific and would sit better under `graphitron-rewrite/docs/`. Done alongside the module moves so cross-references settle in one coherent change.

### What moves (`docs/` → `graphitron-rewrite/docs/`)

- `claude-code-web-environment.md` — rewrite-build mechanics
- `code-generation-triggers.md` — classification pipeline; its "Source Map" points into rewrite sources
- `rewrite-design-principles.md` / `rewrite-model.md` — explicitly rewrite
- `runtime-extension-points.md` — generated code's runtime extension surface
- `workflow.md` — process doc; only exercised by rewrite work and references `planning/`
- `planning/` — entire subtree (roadmap, plans, research notes)

### What stays at top-level `docs/`

- `README.md` — docs index (restructured as a split — see below)
- `vision-and-goal.md`, `graphitron-principles.md`, `dependencies.md`, `security.md` — project-wide

### Link-fanout work

**Intra-bundle links stay valid** — everything moves together, relative paths unchanged. Spot examples: `rewrite-design-principles.md` → `planning/argument-resolution.md`; `rewrite-model.md` → `planning/rewrite-roadmap.md`; `workflow.md` → `plan-slug.md`; every plan's cross-plan reference.

**Bundle → outside-world links** need one extra `../` because the bundle sits one directory deeper than before. The rule is depth-agnostic, but concrete examples differ by source-file depth — `docs/foo.md` and `docs/planning/plan-foo.md` both need the same one-level adjustment, just applied to their respective starting points:

| Source file depth | Old link | New link |
| --- | --- | --- |
| `docs/foo.md` → project-wide doc (stays) | `graphitron-principles.md` | `../../docs/graphitron-principles.md` |
| `docs/foo.md` → sibling module | `../graphitron-common/README.md` | `../../graphitron-common/README.md` |
| `docs/planning/plan-foo.md` → project-wide doc (stays) | `../graphitron-principles.md` | `../../../docs/graphitron-principles.md` |
| `docs/planning/plan-foo.md` → sibling module | `../../graphitron-codegen-parent/...` | `../../../graphitron-codegen-parent/...` |

Known hits to fix (grep-confirmed at time of writing):
- `rewrite-design-principles.md` → `graphitron-principles.md` (project-wide)
- `runtime-extension-points.md` → `security.md` (×2), `../graphitron-common/README.md`, `../graphitron-example/graphitron-example-server` (sibling modules)
- `code-generation-triggers.md` → `../graphitron-codegen-parent/graphitron-java-codegen/README.md` (sibling module)
- `planning/plan-classification-vocabulary-followups.md:115` → `../../graphitron-codegen-parent/graphitron-java-codegen/README.md` (sibling module, planning-depth)

**Outside-bundle → bundle links** get a `graphitron-rewrite/` prefix:

- `CLAUDE.md` (repo root) — refs to `docs/claude-code-web-environment.md`, `docs/rewrite-design-principles.md`, `docs/planning/rewrite-roadmap.md`, `docs/workflow.md` → `graphitron-rewrite/docs/...`.
- `docs/README.md` — the sections pointing at moved docs (Rewrite Development, planning-subtree bullets) retarget to `graphitron-rewrite/docs/...`. Simpler split: keep the top-level `README.md` as a short project-wide index plus a "Rewrite development" pointer to `graphitron-rewrite/docs/README.md` (new file), which holds the current rewrite-dev sections verbatim.

**String path references** (not markdown links, prose paths):

- `code-generation-triggers.md:240` contains prose "All source lives under `graphitron-rewrite/src/main/java/...`". After the consolidation the generator source lives two directories deep at `graphitron-rewrite/graphitron-rewrite/src/main/java/...` (aggregator-dir / generator-module-dir / src-tree). From the doc's new location `graphitron-rewrite/docs/code-generation-triggers.md`, the correct relative path is `../graphitron-rewrite/src/main/java/...`. Repo-root-relative alternative: `graphitron-rewrite/graphitron-rewrite/src/main/java/...`, noted explicitly as repo-root-relative. Do NOT shorten to `../src/main/java/...`; that resolves to the aggregator dir, not the generator source.

**Canonical-path text** in procedural docs — rewrite semantic references, not line numbers:

- `CLAUDE.md` — "Plans live at `docs/planning/plan-<slug>.md`" → `graphitron-rewrite/docs/planning/plan-<slug>.md`.
- `workflow.md` — every `docs/planning/...` inline reference (currently repo-root-relative) goes stale after move; rewrite to the bundle-relative `planning/...` form since the two are siblings in the new location.

### Sequencing

Doc relocation is a separate commit from the Step 2 pom/module commit — no interdependency. Preference: docs after modules, so the module build is unambiguously green before anyone eyes the doc churn.

### Verification

After the move, three checks. First two are fast greps:

```bash
# (a) Any outside-bundle reference to a moved file that didn't get a graphitron-rewrite/ prefix.
grep -rn '](docs/\(claude-code-web-environment\|code-generation-triggers\|rewrite-[a-z-]*\|runtime-extension-points\|workflow\|planning/\)' .
# (b) Any intra-bundle link that accidentally kept a stale docs/ prefix.
grep -rn '](docs/' graphitron-rewrite/docs/
```

Third is an end-to-end link resolver — catches stale `../../...` depths that the prefix-based greps miss (most important for `graphitron-rewrite/docs/planning/` files whose refs to non-moving siblings now need one more `../`):

```bash
# (c) Resolve every markdown relative-link against its file's directory. Report broken ones.
find . -name '*.md' -not -path './**/target/*' -not -path './.git/*' -print0 |
  while IFS= read -r -d '' md; do
    dir=$(dirname "$md")
    grep -oE '\]\([^)#][^)]*\)' "$md" | sed -E 's/^\]\(([^)#]+).*/\1/' |
      grep -Ev '^(https?|mailto):' |
      while read -r link; do
        [ -e "$dir/$link" ] || echo "BROKEN $md → $link"
      done
  done
```

All three should return zero matches. Supplement with a manual click-through of `CLAUDE.md`, `docs/README.md`, and `graphitron-rewrite/docs/README.md`.

---

## What we're NOT doing

- **Fixing the fixtures-jar-clobber footgun.** The roadmap entry mentions the clobber as motivation, but the clobber persists until either (a) `-Plocal-db` is made default, (b) the TestContainers default is removed, or (c) a pre-install sanity check refuses to publish a fixtures jar with an empty jOOQ catalog. All three are their own decisions with their own trade-offs — outside this plan's scope. Backlog follow-up to add: "Make fixtures default profile non-destructive" once this lands.
- **Changing runtime artifact coordinates for the generator.** `no.sikt:graphitron-rewrite` keeps its groupId/artifactId/version. Downstream consumers need zero changes.
- **Maven Central publish exclusions.** The root pom `excludeArtifacts` list covers only `graphitron-example-*`. The rewrite fixtures + test modules were already publishable under their old names and remain so under new names. Whether they *should* publish is a separate question — note it and let a future plan decide.
- **Changing test content or fixtures.** Source files move by `git mv` with zero content edits. Any `package` declarations stay on their Java path (package is driven by source-root-relative path, which doesn't change when the module root relocates).
- **Rewriting doc prose.** Step 5 is a relocation, not an edit pass. Content moves by `git mv`; only the link paths and a handful of path-in-prose references change. Reshaping `docs/README.md` into a top-level / rewrite-bundle split is the one structural edit in Step 5 — everything else is mechanical.

---

## Why this order

POM surgery before filesystem moves would leave the tree in a non-buildable state (poms reference children at paths that don't exist yet). Filesystem moves before POM surgery leaves the old aggregator pointing at modules in new locations. The only clean sequence is: moves + new aggregator pom + edits to the moved poms, all in one commit. That commit is large by file count but small by logical change — one reviewer read should carry it.

## Estimate

A full day end-to-end: POM edits + module moves (half a day, Steps 1–4) + doc relocation and link fanout (half a day, Step 5) + verification builds. No test authoring. Two categories of risk: a stray reference to an old artifactId (grep gate at end of Step 4) and a broken markdown link after relocation (grep gate + click-through at end of Step 5).

---

## References

- `docs/planning/rewrite-roadmap.md` — Consolidate-rewrite-modules entry.
- `docs/claude-code-web-environment.md` — fixtures-jar-clobber footgun context.
- `graphitron-codegen-parent/pom.xml` — precedent for directory/artifactId divergence on aggregator poms.
