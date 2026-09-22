# What the capture dissolution measured

Measurements taken between 2026-09-11 and 2026-09-22 while dissolving `meta_materialize`, the
derivation gatherer and the duplicate capture producers. Filed as an audit for the reason the
predecessor audit states: an item's file dies at Done, and the numbers have to survive that. The
item states the plan; this holds the figures the plan was decided on.

Each section says how it was measured and whether the instrument was kept. Where an instrument was
temporary, that is stated, because a number nobody can reproduce is worth less than one somebody can.

## The entry stratum costs more to write than everything else the store holds

Measured 2026-09-12. JFR at `settings=profile` over `mvn graphitron:capture` on
`graphitron-sakila-example`, plus a temporary probe timing each write mechanism and counting its
rows. **Neither instrument was kept**; nothing in the tree depends on the probe.

| mechanism | rows | time | per row |
|---|---|---|---|
| `FactSink` generic arm, a bind batch | 64589 | 1.6 s | 25 us |
| `FactWrites`, a written bind batch | 21550 | 0.8 s | 35 us |
| `RowChunks`, a multi-row `VALUES` upsert | 7194 | 8.7 s | 1200 us |

7194 entry rows cost more wall clock than the 86139 rows of every other family put together, at
forty times the per-row cost. The hot callers ranked: `SdlEntries.valuesOfDepth`,
`fieldDefinitions`, `appliedArguments`, `fieldDirectives`, `fieldArguments`, `typeDeclarations`,
then `GraphitronFieldEntries.bindings` and `JooqFactCapture.columns`.

The consequence that decided sequencing: the stratum the arc was growing is the expensive one, so
the number gets worse as the migration proceeds rather than better.

## A recursive view is re-run once per driving row

Measured 2026-09-13, and the sharpest single result on the arc.

`intent_node_id_decode_hop` was the most expensive thing a capture did: **6.2 seconds of an 8.8
second materialization pass, to produce 38 rows.** Not the rows and not the inputs. Every input read
fast alone, `intent_condition_method_route` in a millisecond for three rows and
`intent_node_id_decode_endpoint` in none for 84. Composing them cost 529 milliseconds.

**The mechanism, isolated by swapping two names.** The view left-joined two reference-target views
under a site predicate and read their columns with `COALESCE` over the pair. Both were
`WITH RECURSIVE`, so H2 re-ran a whole recursive walk once per driving row. Replacing only those two
names with tables holding 11 and 5 rows, the same SQL otherwise, took it from **529 ms to 8 ms**. The
argument-site one alone accounted for nearly all of it, 529 to 20, and it is named twice more:
`intent_node_id_instruction_live` fell from 168 ms to 48 and `intent_argument_column_scope_live`
from 11 to 2, both themselves registered sources.

This is the figure behind the rule that a recursive view must terminate on the population the store
can hold rather than the one the subject would have.

## What a registration bought where one was earned

The largest single measured improvement anywhere in the subject was a pair of registrations, taking
two positions from **464.5 s and 82.3 s to 0.2 s and 1.3 s**. Recorded because the item argues
registrations are usually unearned, and an argument that cannot name its own strongest
counter-example is not worth much.

The counter-case, same test read the other way: a candidate whose one reader nothing exercises yet
has every refresh buy nothing.

## The two producers, and the cost of discovering agreement by hand

Measured 2026-09-11 by `SdlWalkIsRedundantTest`, which **was kept**.

`SdlFactCapture` walked a merged registry with graphql-java accessors in hand and wrote twenty seven
relations. `SdlAnchor` derived twenty six of them out of the entry stratum in SQL. Both ran on a mojo
build, the derivation first and the walk second, both upserting, so every anchor row a reader saw was
the walk's and the derivation's was overwritten unexamined.

The test captures one corpus twice, once by each producer, into two stores, and compares the twenty
six shared relations by primary key and then column by column. It excludes the instant, because two
readings are two instants and that is what the column is for, and the generated columns, which are a
function of the row beside them and would report one disagreement twice. The corpus carries every
declaration form, both directive sites that take arguments, an interface, a union, an enum, an
extension and a schema block, so agreement means something.

**The first run returned eight disagreeing relations and the shape of the answer was the useful
part.** Five differed in one column and it was the same column: the derivation numbered ordinals
from one where the walk and the schema number them from zero. Six of `SdlAnchor`'s eleven ordinal
computations already subtracted one and five had not. A sixth defect fell out of fixing them.

What duplication cost was never the wasted work. It was that a column added to an anchor had to be
taught to two writers in two languages.

## Where a gate runs decides when it fires

Measured 2026-09-18.

Six gates ask questions about the store's shape. Three ran in `graphitron-model` at module five of
fourteen, about **four minutes** into a build. Three ran in `graphitron` at module seven, after that
module's whole test tier, about **twenty minutes** in. Nothing about the later three needed a
generator.

The split was an accident of where a field was written, not a decision. `UnregisteredRelationTest`
reached into the generator's module for one thing, a `@Tag` annotation. `DerivedReadCostTest` reached
for that and a six-line factory over a type its own module already owned. Two arms of
`FactCaptureAgreementTest` reached for neither and sat there only because the registration map they
read was declared in that class.

The cost that justified moving them: every one is a gate that fires when the schema changes, which is
to say when somebody is in the middle of changing it. `DerivedReadCostTest`'s reader count moved
twice in one day, once because a sibling session added relations and once because another retired
three. Each time the answer arrived twenty minutes into a build rather than four.

## A measurement that was wrong, and how

Recorded because the method is the lesson, not the number.

A prediction that removing `DerivedReadCostTest` would save 215 s was produced by comparing an
isolated run against a suite run and attributing the difference to the code. The real figure is
about 32 s, established by interleaved A/B pairs: baseline 245, 244, 215, 208, 207 against excluded
261, 176, 175, 185, 173. The spread inside each arm is wider than the effect being measured, which is
why one run of each proves nothing and why the pairs had to interleave.

Two rules came out of it and are now in the `build-profile` skill: a long span is not an exclusive
cost, and one run is not a measurement.
