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

## Reviewer findings

### Round 1 (2026-08-27, Spec -> Ready, reviewer session 0169pRZRYLidSbfcif3xovzW)

Verdict: withhold. Two findings, one root cause: the grep behind the scope claim missed one idiom,
and that same idiom is what the recommended enforcer would fail on. The goal reads clearly and the
prose half of the change is well placed. A session about to commit a roadmap state transition runs a
42-second scoped build instead of an 11-minute reactor build, and the three documents a session
actually reads before that push all say so. Nothing about the plan's shape is a parallel mechanism:
`CLAUDE.md`'s "Building and testing" already names one verification build and already carries a
carry-forward judgment for rebased commits, so a second named build with a stated predicate extends
that section rather than competing with it, and the publish skill's step 2 already reasons about
whether a build still covers the tree.

Most of what the plan asserts about the tree holds. Both module selectors are real reactor
directories (`roadmap-tool`, `docs`); the artifact ids `graphitron-roadmap-tool` and `graphitron-docs`
are the ones the plan gives them. All five named steps exist at the phases claimed:
`verify-roadmap-readme` and `check-adoc-tables` bound to `verify` in `roadmap-tool/pom.xml`, and
`render-roadmap-adoc`, `check-adoc-xrefs` bound to `process-resources` plus `render-site` at `compile`
under the `activeByDefault` `docs` profile in `docs/pom.xml`, with the xref check declared after the
renderer in the same phase so it walks a staged tree. Neither pom declares `local-db`, so `verify`
without that profile is right and the catalog-jar footgun really is out of reach. The
`check-adoc-xrefs` asymmetry the plan relies on is the check's actual behaviour: a dangling anchored
xref throws, a dangling cross-file path from roadmap prose goes onto a report-only list and prints.
`docs` declares a reactor-order dependency on `graphitron-roadmap-tool` and a repo dependency on
`graphitron`, so the prior-full-install precondition is stated correctly. The arithmetic checks
(687s / 42.1s is 16.3; five commits at 11m27s is 57 minutes). `InertSpans`,
`check-module-enumeration`, `check-coverage-agent-wiring` and `check-transient-citations` all exist
under those names. The pom half of the scope claim holds exactly as stated: outside `roadmap-tool` and
`docs`, the only pom naming the directory is the root, in XML comments and in `<module>roadmap-tool</module>`.

**1. The source half of the scope claim is false, and it is the claim the whole item rests on
(question 1).** "Nothing else. Established by grep rather than assumed: ... no main or test source
outside them resolves it" does not survive an FQN-blind grep for the path. Four test sources in three
modules outside the scoped set resolve it today:
`graphitron/src/test/java/no/sikt/graphitron/rewrite/GuardScope.java:48` and
`JavadocReferenceGateTest.java:128` (`p.resolve("roadmap/workflow.adoc")`),
`graphitron-mcp/src/test/java/no/sikt/graphitron/mcp/StoreClientBoundaryTest.java:244` (the same),
and `graphitron-sakila-example/src/test/java/no/sikt/graphitron/rewrite/test/internal/ReadmeLinkIntegrityTest.java:116`
(`Files.isDirectory(p.resolve("roadmap"))`). All four are repo-root probes rather than readers, which
is why the conclusion is probably still right, but the last one does read roadmap content:
`ReadmeLinkIntegrityTest` walks every `README.md` in the repo, `roadmap/README.md` among them, and
asserts each of its 623 relative link targets exists. A roadmap-only diff that leaves a dangling
`[plan](<slug>.md)` fails a test in `graphitron-sakila-example`, which the scoped build does not run.

What would satisfy this is the argument the plan currently forecloses by asserting there is nothing
to argue about. That argument exists and it is a good one: `verify-roadmap-readme` compares
`roadmap/README.md` byte-for-byte against a fresh render of the item files, and `ConceptIndex`
resolves item liveness in one place, so a README the scoped build passes has item links that resolve
by construction, and the residual link surface (the header's `../docs/...` and `workflow.adoc` links)
comes from the renderer's own template in `roadmap-tool` main source, where editing it is not a
roadmap-only diff. Say that, and restate the claim as what was actually established: no build step
and no test outside those two modules reads content a roadmap-only diff can change. As written, an
implementer who re-runs the grep the plan says was run finds four hits and no guidance.

**2. The recommended enforcer, as specified, fails the reactor as it stands, and names a reuse target
that does not do the work (question 2).** The plan asks the reviewer to pick an arm, so the arm has to
be pickable. "Fail the build when a module outside `{graphitron-roadmap-tool, graphitron-docs}` names
the roadmap directory as a build-time path, in a pom configuration or in a main or test source,
outside comment regions" fires on all four sites in finding 1, none of which is in a comment. The
Tests section then requires "the real reactor passes". Those two cannot both hold, and the distinction
that would reconcile them, a repo-root existence probe against a genuine consumer, is the entire
design content of the check: it decides whether the enforcer pins something worth pinning or becomes
a rule contributors route around. That distinction is the author's to draw, not the implementer's to
invent at the keyboard, which is what makes this a question-2 finding rather than an implementation
note.

The reuse premise is also misdirected. `InertSpans` masks AsciiDoc code spans and structural blocks,
which is why its consumers are `AdocXrefAnchorCheck`, `SchemaIdentifierDriftCheck` and the generated
AsciiDoc renderers; it has no notion of a Java or XML comment. The pom half of the scan does have a
precedent, `CoverageAgentWiringCheck`, which strips `<!-- ... -->` before matching and already walks
the root pom plus every declared module. The Java half has none in `roadmap-tool`: comment-region
masking over Java lives in `RoadmapReferenceScanner`, in `graphitron`'s test tier. So the arm as
specified needs either a new Java scanner in `roadmap-tool` or a habitat in the `graphitron` test tier
next to the guard that already does this work, and picking between those changes what gets built. Name
the precedents the arm actually reuses, and say which module the Java half lives in.

Worth noting because it cuts toward the enforcer rather than against it: if the check is spelled as
"names the roadmap directory", `roadmap-tool` as a bare module name is a substring hit, so the rule
needs to be path-shaped rather than word-shaped.

Non-blocking, noticed along the way. `RetiredVocabularyGuardTest` puts `roadmap/` out of scope
explicitly in its own javadoc, and `DocsIndexBuilder` says the same for the MCP retrieval index, so
both of the plausible-looking third consumers really are non-consumers by declared intent. That is
worth a clause in the scope section: it is the strongest evidence the plan has that the boundary is
deliberate in this tree rather than accidental, and it costs one sentence.
