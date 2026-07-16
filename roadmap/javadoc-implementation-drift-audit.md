---
id: R483
title: "Audit javadoc for drift against current design and implementation"
status: Spec
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

So the unit of work is **not** "judge prose against code and rewrite." It is: convert an unpinned claim into a form that a compiler, the javadoc tool, or a test will break when it drifts again.

- **Relink.** Where the claim asserts a linkage to a live symbol, convert it to a `{@link}`; a rename or removal then breaks the javadoc build. This is the primary tool.
- **Pin.** Where a load-bearing claim has no pin at all, the signal is to file/add a pin (a named test or a type-level guarantee), not to write more confident prose.
- **Delete.** Where the claim is dead narration of a retired design, delete it.

Verification then has a mechanical spine, does the cited symbol/test exist and say what the prose says, rather than resting on a pure semantic re-read. When the audit runs as a fan-out, each proposed change is checked against the *implementation* (and any pin it introduces compiles/passes), never against the comment it replaces.

## Done: leave an enforcer behind, per module

A coverage-percentage or sampling Done is itself an unguarded census that rots (exactly the failure mode this item exists to fix). The defensible Done is module-by-module: each module gets one recorded adversarial pass, **and** that pass leaves the surface more mechanically pinned than it found it (link-conversions, pins for load-bearing claims). The test of a finished module is not "we read all of it" but "the next drift here becomes a compile/javadoc error rather than silent rot." Modules are the natural fan-out unit; the item is done when every in-scope module has had its recorded pass.

## Execution: multi-agent workflow, `graphitron` sub-partitioned

Settled from a post-R482 surface measurement: roughly 3,400 javadoc blocks across about 520 main-source files, with `graphitron` alone holding around 74% (circa 2,540 blocks over 321 files). That is well past what a single inline pass can read against the implementation with any depth; an inline sweep would silently degrade into sampling, the census-that-rots failure the Done section rejects. So the audit runs as a fan-out.

- **Fan-out unit.** Module is the coarse unit for the smaller modules (`graphitron-javapoet`, `graphitron-jakarta-rest`, `graphitron-mcp`, `graphitron-lsp`, `graphitron-maven-plugin`, `graphitron-fixtures-codegen`, `graphitron-sakila-service`, `graphitron-sakila-example`), one reader each. `graphitron` is too large for one reader and is sub-partitioned by `rewrite/*` subsystem package (`rewrite/model`, `rewrite/generators`, the `rewrite` core, `rewrite/catalog`, with the small remaining subpackages grouped), each slice sized to a single agent context. Main-source javadoc is the priority surface the partition is sized on; test-tier comments in the same slice are swept in the same pass.
- **Pipeline, not barrier.** Each slice runs reader then adversarial verify independently: a reader proposes relink/pin/delete edits; a verify stage checks each proposed edit against the current implementation, and that any introduced pin compiles and passes, never against the comment it replaces. A slice's edits land only on passing verification. There is no cross-slice barrier; a slice finishes and lands on its own.
- **Per-module recorded pass** is both the synthesis unit and the Done ledger (see above).

Launching the fan-out is token-heavy and is authorized explicitly at In Progress; this Spec fixes the shape, not the spend.

## Scope

- **In scope:** javadoc and implementation comments across the generator and runtime modules.
- **Out of scope:** the roadmap-reference purge and its guard (R482); authoring net-new manual/architecture pages beyond what pinning a claim requires (file follow-ons).
- **R482 has landed** (de-noised surface; the `RoadmapReferenceGuardTest` guard now prevents the audit's own rewrites from reintroducing roadmap refs). The surface numbers above are measured against that de-noised state.
