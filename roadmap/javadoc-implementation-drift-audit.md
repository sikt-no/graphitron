---
id: R483
title: "Audit javadoc for drift against current design and implementation"
status: Spec
bucket: cleanup
priority: 10
theme: docs
depends-on: [javadoc-roadmap-reference-purge]
created: 2026-07-15
last-updated: 2026-07-15
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

## Execution (decide at Ready)

This is unbounded semantic work and a natural fan-out: readers per module propose changes; each is adversarially verified against the implementation before it lands. Whether to run it as a multi-agent workflow (higher token cost, better coverage) or inline module-by-module is a deliberate decision to fix before leaving Spec, and depends on how large the surface is once R482 has de-noised it.

## Scope

- **In scope:** javadoc and implementation comments across the generator and runtime modules.
- **Out of scope:** the roadmap-reference purge and its guard (R482); authoring net-new manual/architecture pages beyond what pinning a claim requires (file follow-ons).
- **Depends on R482** landing first (de-noised surface; guard prevents the audit's rewrites from reintroducing roadmap refs).
