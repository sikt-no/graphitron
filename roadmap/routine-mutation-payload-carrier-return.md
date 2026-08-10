---
id: R618
title: "Routine mutation: admit a payload carrier return with a typed errors channel"
status: Backlog
bucket: feature
priority: 3
theme: routine
depends-on: []
created: 2026-08-10
last-updated: 2026-08-10
---

# Routine mutation: admit a payload carrier return with a typed errors channel

A `@routine` write on `Mutation` can only return the terminus `@table` type directly. Every
other return shape is rejected by `RoutineDirectiveResolver.resolve` with `@routine requires a
@table-annotated return type`, which fires before catalog resolution and therefore before any
chain-level verdict. The rejection is correct for the shape the resolver models (the routine
node's result table must resolve against a table-bound element type), but it also blocks the
return shape most authors reach for on a fallible write: the payload carrier.

Both other write families already admit that carrier. `@mutation` DML resolves it through
`BuildContext.scanStructuralDmlPayload` (a payload Object with exactly one non-errors data field
whose element is `@table`-bound, plus an optional errors-shaped field), landing
`MutationDmlRecordField` with an `ErrorChannel.RouterDispatched`; the `@service` family resolves
the sibling `scanStructuralServiceCarrierPayload`. `@routine` resolves neither, and
`MutationRoutineWriteField.errorChannel()` is pinned `Optional.empty()` with the reason stated as
the terminus rule: the return "is the direct terminus `@table` type, never a payload carrying a
typed `errors` field". So the two halves are one gap, not two. Admitting the carrier is what makes
a typed channel representable on a routine write at all; without it a routine that raises for a
business reason reaches the client through the redacting catch arm as `An error occurred.
Reference: <uuid>`, and the author's only route to a typed `errors` list is to abandon `@routine`
and hand-write the call behind `@service`.

The authoring shape that motivates this (from a consumer schema) is an access-controlled create:

```graphql
type OpprettFeideBrukerPayload {
  feideBruker: FeideBruker      # @table-bound element, the carrier's data field
  errors: [OpprettFeideBrukerError]
}

type Mutation {
  opprettFeideBruker(input: OpprettFeideBrukerInput!): OpprettFeideBrukerPayload
    @routine(name: "opprett_feide_bruker", argMapping: "...")
    @reference(path: [{table: "feide_bruker"}])
}
```

Note what the RLS setting does to the value of each half: the caller cannot read the row it just
created (the read policy requires a role assignment the new row does not yet have), so the
post-commit re-read legitimately returns nothing and the data field is always null. The entire
informational content of the response is the errors list. A shape where the errors channel is the
point, and the data field is a structural placeholder, is exactly the one the current pinning
refuses.

Scope sketch for Spec:

* Whether the carrier scan generalises to a third `CarrierFamily` arm (the enum's javadoc already
  reserves this: "a third family extends the enum and gets exhaustiveness prompts at both policy
  sites") or whether a routine carrier is close enough to the DML family to reuse it. The two
  coupled policies to decide are the forbidden-directive set on the data field and the ID-element
  wrapper admission.
* Where the return-shape unwrap belongs. The resolver is position-agnostic by construction and
  the terminus rule is already a caller-side chain-level verdict, so the carrier unwrap most
  plausibly happens at the Mutation classifier seat (`classifyMutationRoutineChain`) before the
  chain walk, with the resolver continuing to see a table-bound element type. That keeps the
  resolver's shape invariant intact rather than widening it.
* The leaf: whether `MutationRoutineWriteField` gains a carrier arm plus an
  `ErrorChannel.RouterDispatched`, or a sibling leaf carries the payload shape the way
  `MutationDmlRecordField` sits beside `DmlTableField`. The `errorChannel()` pin and its stated
  reason retire either way.
* Step 2 of the write emit is unchanged in principle (the post-commit re-read still anchors on
  hop 0's captured key and projects the terminus type), but it now feeds the carrier's data field
  rather than the field's own return, which is the same relationship the DML carrier's data field
  already has to its follow-up SELECT.
* The zero-row re-read is a real case, not a defect, once the carrier lands: a committed write
  whose post-commit read returns nothing must produce a null (or empty-list) data field alongside
  a populated errors list, not a field error. Pin it.

Adjacent, deliberately not folded in: the routine-kind axis (procedures, scalar and void
routines, and the single-node Mutation `@routine` with no `@reference` hop) is
`roadmap/routine-write-result-shapes.md`. That item asks which routine kinds can back a write;
this one asks what the field may return once one does. They meet if a consumer's routine turns
out to be void or scalar, since a void routine plus a payload carrier has no data field to fill,
but the return-shape work stands alone over the table-valued kind already shipped.

