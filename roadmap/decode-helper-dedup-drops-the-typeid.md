---
id: R949
title: "Two @nodeId input fields over one table share a decoder that hardcodes one typeId"
status: Backlog
bucket: bug
priority: 2
theme: nodeid
depends-on: []
created: 2026-09-14
last-updated: 2026-09-14
---

# Two @nodeId input fields over one table share a decoder that hardcodes one typeId

## Goal

A schema can expose two node types over the same table and have input fields for both decode the
ids they are given. A *node type* is a GraphQL object type graphitron hands a globally unique id,
declared with `@node`; the id carries a *typeId*, a short tag saying which type the id opens into,
which defaults to the type's own name. An input field tagged `@nodeId(typeName:)` takes such an id
and decodes it back into the jOOQ record its type is backed by. Today, when two of those fields on
one input resolve to the same jOOQ record, one of them rejects its own valid ids and accepts the
other type's ids in their place. That blocks the migration this shape exists to serve: renaming a
node type while the old name stays exposed, so clients move over one at a time.

The minimal pair, two node types over one table:

```graphql
type Person implements Node @table(name: "person") @node(keyColumns: ["person_id"]) { ... }
type OpptakPerson implements Node @table(name: "person") @node(keyColumns: ["person_id"]) { ... }

input OpprettSoknadInput {
    personId: ID @nodeId(typeName: "Person")
    opptakPersonId: ID @nodeId(typeName: "OpptakPerson")
}
```

Both fields decode into `PersonRecord`, so both are emitted as calls to one `decodePersonRecord`
helper whose body passes a literal `"person"` to `NodeIdEncoder.decodeValues`. The consequences are
the two halves of the same mistake, and only one of them is visible: a real `OpptakPerson` id fails
with "Decoded NodeId did not match the expected type for this argument", which reaches the client as
"An error occurred", while a `Person` id passed as `opptakPersonId` is accepted without complaint.
Neither field is doing what its own `typeName` says. The encoding side is correct: the same pair of
types on output fields produces the right ids, which is what makes the input side's silence
expensive, since the schema looks migrated and the mutation is not. Reported by a consumer at
https://github.com/sikt-no/graphitron/issues/548, hit while renaming `Person` to `OpptakPerson` in
fs-plattform/opptak; the output fields migrated and the mutation input field had to be held back.

Note that the reproduction needs no explicit `typeId:` argument. Two node types over one table have
distinct typeIds by default, since each falls back to its own type name, so the collapse is reached
by the ordinary shape rather than by an unusual one.

The decode helper's identity is the thing to fix: a decode body is a function of the record class
*and* the typeId it opens, and the generator keys it on the record class alone.
`InputBeanInstantiationEmitter.collectRecordDecoders` dedups into a `Map<ClassName,
NodeIdDecodeRecord>` keyed by `CatalogRefs.recordClass(rec.table())`, so the first field seen wins
the body and the second silently rides it. The same key runs through the naming namespace
(`FetchersHelperNames.decodeStems`, reached through `decodeSingular(ClassName)`) and through the
three sibling collection sites that feed it: `TypeFetcherGenerator.collectProjectionDecoders`,
`TypeFetcherGenerator.collectParamRecordDecoders`, and the bean-member walk above. A fix that
changes only the one map leaves two helpers wanting one name, so the key has to widen everywhere the
decode family is identified, and the call sites in `ArgCallEmitter`, `ServiceMethodCallEmitter` and
`TypeFetcherEmissionContext` have to pass the wider key rather than a bare record class. The
polymorphic sibling `collectPolymorphicDecoders` already keys on its container name and states in
its javadoc why; the single-type arm is the one that kept the narrower key.

Acceptance is a schema fixture carrying the minimal pair above, asserting the emitted fetcher calls
two distinct decode helpers whose bodies carry the two distinct typeIds, and an execution-tier
assertion that an id minted for each type is accepted at its own field and refused at the other's.
The refusal half matters as much as the acceptance half, since the current behaviour passes any test
that only checks that a valid id works.
