---
id: R543
title: "Corpus asserts a coordinate's facts and commands, not one verdict triple"
status: Backlog
bucket: testing
theme: testing
depends-on: []
created: 2026-07-26
last-updated: 2026-07-26
---

# Corpus asserts a coordinate's facts and commands, not one verdict triple

`@classified` asserts one three-axis verdict per coordinate: a `source`, a single `operation` arm token, and a `target` wrapper plus outer shape. R333's *Corpus assertion shape* question resolves that this generalizes: the directive should assert a `source` fact, a `target` fact, and a **set** of `operation` rows, each independently assertable, because `operation` is the one genuinely multi-valued relation in the model (collapsing it into a single slot is the 1NF fault that multiplies the leaf cross-product in the first place). A coordinate that selects, joins, paginates and filters is four operation rows today squeezed into one arm token, so the corpus can currently name only the arm the leaf happens to expose.

The consequence is a ceiling on what the corpus can be the spec for, and it is the reason the classification truth table still holds ~149 slot-asserting rows. Those rows assert `joinPath()`, `resolvedTable`, `sourceKey()`, `returnType().wrapper()`, `columnName()` and friends, and they stay in `GraphitronSchemaBuilderTest` because the corpus vocabulary has nowhere to put them. That exclusion is a property of the vocabulary, not a law: under R333 each of those *is* a fact with its own natural key and its own walk, and R333's seam-placement rule already treats corpus assertability as a first-class design criterion (rule c, taken in its looser reading: seam wherever the corpus might want to assert). Widening the directive vocabulary to the fact set is what lets the corpus absorb slot detail instead of quarantining it, and it removes the "verdict, not slots" split that currently divides one classification spec across two mechanisms.

Part of this is available before the leaf zoo dissolves. Several facts are already materialized as fact-shaped records that a widened directive could read directly: `JoinStep.Hop` / `On` (R438), `TableExpr` including `RoutineCall` (R435), `PivotSpec` (R501), `NodeMetadata`. Others wait on the emit re-platforming (R314 shipped the reentry family as the first one driven by the model). Spec should decide how much to land against today's accessors versus how much to hold for the facts, and pick the directive shape: a repeatable per-operation directive, a set-valued argument, or a separate `@triggers` sibling. Also due at Spec: what the coverage obligation becomes when it can no longer be stated over sealed leaf classes, since `ClassifiedCorpus.coveredLeaves()` and `VariantCoverageTest`'s corpus check are both defined over `Class<?>` leaves (see the fourth-reader note in R333's consumers section, which owns the re-sourcing requirement this item inherits).

## Both halves, one altitude rule

The facts are R333's front half. The back half is the method graph, and under the functional-core /
imperative-shell cut R333 draws (thread B: the core decides the entire emit, the shell renders and never
assembles, and commands must be complete) the back half is equally declarative data: a set of committed
method commands plus the edges that close over them. So the corpus's natural reach is both halves of one
coordinate, and the two want the same treatment rather than two mechanisms.

This matters because emit behaviour is currently assertable only two ways, both bad for a spec: code-string
matching on generated bodies, which the project treats as an anti-pattern and R387 exists to migrate off,
or end-to-end through the compilation and execution tiers, which answers "it compiles and runs" but never
"this coordinate emits these methods and wires them to each other". A command set is data produced by a
pure function over the SDL, so it is assertable from a fixture directive with no javac in the loop. That is
the missing rung between the pipeline and compilation tiers.

**Precondition: a command is pure data, in ordinary Java records.** A command carries no vocabulary from the
emit library. The moment a command holds a `CodeBlock` (or a `TypeName`, or any other javapoet type) it stops
being a decision the core made and becomes output the core already rendered, which defeats the point twice
over: the core has reached into the shell's job, and the command is no longer comparable, printable, or
assertable as data, only as text. This is the dual of R333's thread-B law. Thread B says the shell makes no
decision the core could have made; the same cut requires that the core render nothing the shell should
render, and only the second half is currently at risk.

It is at risk concretely, so this is a precondition rather than a principle. `RowsMethodBody` is a sealed
hierarchy in the *model* package whose every permit carries an opaque `CodeBlock content()`, constructed by
`SplitRowsMethodEmitter` and `TypeFetcherGenerator` and consumed by `RowsMethodSkeleton`: emitters on both
ends, so it is a shell-to-shell handoff misfiled as a model type, with the boundary inverted (the shell owns
the declaration scaffolding while a model type carries pre-rendered body text). More broadly the model's
Java-type vocabulary *is* javapoet's, with `ClassName` in 21 model files and `TypeName` in 20, plus
`ParameterizedTypeName` / `ArrayTypeName`, reaching as far as author-facing rejection text through
`TypeNames.simple`. A plain type reference record would serve every one of those slots. `MethodCommand` is
the counter-example that shows the target shape: four strings, no emit vocabulary, fully comparable.
Whether that purge lands here or as its own item is a Spec-time call; the command half of this item cannot
assert commands as data until it does.

**The altitude rule, stated once and inherited by both halves.** Even as pure records, command completeness
and command assertability pull against each other: a command complete enough that the shell decides nothing
carries the whole structure of a method body, and a directive that spells that out is not a spec anyone
reads. The resolution already exists on the front half, where `DimensionTuple` deliberately asserts the
`Operation` *arm token* and not the arm payload, on the stated grounds that the payload is not reconstructible
from a directive and its completeness belongs to the tiers that compile and run the result. The constraint is
payload size and structure, not the vocabulary the payload is written in. Apply the same rule one layer down:

| layer | the corpus declares | who owns the rest |
|---|---|---|
| facts (front half) | `source`, `target`, the coordinate's operation set | slot payloads stay with the pipeline tier |
| commands (back half) | which methods the coordinate commits, and the edges they close over | bodies stay with the compilation and execution tiers |
| bodies | nothing | javac and PostgreSQL |

Both halves assert identity and wiring, never payload. That keeps one answer to "what is a declarative test
for here" instead of one rule per half, and it keeps the directive readable as a spec.

**Phasing.** The fact half can start now (several facts are already materialized records, as above). The
command half is gated on commands carrying more than a name: today's `MethodCommand` is a name-authority
record (coordinate, unit, type path, method name) and the closure oracle is name-level, which is enough to
assert *that* a coordinate commits a method and that every callee resolves, but not what kind of method it
is. R541 is spending that registry as it lifts the root query unit, so the command half should follow its
lead rather than mint a parallel notion of command. Do not block the fact half on it.

Two knock-on effects, worth weighing at Spec because they argue for the item's size. R25's coverage baseline
puts the emitters at the bottom of the generator source (`JooqRecordInstantiationEmitter` 40.7%,
`FetcherEmitter` 50.2%), which is what you would predict when emit behaviour is reachable only through the
whole pipeline; asserting commands moves that coverage debt to a tier that can reach it directly. And R387's
migration off code-string assertions gets a destination: a command assertion is what those tests were
reaching for when they matched on generated text.

Out of scope: the rejection rows (their own mechanism, filed separately) and the input-side rows. Not a
re-litigation of R333's model or of the FCIS cut; this item consumes both. Not the closure oracle's
extension to further emit families, which rides with the families themselves.
