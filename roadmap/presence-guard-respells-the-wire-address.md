---
id: R903
title: "The authored presence guard re-spells the wire address instead of carrying it"
status: Backlog
bucket: cleanup
priority: 4
theme: model-cleanup
depends-on: []
created: 2026-09-01
last-updated: 2026-09-01
---

# The authored presence guard re-spells the wire address instead of carrying it

Two records now hold the same three components with the same two invariants. The model's
`WireAddress` carries `(outerArgName, path, list)` and rejects a blank argument name and an empty
path. The command layer's `PresenceGuard.FieldPresent` carries `(outerArgName, path, list)` and
rejects a blank argument name and an empty path. `ConditionCommands.presenceOf` is the whole
distance between them: it reads the three components off one and passes them to the other.
`WireAddress` has exactly one field (`FkTargetConditionFilter.field()`), one construction site
(`FieldBuilder`'s reference-field arm) and one reader (`presenceOf`), so it exists only to be
copied one step later into an identically shaped record.

Why this is worth an item rather than a shrug: an invariant spelled twice is two places a fix has
to land, and a component added to the wire address later reaches the guard only if someone
remembers the second record. The tree already states that principle at
`WireMapChain`'s javadoc, about this very descent ("the shape is one decision about generated code
and not two, and two spellings of it are two places a fix has to land").

The copy is justified in javadoc by two claims that do not hold against the tree:

- `presenceOf`'s javadoc calls it "the same produce-time narrowing `ReachPath#narrow` performs".
  `ReachPath.narrow` narrows a *type*, `List<JoinStep>` down to `List<JoinStep.Hop>`, and refuses a
  step the reach cannot carry; it keeps the model's own `JoinStep.Hop` and `On` values. Likewise
  `AuthoredMethodRef` is a real projection: it drops the reflected reference's signature facts and
  keeps the address. `presenceOf` drops nothing and refuses nothing.
- `PresenceGuard.FieldPresent`'s javadoc says the address is "narrowed off the model's
  `WireAddress` at production", and `presenceOf` says the point is that "the renderer spells a
  traversal without reading the classified model". `ConditionGlueRenderer` already imports
  `rewrite.model.CallParam` and `rewrite.model.CallSiteExtraction` and reads both, and command
  records already carry model value types across this same seam: `ColumnTerm` carries a
  `ColumnRef`, `ArgBinding` carries a `CallParam`, `CallWrap` carries `JoinStep`, `On`,
  `OrderBySpec`, `ParentCorrelation` and `TableRef`. There is no boundary here for the copy to be
  respecting.

## What to do

Have `PresenceGuard.FieldPresent` carry the `WireAddress` it is made from, so the guard's shape is
the address's shape by construction rather than by coincidence, and `presenceOf` becomes the
wrapping it actually is. That is the house shape at this seam, the one `ColumnTerm` and
`ArgBinding` already use. Delete the duplicated validation with it. Then correct the two javadoc
paragraphs above so they state what the record does, and drop the `ReachPath.narrow` analogy
rather than repairing it.

If the alternative is preferred, that the command layer must not name a model record here, then the
justification has to be a boundary that exists: state which rule the surrounding command records
break, or accept that they set the convention. What should not survive is a copy defended by a
narrowing that does not narrow.

## A smaller adjacent question, for the same pass

The generated arm elides its guard when the term is proven non-null (`appendGuardedAnd` emits the
bare statement when `ColumnTerm.nonNull()` holds). The authored arm has no such elision: a filter
field that is statically non-null under a statically non-null argument still gets a runtime
presence test that is always true. The fact needed to elide it is `effectiveNonNull && rf.nonNull()`,
computed two lines below the `WireAddress` construction site and already passed to the implicit body
param. Emitting the always-true test is harmless, so this is tidiness, not correctness. Note that
`Predicate.Authored`'s compact constructor currently forbids the honest datum for that case: a
non-empty reach must carry `FieldPresent`, so a statically-present field cannot say `Always`.
Whoever picks this up decides whether the invariant should read "a non-empty reach carries a
presence decision" instead, or whether the elision belongs in the renderer's spelling of a
`FieldPresent` guard it can prove constant. Do not weaken the invariant without replacing what it
buys, which is that an unguarded generator-minted `EXISTS` is unconstructable.

## Provenance

Found at the In Review to Done gate on the item that introduced both records, the optional-filter
`@reference` join fix. Nothing here is a defect in that item's behaviour: the emitted guard is
correct, the full reactor is green, and the fix's own pins are load-bearing. This is the shape of
the data the fix carries, filed rather than bounced.
