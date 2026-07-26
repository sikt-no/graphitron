---
id: R543
title: "Corpus asserts the coordinate fact set, not the verdict triple"
status: Backlog
bucket: testing
theme: testing
depends-on: []
created: 2026-07-26
last-updated: 2026-07-26
---

# Corpus asserts the coordinate fact set, not the verdict triple

`@classified` asserts one three-axis verdict per coordinate: a `source`, a single `operation` arm token, and a `target` wrapper plus outer shape. R333's *Corpus assertion shape* question resolves that this generalizes: the directive should assert a `source` fact, a `target` fact, and a **set** of `operation` rows, each independently assertable, because `operation` is the one genuinely multi-valued relation in the model (collapsing it into a single slot is the 1NF fault that multiplies the leaf cross-product in the first place). A coordinate that selects, joins, paginates and filters is four operation rows today squeezed into one arm token, so the corpus can currently name only the arm the leaf happens to expose.

The consequence is a ceiling on what the corpus can be the spec for, and it is the reason the classification truth table still holds ~149 slot-asserting rows. Those rows assert `joinPath()`, `resolvedTable`, `sourceKey()`, `returnType().wrapper()`, `columnName()` and friends, and they stay in `GraphitronSchemaBuilderTest` because the corpus vocabulary has nowhere to put them. That exclusion is a property of the vocabulary, not a law: under R333 each of those *is* a fact with its own natural key and its own walk, and R333's seam-placement rule already treats corpus assertability as a first-class design criterion (rule c, taken in its looser reading: seam wherever the corpus might want to assert). Widening the directive vocabulary to the fact set is what lets the corpus absorb slot detail instead of quarantining it, and it removes the "verdict, not slots" split that currently divides one classification spec across two mechanisms.

Part of this is available before the leaf zoo dissolves. Several facts are already materialized as fact-shaped records that a widened directive could read directly: `JoinStep.Hop` / `On` (R438), `TableExpr` including `RoutineCall` (R435), `PivotSpec` (R501), `NodeMetadata`. Others wait on the emit re-platforming (R314 shipped the reentry family as the first one driven by the model). Spec should decide how much to land against today's accessors versus how much to hold for the facts, and pick the directive shape: a repeatable per-operation directive, a set-valued argument, or a separate `@triggers` sibling. Also due at Spec: what the coverage obligation becomes when it can no longer be stated over sealed leaf classes, since `ClassifiedCorpus.coveredLeaves()` and `VariantCoverageTest`'s corpus check are both defined over `Class<?>` leaves (see the fourth-reader note in R333's consumers section, which owns the re-sourcing requirement this item inherits).

Out of scope: the rejection rows (their own mechanism, filed separately) and the input-side rows. Not a re-litigation of R333's model; this item consumes it.
