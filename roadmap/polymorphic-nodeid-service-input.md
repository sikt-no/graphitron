---
id: R933
title: "@nodeId(typeName:) may name an interface at a @service input, decoding into a record-supertype slot"
status: Backlog
bucket: feature
priority: 3
theme: nodeid
depends-on: []
created: 2026-09-08
last-updated: 2026-09-08
---

# @nodeId(typeName:) may name an interface at a @service input, decoding into a record-supertype slot

## Goal

A `@service` mutation can take the id of *any* implementation of an interface as one input field, by
writing `@nodeId(typeName: "<interface>")` on it and typing the receiving Java slot as a supertype of
every implementation's generated jOOQ record. Graphitron decodes the id at the boundary, rejects an id
belonging to none of the implementations with the standard client error, and hands the service a
populated record whose runtime class says which implementation it was. Today the directive accepts one
`@table` object type only, so a polymorphic service input is spelled as a bare `ID!` and every such
service decodes the wire format by hand.

A *node type* is a `@node` type: a type whose rows carry a global id, encoded on the wire as an opaque
base64 string of `typeId:key1,key2,...`. The *typeId* is the prefix naming which node type the id
belongs to. An *implementation* here is one `@table` object type implementing a multitable interface,
what the multitable read path calls a participant.

The shape, verbatim from the reporting subgraph (the `Applikasjon` interface has three `@table`
implementations, each its own node type):

```graphql
"""Applikasjonen som skal deaktiveres."""
input DeaktiverApplikasjonerInput {
  "ID-en til applikasjonen (FeideApplikasjon, MaskinportenApplikasjon eller MaskinbrukerApplikasjon)."
  applikasjonId: ID!
}
```

The sibling fields in the same schema region write the directive wherever it *can* express the intent
(`miljoId: ID! @nodeId(typeName: "Miljo")`); this one cannot, so it carries a comment instead and the
service decodes against three typeIds itself. When this lands the author writes:

```graphql
input DeaktiverApplikasjonerInput {
  applikasjonId: ID! @nodeId(typeName: "Applikasjon")
}
```

```java
public record DeaktiverApplikasjonerInput(UpdatableRecord<?> applikasjonId) {}

public static Boolean deaktiver(DeaktiverApplikasjonerInput in) {
    UpdatableRecord<?> app = in.applikasjonId();
    if (app instanceof FeideApplikasjonRecord r)        return ...;
    if (app instanceof MaskinportenApplikasjonRecord r) return ...;
    if (app instanceof MaskinbrukerApplikasjonRecord r) return ...;
    throw new IllegalStateException();
}
```

(An `instanceof` chain rather than a pattern switch so the example compiles at Java 17, the floor
consumers may be on; a consumer on 21 writes the switch.)

The service receives both facts it dispatches on, the type as the record's class and the keys as the
record's loaded key columns, and never the base64 string. The same shape holds at a top-level
`@service` argument, where the producer parameter is typed the same way.

## Field report

Reported at https://github.com/sikt-no/graphitron/issues/543 against 10.0.0-RC36, from the
tilgangsstyring subgraph. The reporter names it as a third coordinate beside two already fixed:
issue 526 (R673, a `@nodeId` lookup argument on a field returning a multitable interface now
dispatches on the decoded typeId) and issue 525 (R676, a `@nodeId` filter input on such a field
routes per participant). Both are generator-owned rails: graphitron consumes the decode itself. Here
the decode has to cross into author code, so preserving the type is the point rather than a detail.
A second mutation family of the same shape (`oppdaterApplikasjonDetaljer`) is in flight in the same
subgraph, so the hand-rolled decode now exists twice.

The reporter's literal proposal is a small decoded-id value type carrying the typeId and the key
values. This item adopts the goal and answers it with the record carrier that already exists; see
"Other solutions we've considered" for why.

## What exists today, and where each piece stops

Three facts, each anchored on the symbol that owns it, decide the shape of the work.

*The directive refuses an interface at every coordinate.* `NodeIdLeafResolver.resolve` requires the
resolved `typeName` to be a `GraphQLObjectType` carrying `@table` and rejects anything else as
structural, with the message fragment `is not @table-annotated`. `BuildContext.resolveNodeIdRecordDecode`,
the record-member sibling, applies the same test with the same message. Bare `@nodeId` cannot fill
the gap either: the polymorphic reading R673 gave it derives the candidate set from the consuming
field's multitable *return type* (`FieldBuilder.resolveNodeIdArgTargets`, verdict `SharedTarget` or
`PerParticipant`), and a mutation returning a payload has no such set. So the interface has to be
named, and `typeName:` is the only place to name it.

*The decode is typed to one table at build time.* On the Java rail the decoded value lands in a slot,
meaning a producer parameter or a member of a hand-written input bean. The fact store's
`intent_node_id_decode` slot arm knows two destinations: `JOOQ_RECORD`, a slot typed as the node
type's own table's generated record, which takes the whole key tuple; and `SINGLE_KEY_COLUMN`, a
one-column key into a slot typed as that column. Its comment states the boundary this item moves: a
slot typed as *some other* table's record is deliberately no row, and `intent_node_id_decode_defect`
(read by `NodeIdDecodeDefects`) names the refusal. In the generator, `InputBeanResolver.buildJooqRecordLeaf`
resolves `typeName` to one table and checks the member's declared class *is* that table's record
class. `NodeIdEncoder.decodeValues(expectedTypeId, id)` takes the typeId as an input and returns key
values only, so nothing downstream of a decode can recover which type the id belonged to. Every
carrier is built on the premise that the type was settled at build time and only the key travels at
runtime; a polymorphic service input inverts that premise on purpose.

*The runtime dispatch already exists on the read side.* `MultiTablePolymorphicEmitter.nodeIdDispatchGuard`
emits `NodeIdEncoder.peekTypeId(id)` followed by a candidate check and the client error
`expected an id of one of: <candidates>`, and every node type already has a generated per-type
decode (`NodeIdEncoderClassGenerator.buildPerTypeDecode`) plus a record-materialising helper shape
(`RecordDecodeFragments.decodeHelper`). The write side needs the same three steps composed at a
slot, not new primitives.

## Design

One rule, stated in the author's terms: **at a slot, `typeName:` may name a multitable interface (or
union) whose implementations are all node types; the slot's declared Java type must be one every
implementation's generated record is assignable to; the decode dispatches on the typeId into that
implementation's record.**

* *Resolution.* Where `typeName` resolves to a `GraphQLInterfaceType` or `GraphQLUnionType`, the
  candidate set is `GraphQLSchema.getImplementations` (or the union's members) filtered to `@table`
  object types, each resolved through the existing single-type path
  (`BuildContext.resolveTargetKeys`, `resolveDecodeHelperForType`). An implementation that is not a
  node type is a build error naming it, on the model of the existing `is not a node type. Annotate
  ... with @node` message: a polymorphic decode with a hole in its candidate set would silently
  reject one implementation's ids at runtime. An interface with no `@table` implementations, or a
  single-table `@discriminate` interface, is refused as structural: the first has nothing to decode
  into, and the second is one table with one record class, so the existing single-type spelling
  already covers it and a second spelling would be a trap.
* *Slot typing.* The slot's declared class must satisfy `isAssignableFrom` against every candidate's
  generated record class. `org.jooq.Record`, `TableRecord<?>` and `UpdatableRecord<?>` all qualify;
  so does any consumer-side supertype the generated records happen to share. A slot typed as *one*
  candidate's record is rejected with a message naming the candidates and the supertypes that
  qualify, because the alternative (decode the one, reject the others) is the single-type behaviour
  the author just declined by naming the interface. A scalar slot is rejected: there is no single
  column that means "the key" across several tables, and even at arity one the slot would have
  nowhere to carry the type. The existing `InputBeanResolver.isJooqRecord` assignability test already
  routes a `TableRecord<?>`-typed member into the record arm, so the change is inside
  `buildJooqRecordLeaf` and its producer-parameter counterpart, not a new arm.
* *Emitted glue.* Per slot: `peekTypeId` on the wire value; a switch over the candidate typeIds, each
  arm calling that candidate's record-materialising decode helper; the default arm throwing the
  R673-shaped client error naming every candidate. Malformed base64 and a foreign typeId both land in
  the default arm. A nullable (`ID`) slot keeps the existing changed-flag contract (omitted leaves the
  member unset, explicit `null` sets it null); a non-null (`ID!`) slot always decodes or throws. The
  glue must compile at Java 17 (`graphitron-sakila-example` pins `<release>17</release>` for emitted
  sources), so the switch is a string switch on the peeked typeId, not a pattern switch.
* *Fact store.* The slot arm of `intent_node_id_decode` gains the polymorphic destination, one row per
  candidate under the same use site, or a fifth destination value whose arity is the candidate count;
  which of the two is a Spec question, decided by what `intent_node_id_decode_defect` needs to name
  its refusals (a non-node candidate, a slot type that fails assignability). The relation's own
  comment asks that the record be the node type's own table's record; that sentence is the one this
  item rewrites, and it should say why the supertype is now admitted.
* *Error message.* The client error follows `nodeIdDispatchGuard`'s wording so a Relay client sees one
  shape for a foreign id whether it hit a lookup argument or a mutation input.

## Implementation

Symbol-anchored, no line numbers; re-find by search at pickup.

* `NodeIdLeafResolver.resolve` and `BuildContext.resolveNodeIdRecordDecode`: the `is not
  @table-annotated` arm forks on interface/union, producing a candidate set rather than a rejection.
  A sealed outcome (`SingleType` / `Polymorphic(candidates)`) keeps every existing caller on the
  single-type arm untouched.
* `InputBeanResolver.buildJooqRecordLeaf`: on a `Polymorphic` resolution, the assignability check over
  all candidates, then a new `CallSiteExtraction` leaf (a sibling of `NodeIdDecodeRecord` carrying
  the candidate list) rather than widening `NodeIdDecodeRecord`'s single-table shape.
* The producer-parameter slot (the path `NodeIdDecodeDefects` guards): the same assignability rule
  where the parameter's declared type is read, and the same leaf.
* `InputBeanInstantiationEmitter` / the producer-parameter emitter: render the peek-switch-decode glue
  from the leaf, reusing `RecordDecodeFragments.decodeHelper` per candidate and the message builder
  behind `nodeIdDispatchGuard` for the default arm.
* `graphitron-model.sql`: the `intent_node_id_decode` slot arm and `intent_node_id_decode_defect`, per
  the Fact store bullet above.
* Docs: `docs/manual/reference/directives/nodeId.adoc` gains a subsection beside "Decoding into a jOOQ
  record at a `@service` param" and a Constraints bullet; `docs/manual/how-to/global-id.adoc` gains
  the mutation-side sibling of "A `@nodeId` argument on a field returning an interface or union",
  with the `Application` / `FeideApplication` / `IdmApplication` schema that page already uses.

## Tests

* Pipeline tier: an interface-typed `@nodeId` on a hand-written bean member typed `UpdatableRecord<?>`
  classifies clean and emits a switch over every candidate typeId; the same member typed as one
  candidate's record is refused naming the candidates; a scalar member is refused; an interface with a
  non-node implementation is refused naming it; a single-table `@discriminate` interface is refused
  pointing at the single-type spelling. The same matrix at a top-level producer parameter.
* Fact-store tier: the new destination rows and defect verdicts, in the style of
  `NodeIdDecodeDefectsTest`.
* Execution tier: a sakila-example `@service` mutation over a multitable interface with two `@node`
  implementations, driven with an id of each implementation (the service reports which record class
  it received), a foreign id (client error naming both candidates), and a malformed id.
* Compilation tier: the emitted glue compiles under `<release>17</release>` in `graphitron-sakila-example`.

## Out of scope

* `InputBeanResolver.singleValuedMemberDeferral`: a `@nodeId` on a *scalar* member of a hand-written
  bean, at one concrete type, is refused as deferred today (the one-column projection a producer
  parameter already receives is not emitted one level deeper). This item leaves that refusal in place
  and does not depend on lifting it: the polymorphic slot is a record supertype, which lands on the
  record arm, and a scalar slot has nowhere to carry the type in any case.
* The decoded-id value type the issue proposes; see below.
* Documenting the manual `peekTypeId` / `decodeValues` pattern as a canonical interim: once this
  lands the pattern is unnecessary, and documenting it first would bless the wire-format knowledge
  in author code the directive exists to remove.

## Other solutions we've considered

* *A generated decoded-id value type* (`typeId` plus key values), the issue's literal proposal. It
  needs a runtime home and a key representation: untyped (`String[]`) hands the author the wire
  format's columns to re-coerce, and typed needs the table's record anyway, at which point the value
  type is the record with a name beside it. The record supertype carries the same two facts, types
  the keys correctly per candidate, and stays inside the existing doctrine that a slot receives the
  decoded key and never the wire format. The one thing the value type would add is a `typeName`
  string; the record's class, and `getTable()`, answer the same question.
* *Widening `typeName:` alone*, keeping the slot typed as one record. Rejected above under Slot
  typing: it is the single-type behaviour with a misleading spelling.
* *A new directive argument* (`@nodeId(anyOf: [...])`) listing the implementations. The interface
  already lists them, and a hand-maintained list drifts from it when an implementation is added.
