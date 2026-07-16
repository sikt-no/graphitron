---
id: R483
title: "Audit javadoc for drift against current design and implementation"
status: Ready
bucket: cleanup
priority: 10
theme: docs
depends-on: []
created: 2026-07-15
last-updated: 2026-07-16
---

# Audit javadoc for drift against current design and implementation

The rewrite made several large design pivots over the last two months, and javadoc written against earlier designs is likely to describe behavior that no longer exists: renamed or removed collaborators, retired passes, superseded control flow, contracts that have since inverted. Stale-but-confident javadoc is worse than none: it actively misleads a reader (human or agent) into a wrong mental model of the code. This item is a systematic sweep comparing doc comments against the current implementation and design, module by module, correcting what has drifted. Sequenced after R482, which shrinks and de-noises the surface first (much of the most-drifted text is exactly the historical roadmap narration R482 removes).

## The hazard, and the shape that defends against it

The central hazard is that a corrector confidently rewrites a stale comment to match its *own* wrong understanding, producing fresh prose that is equally unpinned and will re-drift silently. Rewriting prose to "match current understanding" is a second fallible opinion, not a fix. The project principle "documentation names only live tests/code" supplies the defense: prose is trustworthy only to the extent something mechanically pins it.

So the unit of work is **not** "judge prose against code and rewrite." It is: convert an unpinned claim into a form that something mechanical breaks on when it drifts again. The three tools differ in how much enforcement they actually carry *at the routine gate* (`mvn verify -Plocal-db`, the CI build), and that difference is load-bearing here, so they are listed strongest-first:

- **Delete.** Where the claim is dead narration of a retired design, delete it. Fully enforced: deleted prose cannot drift.
- **Pin.** Where a load-bearing claim has no mechanical backing, convert it to a named test or a type-level guarantee (an invariant made unrepresentable rather than documented), not to more confident prose. Enforced at the gate by the test tier or the compiler. See the pin-scope boundary below for what lands inline versus what is filed.
- **Relink.** Where the claim asserts a linkage to a live symbol, convert it to a `{@link}`. Note the honest limit: the javadoc jar goal is bound only in the `release` profile (`pom.xml`), the routine build and CI run no javadoc and no doclint, and `javac` ignores javadoc, so a dangling `{@link}` compiles clean and passes the gate. At that level `{@link}` carries only the IDE-refactor tracking the principles grade as "no audit infrastructure" (`development-principles.adoc`), near-zero protection in a text-editing, multi-agent workflow. Relink is therefore counted as load-bearing **only once** a `{@link}`-reference-validity gate runs in the routine build; that gate is filed as a prerequisite (see `depends-on`). Until it lands, relink is still worth doing (it makes the reference explicit and greppable) but counts as a soft improvement, not a pin, and the plan leans on delete + pin for real enforcement.

Verification has a mechanical spine only to the extent the tool runs at the gate: a delete needs no check, a pin compiles and passes, a relink is gate-checked once the reference gate exists and by symbol-existence spot-check until then. When the audit runs as a fan-out, each proposed change is checked against the *implementation* (and any pin it introduces compiles/passes), never against the comment it replaces.

**Pin-scope boundary.** A trivial pin, converting an existing assertion into a `{@link}` or tightening a test that already exists, lands inline in the slice's commit. A substantive pin, a net-new meta-test or a type-level lift of the kind the principles name (threading a type token, making a field's provenance structural), is itself a reactor change that needs its own roadmap item: the slice files a follow-on and records the still-unpinned claim in its pass notes rather than blocking on writing the pin. The audit's job is to *surface and route* load-bearing-but-unpinned claims, not to build every pin inline.

## Done: leave an enforcer behind, per module

A coverage-percentage or sampling Done is itself an unguarded census that rots (exactly the failure mode this item exists to fix). The defensible Done is module-by-module: each module gets one recorded adversarial pass, **and** that pass leaves the surface more mechanically pinned than it found it (link-conversions, pins for load-bearing claims). The test of a finished module is not "we read all of it" but "the next drift here becomes a compile or test failure (and, once the reference gate lands, a javadoc-reference error) rather than silent rot." Modules are the natural fan-out unit; the item is done when every in-scope module has had its recorded pass (tracked in the Progress ledger below).

## Execution: multi-agent workflow, `graphitron` sub-partitioned

Settled from a post-R482 surface measurement: roughly 3,500 javadoc blocks across about 520 main-source files, with `graphitron` alone holding around 70% of them (circa 2,540 blocks, and a bit over 60% of the files, 321 of ~520). That is well past what a single inline pass can read against the implementation with any depth; an inline sweep would silently degrade into sampling, the census-that-rots failure the Done section rejects. So the audit runs as a fan-out.

- **Fan-out unit.** Module is the coarse unit for the smaller modules (`graphitron-javapoet`, `graphitron-jakarta-rest`, `graphitron-mcp`, `graphitron-lsp`, `graphitron-maven-plugin`, `graphitron-fixtures-codegen`, `graphitron-sakila-service`, `graphitron-sakila-example`), one reader each. `graphitron` is too large for one reader and is sub-partitioned by `rewrite/*` subsystem package (`rewrite/model`, `rewrite/generators`, the `rewrite` core, `rewrite/catalog`, with the small remaining subpackages grouped), each slice sized to a single agent context. The partition is sized on main-source javadoc only (the measured surface above); test-tier comments are lower-risk, are swept opportunistically within the slice, and do not gate the slice's pass. A slice that proves oversized once test comments are folded in is split further by the implementer.
- **Pipeline, not barrier.** Each slice runs reader then adversarial verify independently: a reader proposes relink/pin/delete edits; a verify stage checks each proposed edit against the current implementation, and that any introduced pin compiles and passes, never against the comment it replaces. A slice's edits land only on passing verification. There is no cross-slice barrier; a slice finishes and lands on its own.
- **Per-slice recorded pass** is the synthesis unit; each is checked off in the Progress ledger below as it lands.

Launching the fan-out is token-heavy and is authorized explicitly at In Progress; this Spec fixes the shape, not the spend.

## Seeded findings from R492

R492's gate-greening surfaced concrete drift it deliberately left for this audit (it converted the dangling links to `{@code}` to enable the gate rather than guess the current symbol). Feed these into the relevant module's pass:

- **Retired `BatchKey` classification vocabulary.** `BatchKey` and its arms (`MappedRecordKeyed`, `MappedTableRecordKeyed`, `AccessorKeyedSingle`) no longer exist; comments in `graphitron-sakila-service` fixtures (`FilmService`, `FilmCardData`, `FilmReviewDetails`, `InventoryExtensions`, `RecordExampleType`) and a few `graphitron` sites still name them. Repoint to the current classification vocabulary. Note the sakila-service fixtures do not depend on `graphitron`, so the fix there is prose accuracy, not a `{@link}`.
- **`ChildField.SplitLookupTableField`** (`SplitRowsMethodEmitter`) is retired; the current variants are `LookupTableField` / `BatchedLookupTableField`. Repoint once the correct one is established.
- **Legacy `GraphQLGenerator` clause** (`GraphQLRewriteGenerator`): the `no.sikt.graphitron.generate.GraphQLGenerator` it contrasts against is deleted, so the whole "independent of the legacy generator" clause is likely stale narration to re-evaluate.

## Progress ledger

Each slice records its pass here: check the row and note the landing SHA(s) once the reader-plus-verify pass has landed and left the slice more pinned than it found it (link-conversions done, substantive pins filed as follow-ons per the pin-scope boundary). There is no cross-slice barrier; slices land independently. The item reaches In Review when every row is checked.

- [ ] `graphitron` / `rewrite/model`
- [ ] `graphitron` / `rewrite/generators`
- [ ] `graphitron` / `rewrite/catalog`
- [ ] `graphitron` / `rewrite` core + small subpackages (`selection`, `walker`, `session`, `schema`, `methodgraph`, `lint`, `compile`)
- [ ] `graphitron-javapoet`
- [ ] `graphitron-jakarta-rest`
- [ ] `graphitron-mcp`
- [ ] `graphitron-lsp`
- [ ] `graphitron-maven-plugin`
- [ ] `graphitron-fixtures-codegen`
- [ ] `graphitron-sakila-service`
- [ ] `graphitron-sakila-example`

## Scope

- **In scope:** javadoc and implementation comments across the generator and runtime modules.
- **Out of scope:** the roadmap-reference purge and its guard (R482); authoring net-new manual/architecture pages beyond what pinning a claim requires (file follow-ons).
- **R482 has landed** (de-noised surface; the `RoadmapReferenceGuardTest` guard now prevents the audit's own rewrites from reintroducing roadmap refs). The surface numbers above are measured against that de-noised state.
