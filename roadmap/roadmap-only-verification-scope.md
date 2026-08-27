---
id: R853
title: "A roadmap-only diff owes the two gates that read roadmap/, not the whole reactor"
status: Spec
bucket: workflow
priority: 2
theme: tooling
depends-on: []
created: 2026-08-27
last-updated: 2026-08-27
---

# A roadmap-only diff owes the two gates that read roadmap/, not the whole reactor

`CLAUDE.md` defines exactly one verification build, `mvn install -Plocal-db`, and one rule about it:
never push a tree that build did not cover. The rule is right. Its granularity is not. The most
frequent commit shape in this repository is a diff entirely under `roadmap/`: filing a Backlog item,
expanding a body at `Backlog → Spec`, appending a reviewer round, marking a phase shipped, deleting a
file at Done. Every one of those pays a full reactor build, and a full reactor build cannot be broken
by any of them, because only two modules in the reactor read `roadmap/` at all.

Both figures below were measured in one session on one machine, with a warm mvnd daemon, on a tree
whose only diff was this item's own markdown. Ratios transfer between machines and absolute seconds
do not, which is why both were taken here rather than quoted from R733:

* `mvnd install -Plocal-db`, the current verification build: **11m27s**, green.
* `mvnd verify -pl roadmap-tool,docs`, every step that reads `roadmap/`: **42.1 seconds**, green.

So a roadmap state transition pays about **sixteen times** its own risk surface. Across a canonical
path of four to six roadmap commits per item, that is roughly **an hour** of build per shipped item
spent proving that markdown cannot break a code generator. The item that produced these numbers is
itself the worked example: correcting one figure in one paragraph of this file, after the first
measurement came in, owed a second 11-minute reactor build under the rule as it stands.

## Roadmap-only is not zero: two gates really do fire

This item is not "skip the build for markdown". Both halves of the scoped build catch real faults,
and both were confirmed by injecting one:

* **A stale `roadmap/README.md`** fails `verify-roadmap-readme` in `graphitron-roadmap-tool` after
  **12.5 seconds**. This is the failure that actually happens, because hand-editing front-matter
  without regenerating is a one-keystroke mistake.
* **A cross-file anchored `xref:` in an item body naming an anchor no target page publishes** fails
  `check-adoc-xrefs` in `graphitron-docs` after **22.2 seconds**. The item body is rendered into the
  published site, so an item's prose can break the docs build. This one matters for the scope
  decision: it is the evidence that `graphitron-docs` is load-bearing in the scoped set rather than
  padding. Dropping it would halve the cost and lose a gate that fires today.

Note which fault is *not* reachable: a dangling cross-file *path* from roadmap prose is counted and
reported rather than failed, deliberately, because item bodies quote example paths. That asymmetry is
the xref check's own design and this item does not touch it.

## What reads `roadmap/` at build time

Two modules, five steps:

* `graphitron-roadmap-tool`, `verify` phase: `verify-roadmap-readme` (README derived from
  front-matter is in sync) and `check-adoc-tables` (authored `.adoc` under the repo root, which
  includes `roadmap/workflow.adoc` and the audit pages).
* `graphitron-docs`, `process-resources`: `render-roadmap-adoc` converts every item to staged
  AsciiDoc, then `check-adoc-xrefs` walks the staged tree, then the `docs` profile renders the site
  at `compile`.

Nothing else. Established by grep rather than assumed: no pom outside those two names the directory
as a build-time path, and no main or test source outside them resolves it. The other `check-*` steps
bound to roadmap-tool's `verify` phase read other trees (`CLAUDE.md`, the poms, `docs/architecture`,
the DDL); they come along for free in the scoped build and cost it nothing to include.

## The change

1. **`CLAUDE.md`, "Building and testing".** Name a second verification build and state its
   precondition and its boundary. Proposed wording to settle at Spec review:

   > A tree whose own commits are entirely under `roadmap/` is verified by
   > `mvn verify -pl roadmap-tool,docs`, which runs every build step that reads `roadmap/` and
   > nothing else. One file outside `roadmap/` in your own commits and you owe the full build.

   Three precisions the wording has to carry, or it will be misread:

   * `verify`, not `install`, and no `-Plocal-db`: neither of these two modules declares that
     profile, so passing it earns a Maven warning and nothing else. The catalog-jar footgun lives in
     `graphitron-sakila-db`, which the scoped build does not touch.
   * Like every scoped `-pl` command already documented, it reads the *installed* artifacts of
     upstream modules, so it assumes a prior full install in the session. For a roadmap-only diff
     that is sound rather than a caveat: nothing you changed is upstream of these two modules, so a
     stale upstream cannot mask your change.
   * The predicate is on **your own commits**, not on the tree. It composes with the carry-forward
     judgment already in that section rather than replacing it: incoming rebased commits arrived with
     their own verification, and this rule decides what the commits you are adding owe.

2. **`roadmap/workflow.adoc`, Publishing.** Mirror the rule where it bites. Every bracketed
   transition in that section is a roadmap-only commit, and that page is what a session reads when
   it is about to make one.

3. **`.claude/skills/publish/SKILL.md`.** The skill's step 2 already reasons about whether the
   verification build still covers the tree. Give it the roadmap-only case, so the scoped build is
   recognised as coverage instead of looking like a skipped step.

## The fork for Spec review: does the scope claim get an enforcer

"Only these two modules read `roadmap/`" is true today and is exactly the shape of claim that rots.
A third consumer would silently make the scoped build under-verify, and nothing would say so. Two
answers, and the reviewer should pick one:

* **Prose only.** Cheapest, ships in three file edits, and drifts the way the module list in
  `CLAUDE.md` drifted before `check-module-enumeration` existed.
* **A `check-roadmap-consumers` step in roadmap-tool** (recommended), fitting the habitat the
  existing `check-*` family already occupies: fail the build when a module outside
  `{graphitron-roadmap-tool, graphitron-docs}` names the roadmap directory as a build-time path, in
  a pom configuration or in a main or test source, outside comment regions (`InertSpans` already
  does that span work for the other checks). It pins the *spelling* of consumption rather than
  consumption itself, which is the same bargain `check-module-enumeration` and
  `check-coverage-agent-wiring` already make, and it is the only reason the documented command can
  be trusted a year from now.

## Boundary

* **Not a general path-to-scope router.** The same waste exists for a `docs/`-only or
  `.claude/`-only diff, and both are larger design questions (a `docs/` edit can break the javadoc
  reference gate and the architecture identifier checks; a `.claude/` edit is checked by
  `check-transient-citations`). This item takes the one region whose reachable gate set is small,
  known, and enforceable. Generalising is a separate item, and should be filed only once this one has
  been lived with.
* **Not CI.** `rewrite-build.yml` runs the full install with no path filters on every push. That is a
  different risk profile and a different clock, and it is not what a session waits on.
* **Not the coverage marker.** R788, which proposes that a verification build record the SHA it
  covered and that publish refuse to push past it, is complementary: it enforces *that* a build
  covered `HEAD`, where this one decides *which* build `HEAD` owed. If both ship, the marker has to
  record which of the two builds ran.

## Tests

* The enforcer's own test in roadmap-tool, if the reviewer takes that arm: a fixture pom outside the
  allowed module set naming the roadmap directory fails; the same reference inside a comment does
  not; the real reactor passes.
* A negative probe re-run at implementation, confirming both numbers above still hold: stale README
  fails the scoped build, and a dangling anchor in an item body fails it in the docs module.
