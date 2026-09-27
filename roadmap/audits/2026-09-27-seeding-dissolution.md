# What the seeding dissolution found

Evidence behind the graph branch "Seeding dissolves, and the fixture goes with it", and behind the
two branches beside it. Filed as an audit for the reason the earlier ones state: the item's file dies
at Done and the figures have to survive it. The item states the claims; this holds what they rest on.

## Seeding was the only option, and stopped being it

`SeededStore` exists because the gatherers lived in `graphitron`, where a test had no way to run a
capture: writing rows was the only way to put facts in front of a rule. The gatherers are in
`graphitron-model` now and the corpus and its `@expectEquals` runner moved with them, so a test can
state SDL and read the relations back. What is left is fallout, a helper of a few thousand lines and
most of the module's test classes reaching for it.

## Seeded fixtures assert over states capture cannot reach

Converting the macro expansion to a derivation broke six seeded fixtures, and every one was asserting
a state no capture produces. Five seeded a carrier whose *authored* field was already the connection
type, which no transcription holds because the rewrite is derived. One expected a carrier's row
without the `nodes` and `node` its expansion mints. A seventh counted `graphql_field` to assert a
per-field invariant a relation states over the emitted population.

None of the seven could fail before, which is the point rather than an aside: seeding writes both
halves of a claim and nothing checks the halves against each other.

## The code half is worse, and is not old fallout

`SeededStore` seeds `code_` too, and that arrived on 2026-09-20 with the commits introducing the
family. What makes it worse than the SDL half is not a missing corpus. All three strata have one and
two are modules the tests already depend on: the fact documents, `graphitron-sakila-db` (a real
jOOQ-generated catalog), and `graphitron-sakila-service` (ninety-six real Java classes). Extending
any of them is adding a class, a table or a converter to a module that already compiles.

A seeded `code_method` row is a claim about a method; a method in `sakila-service` is one. The
failure mode is the worst available, generated code calling a method that does not exist, failing in
a consumer's build rather than ours, and it is self-confirming: a fixture seeds a method name and
asserts the emitted text contains it, so the test cannot fail for the reason it exists.

## What the conversion cost in test count, and why it lost no coverage

Seven documents replaced four test classes at forty fewer `@Test` methods. The count falls because a
document states one population where a class stated one row per case: sixteen federation cases became
three documents, and the whole-population claim is stronger than the sixteen, since a row nobody
declared fails the same run.

What makes the drop safe rather than merely explained is `theDocumentsDeclareBlocks`, which fails if
the folder stops resolving, a glob stops matching, or any block declares no rows. That was the one
way this arc could have deleted its own coverage silently, and it is closed by a test rather than by
care.

Every document was negative-tested: one expectation broken, the failure confirmed, the expectation
restored.

## Absence is not assertable as an empty block

A document asserting that nothing is derived cannot say so with a block of no rows; the runner
refuses one, on the grounds that it asserts nothing. It is stated instead over a relation that does
hold rows: a synthesized federation key arrives in `intent_federation_key` with no ordinal, so a
whole-population claim that omits one is the assertion. Flipping a link to the federation spec breaks
both such documents, which is what makes the claim mean something.

## One thing the seeded fixture had wrong

The seeded federation key carried `resolvable = true` because the helper passed `true`. A real
capture of `@key(fields: "title")` records a null, the author not having spelled it, with the default
applied downstream. Seven fixtures agreeing with each other is what let that stand.

## The classpath re-read, and what stamping a directory costs

Measured 2026-09-25 on `graphitron-sakila-example`.

| what | before | after |
|---|---|---|
| `code_method` rows written per graph | 16698 | 0 when unchanged |
| catalog rows rewritten per graph | 706 | 0 when unchanged |
| walking a class root for a stamp | n/a | 66 to 82 ms warm, 1904 files |
| what the walk skips | n/a | about nine seconds |

The old reasoning was that a directory changes on every compile so the walk cannot pay. The first
half holds and the second does not, which is the whole of why the skip is now possible: 66 to 82
milliseconds against nine seconds. `FactCaptureAgreementTest` asserted the old conclusion directly
and now asserts its inverse.

## What a false classpath origin cost

Both halves of the classpath corpus were handed to capture as `Origin.PROJECT`, this module's own
output, which is false twice: neither half is ours and both have coordinates. The lie was
load-bearing rather than cosmetic. It removed the reactor limit the `@service` arm leans on, and nine
`graphql.scalars` methods became nameable at `@service`, among them `newAliasedScalar`,
`newRegexScalar`, an enum's `valueOf` and `values`, and four `LocalTimeCoercing` coercion methods.
Marked `REACTOR` and `DECLARED` with their coordinates: none.
