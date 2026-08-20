---
id: R756
title: "The orphan sweep never visits four subpackages the generator emits into"
status: Backlog
bucket: correctness
priority: 2
theme: codegen-correctness
depends-on: []
created: 2026-08-20
last-updated: 2026-08-20
---

# The orphan sweep never visits four subpackages the generator emits into

Clean removal is one of the three clauses of the generator's output contract: a compilation unit
the schema no longer calls for is swept out of the output rather than left behind as an orphan that
still compiles and still resolves. `GraphQLRewriteGenerator.sweepOrphans` implements it by walking a
fixed list of subpackages it considers its own, deleting every `.java` file in them that this run
did not emit.

That list is narrower than the set of subpackages the generator actually writes into. It names the
output package's root plus `util`, `schema`, `types`, `conditions`, `fetchers` and `inputs`. The
rewrite-test fixture also emits into `federated`, `multitenant`, `multischema` and
`multischemamutation`, together 221 of the fixture's 807 generated files, and no run ever deletes
anything from those four.

So a consumer who removes a federated entity, drops a tenant-scattered type, or renames a
multi-schema mutation keeps the old resolver on disk indefinitely. It still compiles, it still
resolves against the generated schema, and nothing reports it. The failure is silent in the worst
direction: the stale unit is a plausible-looking generated file, so a reader who finds it has no
reason to doubt it.

Found by the cross-cutting clean-removal case in `GeneratorDeterminismTest`, which plants an orphan
in every subpackage and asserts the sweep removes it. That case deliberately asserts only over the
declared-owned subpackages today, so that fixing this reads as a fix rather than as a regression;
widening it to every emitted subpackage is part of this item.

## What a plan has to settle

* **Whether the list should exist at all.** The alternative is to sweep every subpackage the run
  emitted into, computed from the run's own emitted set rather than declared ahead of it. That
  cannot fall behind a new emitter, which is exactly how the current list fell behind. The reason to
  keep a declared list is the other half of the clause, that the sweep must not delete a consumer's
  hand-written code sharing the output package, and a computed set only protects a subpackage no
  emitter has ever written into.
* **What protects hand-written code under either shape.** `sweepDoesNotDeleteFilesOutsideOwnedSubpackages`
  pins the current answer against a two-type SDL. Whatever replaces the list owes an equally
  explicit statement of what a consumer may safely put in the output package.
* **Whether an emitter can register its own subpackage**, which would make the ownership question
  local to the emitter that creates the exposure rather than global to a list nobody updates.
* **Whether the gap is worth a migration note.** A consumer upgrading past the fix will see files
  deleted that previous versions left alone, which is the correct behaviour arriving late and still
  worth saying out loud.

## Retired vocabulary

None yet; a plan that removes the declared list retires `OWNED_SUBPACKAGES`.
