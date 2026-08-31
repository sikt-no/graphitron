---
id: R883
title: "Five prose sites name the parent-source derivation by its retired boolean"
status: In Review
bucket: cleanup
priority: 4
theme: codegen-correctness
depends-on: []
created: 2026-08-31
last-updated: 2026-08-31
---

# Five prose sites name the parent-source derivation by its retired boolean

When a generated data fetcher reads its parent object, it has to know whether that object
arrived bare or wrapped in the error-channel `Outcome` type. The generator answers that
question once per GraphQL type. That answer used to be a plain boolean local named
`sourceIsOutcome`, threaded as a parameter into each fetcher builder; it is now a sealed value,
`ParentSourceBinding`, that pairs the narrowing statement with the expression the parent is read
from, so the two halves cannot be minted apart.

The boolean local survives at the one site that feeds the producer, so nothing is stale in the
compiler's eyes. But five prose sites still describe the whole derivation by that local's name,
which now points at an input to the real mechanism rather than at the mechanism. A reader
following any of them lands on a boolean instead of on `ParentSourceBinding`, which is where the
prelude/source pairing and the three-arm fork actually live.

**What changes when this lands.** No prose in the reactor describes the parent-source derivation
by the old boolean's name. Where a symbol citation is the right form, it points at
`ParentSourceBinding` and the build checks that it resolves; where the build cannot check one, the
sentence makes its claim without naming a symbol at all. `sourceIsOutcome` survives only as what
it now is: a local variable at the producer's mint site. Nothing changes for a consumer of
graphitron; this is a contributor-legibility item.

## The five sites

Anchored on symbols, since these files move. Each is greppable by the token `sourceIsOutcome`,
which after this item survives only at the live local in `TypeFetcherGenerator.generateTypeSpec`.

- `ChildField.SingleRecordIdField` javadoc, on the `envelope()` sentence ("the same axis the
  table-field sibling's emitter derives as `sourceIsOutcome`").
- `KeyLift.ProducedRecords` javadoc ("handled by the generator at the type level
  (`sourceIsOutcome`)").
- `FieldBuilder.buildPayloadCarrierBatchedTableField` javadoc ("derived at the type level by the
  generator (`sourceIsOutcome`)").
- Two `//` line comments inside `FieldBuilder.classifyChildFieldOnResultType`, on the DML-carrier
  arm ("(sourceIsOutcome)") and the `@service`-carrier arm
  ("(sourceIsOutcome = hasWrapperArmErrors)").

Two sibling prose surfaces are already correct and are the model to match: `SourceEnvelope`'s
javadoc and the matching paragraph in `docs/architecture/explanation/dispatch-axes.adoc`. Both
name `ParentSourceBinding` as the envelope's type-level twin, and `SourceEnvelope` reaches it
with a fully-qualified `{@link}` across the model-to-generators direction, the same form
`ErrorChannel`, `OrderBySpec` and `ReturnTypeRef` already use.

## The javadoc gate covers two of the five, not five

The filed item proposed repointing all five with `{@link}`, "which is javadoc-checked and so
cannot rot the same way". That premise was measured and holds for two sites only.

The `verify`-phase reference gate runs maven-javadoc-plugin with no `<show>` override, so it
inherits the plugin default `show=protected` and never visits package-private or private
elements. Dangling `{@link}` probes injected at three sites and run through
`mvn -pl :graphitron javadoc:javadoc-no-fork@check-link-references` confirm the split
directly: the probes in `ChildField.SingleRecordIdField` and `KeyLift.ProducedRecords` (public
records) were both reported as `reference not found` errors in the same run, while the probe in
`FieldBuilder` was accepted silently. `FieldBuilder` is a package-private class and
`buildPayloadCarrierBatchedTableField` is a private method, so the whole file is outside the
gate's element set. The two `//` line comments are outside javadoc entirely, where `{@link}` is
inert text.

This splits the item into two remedies rather than one applied five times, and the split is
principled. At the two model sites the `{@link}` is right for a second reason the filed item does
not claim: a model leaf pointing at its generator-side twin is exactly the consumer-to-producer
linkage the development principles say to record with a build-checked `{@link}` when the type
system cannot carry it.

At the three `FieldBuilder` sites there is no honest symbol citation available. Writing
`{@code ParentSourceBinding}` there would swap a stale unchecked name for a fresh unchecked name,
structurally the same artifact rotting by the same mechanism the next time the producer moves. The
third honest form the principles allow is intent altitude: say what is true about where the fact
lives, and name no symbol. Read the sentences and the parenthetical turns out to be decoration on
a claim that is already complete without it. "The source envelope (`DIRECT` vs `OUTCOME_SUCCESS`)
is derived at the type level by the generator, not carried on the key" states the whole
load-bearing fact, survives any rename of the producer, and needs no guard to keep it true.
Widening `FieldBuilder`'s visibility to buy gate coverage is not the alternative: the class is
package-private because that is its correct scope.

## Implementation

**Repoint the two gate-covered model sites.** `{@link
no.sikt.graphitron.rewrite.generators.ParentSourceBinding}`, fully qualified and import-free, per
the `SourceEnvelope` precedent. Both sentences already say the right thing about the axis; only
the name at the end of them is wrong.

**Delete the parenthetical at the three ungated sites.** No replacement symbol. The
`buildPayloadCarrierBatchedTableField` javadoc and the two `classifyChildFieldOnResultType`
comments keep their claim that the envelope is derived at the type level by the generator and not
carried on the key, and lose the `(sourceIsOutcome)` / `(sourceIsOutcome = hasWrapperArmErrors)`
gloss. The gloss is the only part that was wrong, it is the only part that can go stale again, and
nothing downstream of these comments needs a name to navigate by: a reader who wants the mechanism
follows the sentence to the generator and finds `ParentSourceBinding` there, now correctly
cross-referenced from the two model leaves.

**Correct `ParentSourceBinding`'s own javadoc.** It locates the predicate as "computed once per
type at `TypeFetcherGenerator.generateForType`". The computation is in `generateTypeSpec`, which
`generateForType` delegates to and which is also reached directly on the nested-type path, so the
sentence names the wrong method and undercounts the entry points. Because the reference sits in
`{@code}` rather than `{@link}`, the gate cannot see it: this is a sixth instance of the same
defect, in the successor type's own javadoc, and it should not survive an item whose subject is
prose pointing at the wrong symbol. Repoint it at `{@link TypeFetcherGenerator#generateTypeSpec}`
(same package, so the short form resolves) and drop the "once per type" claim to the method that
actually holds it.

## Tests

No new test, and no new guard. The item is prose only, so what demonstrates completion is a
grep plus a gate.

At the Done gate, `grep -rn sourceIsOutcome --include=*.java` returns exactly two hits, both the
live local in `TypeFetcherGenerator.generateTypeSpec`, which stays: its declaration and its single
use. Every other Java occurrence is gone. Three mentions outside Java survive on purpose and are
not the grep's business: this item's own file and R886's, both deleted at their own Done, and the
`roadmap/changelog.md` entry that records the retirement lineage, which is the permanent home for
exactly that. Of the sites that were repointed
rather than emptied, both are now build-enforced, because the javadoc
reference gate resolves the two model `{@link}`s and the corrected
`{@link TypeFetcherGenerator#generateTypeSpec}` on every `verify`. That gate firing on a future
rename is the whole guarantee the item is buying at those sites; at the other three the guarantee
is that no symbol name is left to rename.

Verification is the full `mvn install -Plocal-db`, since the diff touches main sources.

**Re-probing the gate needs a cleared `apidocs` first.** Measured during implementation, and worth
writing down because it silently inverts the answer. maven-javadoc-plugin skips regeneration when it
judges the output up to date, logging `Skipping javadoc generation, everything is up to date` at
debug only, which the gate's `quiet` setting hides. A comment-only edit does not defeat that
judgment, so a probe run over a populated `graphitron/target/reports/apidocs` returns green whatever
the probe says, and reads exactly like "this site is not covered". Clear the directory before every
probe and before the verification build. On cleared runs all three of this item's claims reproduce:
the dangling probes in `ChildField.SingleRecordIdField` and in `ParentSourceBinding`'s own class
javadoc both fail the build, a dangling `TypeFetcherGenerator#generateTypeSpecNoSuchMethod` member
probe fails too (so the corrected member link is enforced even though `generateTypeSpec` is
package-private, doclint resolving members the `show=protected` filter excludes from the output),
and a probe in `FieldBuilder`'s private-method javadoc is still accepted silently.

## Why no registry entry, and no retirement

The tempting move here is to make the fix permanent: inline the single-use boolean at
`TypeFetcherGenerator.generateTypeSpec` so `sourceIsOutcome` drops to zero main-source
identifiers, then register it in `RetiredVocabularyGuardTest.REGISTRY`, whose prose scans reach
every habitat the javadoc gate cannot (comments and javadoc at any visibility, string literals,
authored `.adoc`, fixture SDL and DDL). This item deliberately does not do that, for three
reasons.

The registry's stated entry bar is demonstrated recurrence, "a term enters the registry when an
audit finds it surviving a cleanup, not at every rename", and the existing entries carry their
counts in comments ("two consecutive sweeps", "three sweeps") because the count is the
justification. This term has survived zero post-scrub sweeps, because the scrub is what this item
is. One observation recorded twice inside a single Done gate is one observation.

Worse, installing the guard in the same commit as the scrub makes the bar permanently
unobservable for this term: nobody can later find that the scrub failed to hold, because the guard
would have prevented the evidence. Shipping the scrub alone is what makes a future registration
honest.

Third, every existing registry entry names something that was already dead. Inlining a live local
in order to qualify a token for the registry inverts that arrow, and it is the wrong justification
for a change that deserves a better one. The duplicated derivation that inline would half-address
is filed separately below.

Deleting the parenthetical at the three ungated sites is what removes the recurrence risk the
guard was wanted for. There is no symbol name left there to go stale.

## Follow-up filed separately

`ParentSourceBinding.of` calls itself "The producer", but the two facts it needs are spliced by
hand at both of its call sites: `parentTable != null ? SourceShape.Table : SourceShape.Record` and
`FetcherEmitter.hasWrapperArmErrors(fields)` appear identically in
`TypeFetcherGenerator.generateTypeSpec` and in `FetcherRegistrationsEmitter.parentSourceBinding`,
whose javadoc is a hand-maintained assertion that the two copies agree. That is a derived fact
maintained apart from its source, and `roadmap/changelog.md` already records it as observed and
non-blocking at the same gate that produced this item. Collapsing it (having `of` take the table
backing and the field list, and delete both splices) drops the `sourceIsOutcome` local as a
consequence rather than as an objective, which is the honest route to the retirement this item
declines to take. It is a main-source refactor with two call sites and wants its own reviewer, so
it is filed as R886 rather than bundled here.

## Context for a future reader of these comments

All five sites are restating one fact, and it is worth naming once so the next audit knows what it
is looking at. The type-level derivation (`FetcherEmitter.hasWrapperArmErrors`, keyed on the
`WrapperArm` transport) and its leaf-level twin (`FieldBuilder.carrierPayloadHasErrorsField`,
which mints `SourceEnvelope`) answer the same question on different populations.
`ParentSourceBinding`'s javadoc argues they agree by construction; no test is named for that
agreement today, so it is review-only. That is why these leaves want a cross-reference to the
generator at all, and it is not this item's job to change.

## Not in scope

No user-visible surface, so the user-docs first-client draft does not apply. No behaviour change
at all: every edit is prose. Nothing is retired, so there is no `Retired vocabulary` section and
the Done gate's retirement sweep does not apply. `FieldBuilder`'s visibility stays as it is, and
the `sourceIsOutcome` local stays.
