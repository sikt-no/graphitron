# Plan: Self-contained rewrite aggregator build

> **Status:** Spec
>
> Sub-item of the "Dissolve `graphitron-schema-transform` module"
> umbrella. Lands after
> [plan-rewrite-owns-schema-loading.md](plan-rewrite-owns-schema-loading.md),
> [plan-tagged-schema-inputs.md](plan-tagged-schema-inputs.md),
> [plan-graphitron-prebuilt-schema.md](plan-graphitron-prebuilt-schema.md),
> and [plan-rewrite-maven-plugin.md](plan-rewrite-maven-plugin.md).
> Those plans remove the last rewrite→legacy code dependencies
> (`graphitron-common` on main, `GraphitronContext` in emitted output,
> the legacy Maven plugin used by `graphitron-rewrite-test`). This
> plan completes the arc by making the rewrite aggregator
> (`graphitron-rewrite/`) buildable in isolation via `mvn install`
> on a clean local Maven repo, with no legacy module required to
> resolve parents, plugins, or compile dependencies.

## Goal

`mvn install` (and `mvn install -Plocal-db`) inside
`graphitron-rewrite/` produces a complete, installable build of
`graphitron-rewrite`, `graphitron-rewrite-fixtures`,
`graphitron-rewrite-test`, `graphitron-rewrite-maven`, and
`graphitron-javapoet` without requiring any artifact outside the
rewrite aggregator to already be present in the local repo. Legacy
modules (`graphitron-common`, `graphitron-java-codegen`,
`graphitron-maven-plugin`, `graphitron-schema-transform`,
`graphitron-example`) are not on the reactor path and not on any
compile classpath.

Driving principle: the rewrite aggregator is a self-contained
Maven project. Someone who clones this repo and cares only about
rewrite code should be able to `cd graphitron-rewrite && mvn install`
and get a working build.

## Scope

**In scope**

- Relocate `graphitron-javapoet` under `graphitron-rewrite/` as
  a sibling module. Artifact coordinates unchanged
  (`no.sikt:graphitron-javapoet`); physical location moves.
- Reparent `graphitron-rewrite-parent` so it no longer inherits
  from the top-level `graphitron-parent`. Inline the
  `<dependencyManagement>`, `<pluginManagement>`, and profile
  entries the aggregator actually consumes.
- Verify `graphitron-rewrite-test` builds against
  `graphitron-rewrite-maven` (the rewrite-owned plugin) with no
  reference to `graphitron-maven-plugin`. Prerequisite plan landed
  this switch; this plan only ratchets it via a build check.
- Add a CI check (or `mise` task) that runs
  `mvn install -f graphitron-rewrite/pom.xml` in a freshly primed
  local repo and asserts no legacy artifact was downloaded or
  resolved.
- Update contributor-facing docs
  (`graphitron-rewrite/docs/claude-code-web-environment.md`, the
  repo-root `README.md` if it references the build flow) to
  describe the aggregator-local build as the supported entry point
  for rewrite-only work.

**Out of scope**

- The top-level `graphitron-parent` aggregator and its modules.
  They keep building via `mvn install` at the repo root; nothing
  about the legacy build changes. The rewrite aggregator becomes
  a second, narrower entry point, not a replacement.
- Retiring `graphitron-maven-plugin`. That is the umbrella's
  closing landing marker and ships only when the legacy generator
  retires.
- Retiring `graphitron-common`. Same caveat: consumers of the
  legacy generator still need it on their runtime classpath; the
  rewrite aggregator simply stops pulling it in.
- Any change to `graphitron-rewrite` compile/runtime semantics.
  This is a build-topology plan; generator behaviour is
  unaffected.

## Current state

`graphitron-rewrite/pom.xml` declares
`<parent>no.sikt:graphitron-parent</parent>` and three modules
(`graphitron-rewrite`, `graphitron-rewrite-fixtures`,
`graphitron-rewrite-test`).

Remaining legacy couplings, by module (post-prerequisite-plans
landing):

| Module | Dep | Resolved by |
|---|---|---|
| `graphitron-rewrite-parent` | parent = `graphitron-parent` | this plan |
| `graphitron-rewrite` main | `graphitron-javapoet` (co-owned by legacy `graphitron-java-codegen`) | this plan (fork into aggregator-local copy) |
| `graphitron-rewrite` main | `graphitron-common` | plan-rewrite-owns-schema-loading |
| `graphitron-rewrite-test` | `graphitron-common` | plan-graphitron-prebuilt-schema (`GraphitronContext` relocation) + lint-forbidden-imports doc update |
| `graphitron-rewrite-test` | `graphitron-maven-plugin` | plan-rewrite-maven-plugin |

Only the first two rows are owned by this plan. The other three
close via their named prerequisite plans; this plan's
exit-criteria check confirms they landed cleanly.

`graphitron-javapoet` today lives at
`graphitron-codegen-parent/graphitron-javapoet/` and is a
`<module>` of `graphitron-codegen-parent`. Consumers: legacy
`graphitron-java-codegen` (import dependency) and
`graphitron-rewrite` main (import dependency). Its POM declares
`<parent>graphitron-codegen-parent</parent>`, which is itself a
child of `graphitron-parent`.

## Design

### 1. Fork `graphitron-javapoet` into the aggregator

Duplicate, do not move: legacy's
`graphitron-codegen-parent/graphitron-javapoet/` stays exactly
where it is with coord `no.sikt:graphitron-javapoet` and parent
`graphitron-codegen-parent`. A second copy lands inside the
rewrite aggregator for rewrite's own use.

Layout:

```
graphitron-rewrite/graphitron-javapoet/           # NEW — rewrite's copy
graphitron-codegen-parent/graphitron-javapoet/    # unchanged — legacy's copy
```

- New module at `graphitron-rewrite/graphitron-javapoet/`. Files
  copied verbatim from the legacy directory (including
  `LICENSE-JAVAPOET.txt` and the full `src/` tree).
- New coord: `no.sikt:graphitron-rewrite-javapoet`. Distinct from
  legacy so there is no coord collision in the local repo and the
  rewrite aggregator's resolve is unambiguous.
- Package stays `no.sikt.graphitron.javapoet`. The rewrite main
  module's existing imports (e.g. `import no.sikt.graphitron.javapoet.JavaFile;`)
  keep working without a rename pass across the rewrite source tree.
  Both copies publish the same package; they never coexist on a
  single classpath (legacy consumers depend on
  `graphitron-javapoet`; rewrite consumers depend on
  `graphitron-rewrite-javapoet`; the rewrite aggregator's build
  resolves only the latter).
- Parent on the new copy: `graphitron-rewrite-parent`.
- `graphitron-rewrite/pom.xml` `<modules>` gains
  `graphitron-javapoet` as the first entry (must build before
  `graphitron-rewrite`, `-fixtures`, `-test`, `-maven`).
- `graphitron-rewrite/graphitron-rewrite/pom.xml` swaps its
  `graphitron-javapoet` dep for `graphitron-rewrite-javapoet`.
- Legacy `graphitron-codegen-parent/pom.xml` unchanged;
  `graphitron-codegen-parent/graphitron-java-codegen/pom.xml`
  unchanged; `graphitron-codegen-parent/graphitron-javapoet/`
  unchanged. Legacy build is byte-identical post-landing.

**Drift between the two copies.** The javapoet tree is a vendored
fork with no upstream sync pressure today; drift is unlikely in
practice. Mitigation if drift becomes a concern: a one-shot
`diff -rq` in CI between the two `src/` trees, flagged on
mismatch. Not wired in this plan; add only if a real divergence
lands.

**Eventual collapse.** The umbrella's closing item,
"Retire legacy and unnest the rewrite aggregator" (see
`rewrite-roadmap.md`), deletes the legacy javapoet module along
with the rest of the legacy tree. The rewrite copy becomes the
only copy at that point and may reclaim the shorter
`graphitron-javapoet` coord (and move to the unnested repo root).
Out of scope for this plan; the closing item tracks it.

### 2. Reparent `graphitron-rewrite-parent`

Change `graphitron-rewrite/pom.xml` from:

```xml
<parent>
    <groupId>no.sikt</groupId>
    <artifactId>graphitron-parent</artifactId>
    <version>${revision}${changelist}</version>
</parent>
```

to a self-declared top-level POM (no `<parent>`). Inline the
entries the aggregator actually consumes:

- `<groupId>`, `<version>`, `<revision>`, `<changelist>`:
  declared locally so CI-friendly versioning keeps working.
- `<properties>`: copy `project.build.sourceEncoding`,
  `project.reporting.outputEncoding`, `version.org.jooq`,
  `version.org.junit`, `version.ch.qos.logback`,
  `version.jackson*`, `version.postgresql`,
  `version.testcontainers.postgresql`, `version.jakarta*`
  (any property read by a child POM under `graphitron-rewrite/`).
- `<dependencyManagement>`: copy entries for `org.junit:junit-bom`,
  `org.jooq:jooq`, `org.jooq:jooq-codegen`, `org.jooq:jooq-meta`,
  `com.graphql-java:graphql-java`,
  `com.graphql-java:graphql-java-extended-scalars`,
  `com.apollographql.federation:federation-graphql-java-support`,
  logback, slf4j, jakarta-validation, jackson-*, assertj,
  mockito, maven-plugin-api, maven-core,
  maven-plugin-annotations, approvaltests, commons-text,
  jetbrains-annotations.
- `<pluginManagement>`: copy
  `maven-compiler-plugin`, `maven-surefire-plugin`,
  `maven-jar-plugin`, `maven-source-plugin`,
  `maven-javadoc-plugin`, `maven-resources-plugin`,
  `maven-clean-plugin`, `maven-install-plugin`,
  `maven-deploy-plugin`, `maven-assembly-plugin`,
  `maven-plugin-plugin`, `flatten-maven-plugin`,
  `jooq-codegen-maven`, `maven-enforcer-plugin`,
  `versions-maven-plugin`.
- `<build><plugins>`: copy the flatten + compiler (Java 21) +
  enforcer executions the legacy parent declares.
- `<profiles>`: copy `quick`. The `release` profile is
  deploy-only and stays on the legacy parent; rewrite-only
  releases are not in scope for this plan.

No `<import>` of the legacy parent's dependencyManagement.
Deliberate: the whole point is that `mvn install` inside the
aggregator does not require the legacy parent artifact. An
`<import>` would still pull the legacy parent's POM from the
local repo; self-declaration avoids the dependency entirely.

**Drift risk.** Two parent POMs now declare overlapping
dependency-management entries. The dominant drift axis is jOOQ /
graphql-java / JUnit major bumps — they land in both parents or
neither. Mitigation: a one-shot diff assertion during review
(visual compare of the two `<dependencyManagement>` blocks) plus
a comment in `graphitron-rewrite/pom.xml` pointing at the legacy
parent: "keep in sync with `graphitron-parent` for versions
shared by legacy consumers; rewrite-only bumps land here
independently". Not a CI ratchet; drift is easy to spot at review
time and harmless for rewrite-only bumps (which is the entire
reason we have two parents).

### 3. Ratchet `graphitron-rewrite-test` off legacy

Exit check, not a code change. After
[plan-rewrite-maven-plugin.md](plan-rewrite-maven-plugin.md) lands,
`graphitron-rewrite-test/pom.xml` should already have switched
`<plugin>graphitron-maven-plugin</plugin>` to
`<plugin>graphitron-rewrite-maven</plugin>`. Verify:

- `grep graphitron-maven-plugin graphitron-rewrite/graphitron-rewrite-test/pom.xml`
  returns zero lines.
- `grep 'no\.sikt\.graphql\.' graphitron-rewrite/graphitron-rewrite-test/src`
  returns zero files outside a forbidden-imports lint test
  (which holds the FQN as a string, not an import).
- `graphitron-rewrite-test/pom.xml` no longer lists
  `<artifactId>graphitron-common</artifactId>`.

If any of those fail, this plan is blocked on the named
prerequisite plan and does not move to Ready.

### 4. CI / mise check

Add `mise r rewrite-standalone` (or equivalent) that runs:

```bash
mvn install -f graphitron-rewrite/pom.xml -Pquick
```

against an empty local repo (`-Dmaven.repo.local=$(mktemp -d)`).
Asserts (via `mvn dependency:list` on each module, filtered):

- No artifact under `no.sikt:graphitron-common`,
  `no.sikt:graphitron-java-codegen`,
  `no.sikt:graphitron-maven-plugin`, or
  `no.sikt:graphitron-schema-transform` is resolved.
- `no.sikt:graphitron-javapoet` resolves from the fresh reactor
  build, not from a pre-populated legacy install.

Runs on every PR that touches `graphitron-rewrite/**` or the
top-level parent. Catches re-introduction of a legacy dep before
it lands on trunk.

### 5. Docs

Update three files:

- `graphitron-rewrite/docs/claude-code-web-environment.md`:
  add a paragraph naming `mvn install -f graphitron-rewrite/pom.xml`
  as the rewrite-only build entry point alongside the existing
  full-repo flow. Keep `-Plocal-db` documentation; the profile
  works identically under the aggregator.
- `graphitron-rewrite/docs/rewrite-design-principles.md`:
  reference the self-contained build as the enforcement mechanism
  behind the "no legacy dependencies from rewrite" invariant the
  document describes.
- Repo root `README.md`: one-line mention under the build section,
  pointing contributors at the aggregator entry point for
  rewrite-only work.

## Tests

This plan adds no unit or integration tests inside the generator
itself. Verification is entirely at the build-topology layer:

- **Aggregator smoke**: `mvn install -f graphitron-rewrite/pom.xml -Pquick`
  on an empty local repo exits 0. All five modules build and
  install.
- **`-Plocal-db` smoke**: `mvn install -f graphitron-rewrite/pom.xml -Plocal-db`
  on an empty local repo (with a pre-running PostgreSQL per the
  existing profile contract) exits 0.
- **Legacy-forbidden-artifact check**: the script from §4 asserts
  no `graphitron-common` / `-java-codegen` / `-maven-plugin` /
  `-schema-transform` artifact is resolved during the aggregator
  build.
- **Full-repo regression**: `mvn install` at the repo root still
  exits 0, with the legacy build byte-identical. Since the
  javapoet fork is additive (legacy's copy stays at its original
  location and coord), this check should be a no-op; it is listed
  as a guard-rail only.

## Deliverable

One commit covering five regions:

1. Copy `graphitron-codegen-parent/graphitron-javapoet/` →
   `graphitron-rewrite/graphitron-javapoet/` (new files); update
   the copy's POM: `<artifactId>graphitron-rewrite-javapoet</artifactId>`
   and `<parent>graphitron-rewrite-parent</parent>`. Legacy copy
   and its POM untouched.
2. Update `graphitron-rewrite/graphitron-rewrite/pom.xml`: swap
   the `graphitron-javapoet` dep for `graphitron-rewrite-javapoet`.
3. Update `graphitron-rewrite/pom.xml`: drop `<parent>`, inline
   property / dependencyManagement / pluginManagement / profile
   blocks, add `<module>graphitron-javapoet</module>` first in
   `<modules>`.
4. Add the standalone-build script (§4) as
   `graphitron-rewrite/scripts/verify-standalone-build.sh` or a
   `mise` task. Wire it to CI in the rewrite-tests workflow.
5. Update the three docs (§5).

Expected diff: ~400 lines added (full javapoet source tree copied
into the rewrite aggregator + the inlined parent management).
Net new code: ~50 lines (the verification script). Legacy tree:
zero lines changed.

## Rollout

Post-landing, update the roadmap:

- Move this plan's umbrella entry from the sub-item list to the
  `## Done` section with a one-line summary citing the commit sha.
- Delete the Cleanup-section entry "Drop `graphitron-common`
  build dependency from `graphitron-rewrite`" if it hasn't
  already been absorbed by `plan-rewrite-owns-schema-loading.md`
  (depending on landing order).
- Delete this plan file on Done per the workflow convention.

## Open decisions

**D1. Javapoet: move vs. duplicate.** Resolved: duplicate. Legacy's
`graphitron-codegen-parent/graphitron-javapoet/` stays at its
current path and coord; rewrite gets an additive copy at
`graphitron-rewrite/graphitron-javapoet/` under coord
`graphitron-rewrite-javapoet`. Alternatives considered:
- **Move**, updating the legacy reactor to reach into the
  rewrite tree. Rejected: a move reshapes the legacy reactor
  order and makes the legacy build depend on the rewrite
  aggregator. The user-facing goal of this plan is exactly the
  reverse of that coupling.
- **External-module reference**, where the aggregator lists
  `../graphitron-codegen-parent/graphitron-javapoet` as a
  `<module>`. Rejected: the aggregator's build still reaches into
  the legacy tree, defeating the "self-contained" goal.

Duplication costs ~1k lines of vendored source held in two places;
the drift-mitigation note in §Design step 1 covers maintenance.
Single-copy collapse happens when legacy retires.

**D2. Reparent strategy.** Resolved: self-parent with inlined
management blocks (option A above). Alternative considered:
`<dependencyManagement><import>` of the legacy parent POM
(option B). Rejected: `<import>` still requires the legacy POM in
the local repo, violating the "standalone" exit criterion.
Inlining costs ~200 lines of duplicated management entries;
acceptable given the drift-mitigation described in §2.

**D3. Release-profile coverage.** Resolved: leave on the legacy
parent. The rewrite artifacts publish today via the top-level
`release` profile; rewrite-only releases are a future item if
they ever become relevant. Not gating this plan.

**D4. When does the `graphitron-rewrite-parent` artifactId
change?** Resolved: it doesn't. Keep `graphitron-rewrite-parent`
as the aggregator POM's artifactId even after reparenting.
Renaming would force a coordinate change on every child POM
(`<parent><artifactId>`) and any external consumer that ever
imports the BOM. The reparenting is internal; the coord stays
stable.
