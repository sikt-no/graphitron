---
id: R719
title: "A mutation @routine carrier is decided by the payload shape, not by whether @reference was written"
status: Backlog
bucket: architecture
priority: 3
theme: routine
depends-on: []
created: 2026-08-18
last-updated: 2026-08-18
---

# A mutation @routine carrier is decided by the payload shape, not by whether @reference was written

On a `Mutation` root, `@routine` has two shapes. The **chained** form returns the table type the
chain lands on. The **carrier** form returns a payload wrapping one data field beside an error
channel, and the data field owns a post-commit re-read. Two places decide which shape a field is,
and both decide it the same wrong way: by whether the author wrote `@reference`.

That is a syntactic accident standing in for a question about the returned type's shape, and it has
a visible cost. Writing `@reference` where the path it names is the implicit one should be
discouraged, since the manual already says the explicit spelling "stays legal and resolves
identically; writing it is optional, not wrong". At this one position the rule inverts: the
optional spelling is the only way to say "not a carrier", and omitting it is not read as "the path
is implicit" but as "this is a carrier".

## Where the decision is made twice

**The classifier.** `FieldBuilder.classifyMutationField`'s `@routine` fork reaches the carrier scan
when the field carries no `@reference`, and lands a typed `Deferred` when the scan declines. Its
message names the shape it could not place: "no `@reference` hop and no payload data field to carry",
filed with void, scalar, OUT-parameter binding and non-carrier Object returns as a capability gap.

**The store.** `intent_routine_return_binding` excludes exactly one seat from its population, and
spells it as the pair `(the field is on a mutation root) AND (the field carries no @reference)`. Its
own comment already says this is a stand-in: the exclusion "names that seat rather than the carrier
because the store holds no carrier fact yet; the seat is the classifier's own fork ... and it
narrows to the carrier itself the day a carrier relation lands."

## Why now

That day has arrived. `intent_carrier_data_field` answers the shape question directly, deriving
"this OBJECT type is a payload wrapping one data channel beside an error channel" from the schema
rather than from a directive, and `roadmap/routine-composition-surface-from-facts.md` added
`intent_carrier_routine_hop` on top of it. So the store's exclusion can key on the returned type
being a carrier, and the classifier's fork can ask the same question, leaving `@reference` to mean
what it means everywhere else: a path.

## What this unblocks

```graphql
type Film { filmId: Int! @field(name: "film_id") }

type Mutation {
    makeFilm(...): Film @routine(name: "...")     # implicit path, no @reference
}
```

Today this classifies as a deferred capability gap and its return type gets no binding. Under the
shape question it is the ordinary root chain it looks like, and the author writes no directive whose
only job is to say what the field is not.

## What to settle at pickup

Whether the classifier and the store move in one change or two, and in which order. The store's
narrowing is observable on its own (a binding row appears where none did), and the classifier's is
what makes the shape generate; landing the store first means a fact with no consumer for one commit,
landing the classifier first means the seat exclusion is briefly wrong in the other direction.

Whether an author writing the superfluous `@reference` should be told. The manual's "optional, not
wrong" is the current rule, and a diagnostic where the written path equals the implicit one is the
natural follow-up. It cannot land first: while the mutation-root case reads absence as "carrier",
the diagnostic would fire on the one spelling that is currently load-bearing.

## Related items

* `roadmap/routine-composition-surface-from-facts.md` derived the carrier facts this reads and left
  this carve-out un-unwired.
* `roadmap/routine-write-result-shapes.md` owns the deferred write shapes the classifier's fork
  files this under, and is where the remaining members of that list stay.
