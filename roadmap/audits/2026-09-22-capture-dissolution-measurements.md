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


## Build-side evidence, contributed from outside the item (2026-09-08)

Added by R733's fourth measurement pass, which set out to ask where the build's wall clock goes and
arrived here. Recorded as evidence for this item's owner and its Done gate to use or dispute, not as
a change to its plan. Figures taken at `7a3fae6e` on one 4 vCPU 15 GB sandbox.

**This item's subject is also the largest single regression in our own build.**
`FixtureWarningsGateTest` is one full-fixture generator run and nothing else, and it is the
reference instrument R733 has used across three passes. In isolation it was **2.862 s** on
2026-08-20 and is **26.28 s** now. Both confounds were ruled out: the generator's input grew 13%
(`schema.graphqls`, 4203 to 4742 lines), and the machine is not the cause, DDL cost per statement
being 0.066 ms then against 0.062 ms best now. What grew between the two measurements is the fact
model, views 71 to 120. So the cost tracks the view count rather than the schemas fed to it, which
is this item's mechanism observed from the build side. The build pays it six times over, and
`graphitron-sakila-example` is 223 s of a roughly 1080 s build.

**A second cost of the register, which this item has not counted and which it removes for free.**
`GraphitronModelStore` runs `MaterializeDependencies.populate` inside every boot. That walk starts
at each `meta_materialize` row, parses each stored view definition it reaches with jOOQ's parser,
and now reaches all 120 views. It has gone from **8.41 ms to 139.7 ms** and is **32% of a 436 ms
boot**, up from 6% of a 138 ms one. With the register dissolved the walk has no roots, parses
nothing, and the step goes to zero. The build performs on the order of a thousand boots, so this is
worth roughly two minutes of build CPU on top of the read-side win, and it is paid by every
consumer at every store open as well.

Worth stating as a check rather than a credit: **the Done gate should confirm the step actually
reaches zero rather than assume it.** The measurement is six lines, timing
`MaterializeDependencies.populate` directly against a booted store.

**One piece of counter-evidence, offered because it argues against a claim this item could
otherwise be read as making.** The boot has two halves and only one of them goes. The DDL half is
283.5 ms of the 436 ms, and this item's own remedy pushes it upward: it replaces registrations with
stored keys and indexes, and while it has been in progress the schema has gone from 0 to 22
`CREATE INDEX`, from 148 to 190 tables, and from 2138 to 3278 statements. That is a fair trade if
the read-side win is as large as the figures above suggest, and it is almost certainly the right
trade. It is recorded so that "the boot gets cheaper" is not inferred from "the register goes".

**Two things this item does not absorb, recorded so they are not expected of it.** First, store
size: the real 34 MB build store is 99.3% classpath census rows and the twenty registered targets
hold **25 rows of 251,807**, so dissolving the register changes the store's size by nothing
measurable. That belongs to R762, and through it to R937, compaction on close costing 1.6 s per
close on a store that size. Second, boot *count*: R768's roughly one thousand boots per build are
unaffected by what a boot contains.
