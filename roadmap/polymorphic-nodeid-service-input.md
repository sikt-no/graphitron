---
id: R933
title: "@nodeId(typeName:) may name an interface at a @service input, decoding into a record-supertype slot"
status: Spec
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
writing `@nodeId(typeName: "<interface>")` on it and typing the receiving Java slot as a jOOQ record
supertype. Graphitron decodes the id at the boundary, rejects an id belonging to none of the
implementations with the standard client error, and hands the service a populated record whose
runtime class says which implementation it was. Today the directive accepts one `@table` object type
only, so a polymorphic service input is spelled as a bare `ID!` and every such service decodes the
wire format by hand.

A *node type* is a `@node` type: a type whose rows carry a global id, encoded on the wire as an opaque
base64 string of `typeId:key1,key2,...`. The *typeId* is the prefix naming which node type the id
belongs to. An *implementation* here is one `@table` object type implementing a multitable interface
(or a member of a union), what the multitable read path calls a participant. A *slot* is the Java
destination a decoded value lands in: a producer-method parameter, or a member of a hand-written
input bean.

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
`@service` argument, where the producer parameter is typed the same way, and at a list-valued slot
(`[ID!]` into `List<UpdatableRecord<?>>`), where each element dispatches on its own typeId.

## Field report

Reported at https://github.com/sikt-no/graphitron/issues/543 against 10.0.0-RC36, from the
tilgangsstyring subgraph. The reporter names it as a third coordinate beside two already fixed:
issue 526 (a `@nodeId` lookup argument on a field returning a multitable interface now dispatches on
the decoded typeId) and issue 525 (a `@nodeId` filter input on such a field routes per participant).
Both are generator-owned rails: graphitron consumes the decode itself. Here the decode has to cross
into author code, so preserving the type is the point rather than a detail. A second mutation family
of the same shape (`oppdaterApplikasjonDetaljer`) is in flight in the same subgraph, so the
hand-rolled decode now exists twice.

The reporter's literal proposal is a small decoded-id value type carrying the typeId and the key
values. This item adopts the goal and answers it with the record carrier that already exists; see
"Other solutions we've considered" for why.

## What exists today, and where each piece stops

Four facts, each anchored on the symbol that owns it, decide the shape of the work.

*The directive refuses an interface at every coordinate.* `NodeIdLeafResolver.resolve` (the read side:
arguments and filter inputs against a containing table) requires the resolved `typeName` to be a
`GraphQLObjectType` carrying `@table` and rejects anything else as structural, with the message
fragment `is not @table-annotated`. `BuildContext.resolveNodeIdRecordDecode`, the slot-side sibling
both slot kinds call, applies the same test with the same message. Bare `@nodeId` cannot fill the
gap either: the polymorphic reading it has derives the candidate set from the consuming field's
multitable *return type* (`FieldBuilder.resolveNodeIdArgTargets`, verdict `SharedTarget` or
`PerParticipant`), and a mutation returning a payload has no such set. So the interface has to be
named, and `typeName:` is the only place to name it.

*Both slot kinds are typed to one table at build time, by equality.* A hand-written bean member
reaches `InputBeanResolver.buildJooqRecordLeaf` when its loaded class is assignable to
`org.jooq.Record` (`InputBeanResolver.isJooqRecord`), and that method then requires the member's
declared class to *equal* the node table's generated record class, refusing every other record type
by name. A producer parameter reaches `ServiceCatalog.nodeIdSlotExtraction`, whose
`takesTheNodeTablesRecord` compares the parameter's javapoet `TypeName` (`DecodedParam.javaType`;
no `Class<?>` travels with it) against that same record class, and falls to the one-value
projection (`CallSiteExtraction.ThrowOnMismatch`) on any other type. So a parameter typed
`UpdatableRecord<?>` today is not refused by the walk at all: it is treated as a one-value slot and
the fact store refuses it as `KEY_ARITY_EXCEEDS_SLOT` or `KEY_COLUMN_TYPE_DISAGREEMENT`, a message
that describes a different mistake from the one the author made. Downstream, `NodeIdEncoder.decodeValues(expectedTypeId, id)`
takes the typeId as an input and returns key values only, so nothing after a decode can recover
which type the id belonged to. Every carrier is built on the premise that the type was settled at
build time and only the key travels at runtime; a polymorphic slot inverts that premise on purpose.

*The fact store is silent on the shape.* `intent_node_id_instruction`'s population is instructions
whose target resolved to a node type, so an instruction naming an interface draws no row there, none
in `intent_node_id_decode`, and none in `intent_node_id_decode_defect` (whose two verdicts are
decided on the node key's arity alone, over `intent_node_id_decode_slot` joined to
`intent_resolved_node_key_shape`). The slot arm of `intent_node_id_decode` decides its
`JOOQ_RECORD` destination by string equality of the slot's type against the node type's
`record_class`, the same equality the walk applies, which is what lets the two agree by construction.
The store cannot climb a generated record's ancestry: `jvm_class_supertype`'s census drops the
generated jOOQ package, so `intent_jvm_ancestor` answers for consumer classes and not for
`CustomerRecord`. What it does know is `graphql_poly_member` (interface implementations and union
members), `intent_node_type` (nodehood), `intent_resolved_node_key_shape` (each node type's
`record_class` and arity) and `sql_primary_key` (whether a table has one).

*The runtime dispatch already exists on the read side.* `MultiTablePolymorphicEmitter.nodeIdDispatchGuard`
emits `NodeIdEncoder.peekTypeId(id)` followed by a candidate check, and `dispatchFailureThrow`
builds the client error `Invalid node id "…" for this argument: decodes to type "…", expected an id
of one of: A, B` (or `not a valid id, …` when the peek fails). Every node type already has a
generated per-type decode (`NodeIdEncoderClassGenerator.buildPerTypeDecode`), and
`RecordDecodeFragments.decodeHelper` already emits the record-materialising
`decode<X>Record(Object wire)` helper the single-type slot uses, named through
`FetchersHelperNames.decodeSingular` / `decodeList`. The slot side needs the three steps composed at
one slot, not new primitives.

## Design

One rule, stated in the author's terms: **at a slot, `typeName:` may name a multitable interface or a
union whose `@table` members are all node types; the slot's declared Java type must be one every
member's generated record is; the decode dispatches on the typeId into that member's record.** Five
decisions carry it, and one ordering rule sits above them: `resolveNodeIdRecordDecode` asks "does
`typeName:` name a node type" first, exactly as today, and asks about members only when it does not.
So a type that is itself a node type keeps the single-type decode whatever its kind, and the
container arms below never see it.

### Resolution: the candidate set

Where `typeName` resolves to a `GraphQLInterfaceType` or `GraphQLUnionType`,
`BuildContext.resolveNodeIdRecordDecode` returns a new `Polymorphic(candidates)` arm beside the
existing `Resolved` (renamed or left as the single-type arm; every existing caller stays on it). The
candidates are `GraphQLSchema.getImplementations` (or the union's `getTypes`) filtered to `@table`
object types, each resolved through the existing single-type path (`resolveTargetKeys`, the catalog
table lookup) so every per-candidate fact is the fact the single-type decode already carries. Three
refusals, all structural and all located at the slot:

* A `@table` member that is not a node type, naming the member on the model of the existing
  `is not a @node type` message. A polymorphic decode with a hole in its candidate set would
  silently reject one member's ids at runtime.
* A container with no `@table` members: nothing to decode into.
* A single-table container, meaning an interface carrying `@table` and `@discriminate` itself
  (the sakila `Signal` fixture's shape) that is not a node type: one table, one record class, so
  naming one of its object types, or making the interface a node type, gives the single-type decode
  the author wants, and a second spelling would be a trap. The message offers both. (An interface
  that carries `@node` itself is in `intent_node_type` today through `graphitron_node_entry`, which
  constrains no kind, and is answered by the ordering rule above; whether the single-type path then
  resolves it or refuses it as not an object type is checked at pickup, and a refusal there is a
  pre-existing gap this item reports rather than fixes.)

The read-side `NodeIdLeafResolver.resolve` is untouched: its candidate set comes from the consuming
field's return type, and the how-to already documents that shape.

### Slot typing: assignability, answered from one captured fact

The slot's declared type, with one `List<…>` unwrapped, must be a type every candidate's generated
record *is*: `org.jooq.Record`, `TableRecord<?>`, `UpdatableRecord<?>` where every candidate table
has a primary key (jOOQ generates `TableRecordImpl` for a PK-less table; the sakila catalog has
several, views and routine result records), or a consumer-side interface the generated records share
through jOOQ's `recordImplements` option. Two refusals, both structural and located at the slot:

* A slot typed as *one* candidate's record (or any other record class none of the others is). The
  alternative, decode the one and reject the others, is the single-type behaviour the author declined
  by naming the interface. The message names the candidates, the candidate that fails, and the
  supertypes all of them share.
* A scalar slot. There is no single column that means "the key" across several tables, and even at
  arity one the slot would have nowhere to carry the type. The message says so and names the shared
  supertypes.

The question both the walk and the store answer is "does every candidate's record declare the
slot's type as an ancestor", and the fact it needs is captured, not reconstructed. The classpath
census (`jvm_class_supertype`, closed by `intent_jvm_ancestor`) deliberately excludes the generated
jOOQ package, so it cannot climb from `CustomerRecord`; but the catalog walk already holds the record
class live (`CatalogFactCapture` reads `table.getRecordType()` for `sql_table.record_class_fqn`), and
its declared supertypes are one more fact off that class. A new capture relation
`sql_table_record_supertype (source_name, table_schema, table_name, supertype_name)`, the record
class's transitive superclass chain and implemented interfaces as the JVM names them, is the top
rung; the store answers assignability by "the slot's raw type name is a `supertype_name` of every
candidate's record" (unioned with `intent_jvm_ancestor` for the consumer-side interface case, where
the slot type itself may be a census class whose ancestors matter). The walk answers the same
question with `Class.isAssignableFrom` against each candidate's live record class from the catalog:
the bean path already holds the member's loaded class (`isJooqRecord` walks it), and the producer
path loads the parameter's raw type by name the way `InputBeanResolver.tryLoad` does, since
`DecodedParam` carries only a `TypeName`. One fact, read twice, is what "agree by construction"
means here; a literal allow-list of three jOOQ names in the walk and the DDL would be a parallel
type system bound to jOOQ by nothing, and a primary-key rule for `UpdatableRecord` would be a guess
about codegen configuration (synthetic keys, function tables) where the class itself is the fact.

The bean path needs no new arm: `isJooqRecord` already routes a `TableRecord<?>`-typed member into
`buildJooqRecordLeaf`, so the change is the check inside it. The producer-parameter path likewise
changes inside `nodeIdSlotExtraction`: on a `Polymorphic` resolution, the assignability test replaces
`takesTheNodeTablesRecord`, and a miss is a refusal rather than the one-value fall-through, so the
misleading arity/type messages above stop firing for this shape.

### Emitted glue: one helper per slot, composed from the existing ones

A new leaf `CallSiteExtraction.NodeIdDecodePolymorphicRecord(encoderClass, SequencedMap<String typeId,
per-candidate decode facts>, ClassName slotType, boolean nonNull)` beside `NodeIdDecodeRecord`, where
the per-candidate facts are exactly what `NodeIdDecodeRecord` carries for one type (typeId, key
columns, table) and `slotType` is the *resolved* admitted supertype the helper returns, a classifier
acceptance carried in the type rather than a bare `TypeName` an emit site could be handed. Its
compact constructor refuses fewer than two candidates: a one-candidate polymorphic leaf is the
single-type case, and that cross-axis invariant belongs to the compiler. It is a sibling rather than a widening because every consumer of `NodeIdDecodeRecord`
(`InputBeanInstantiationEmitter`, `ServiceMethodCallEmitter.scalarLeaf`, `ArgCallEmitter`,
`ConditionGlueRenderer`, `TypeFetcherGenerator`) reads a single table off it and emits a single
helper call; a widened record would hand each of them a list they must not receive, and the
exhaustive switches over `CallSiteExtraction` would lose the compiler's help telling the two apart.

The emitter renders, per slot, one helper `decode<Container>Record(Object wire)` (`…RecordList` for
the list shape, mirroring the existing pair) on the enclosing `*Fetchers` class, returning the
slot's declared supertype:

1. `String peeked = NodeIdEncoder.peekTypeId(wire instanceof String s ? s : null)`.
2. One arm per candidate, `if ("<typeId>".equals(peeked)) { r = decode<Candidate>Record(wire); if (r != null) return r; }`,
   each calling the candidate's record-materialising helper in its *null-returning* form (the shape
   `CompositeDecodeHelperRegistry.Mode.SKIP` gives the read side), emitted through
   `RecordDecodeFragments.decodeHelper`, so a right-prefix-wrong-arity id falls through to the
   failure arm below instead of surfacing that helper's own generic mismatch error.
3. Otherwise the client error, built by the message builder behind `dispatchFailureThrow` so a
   Relay client sees one wording for a bad id whether it hit a lookup argument or a mutation input:
   a malformed id, a foreign typeId, and a right-prefix-wrong-arity id all land here, classified
   the way the read side classifies them. The parity is pinned at the execution tier, not asserted
   in prose.

An `if` chain rather than a string `switch`: the emitted source must compile at Java 17
(`graphitron-sakila-example` pins `<release>17</release>`), and a `switch` on a null `String` throws,
so the chain is both the simpler and the correct shape. Nullability keeps the existing contract: a
nullable (`ID`) slot leaves the member unset when omitted and null on explicit `null`; a non-null
(`ID!`) slot always decodes or throws.

### Fact store: the same rule as rows

The store's job is to state the same predicate the walk applies, so the editor and the build refuse
the same schemas and the LSP's node-type keyset moves with them. Four additions, each at the rung the
existing relations reserve for it:

* *Record supertypes as a captured fact.* `sql_table_record_supertype`, described under Slot typing:
  captured at the catalog walk beside `sql_table`, one row per (table, supertype name), with a
  comment stating that it exists because the classpath census excludes the generated package and
  that `intent_jvm_ancestor` is its census-side twin.
* *Container members as a container-keyed rung.* A view `intent_node_container_member (graph_name,
  container_name, member_type_name)` over `graphql_poly_member`, filtered to `@table` members that are
  node types (`intent_node_type`). Keyed on the container, because "which node types are members of
  `Applikasjon`" is a fact about `Applikasjon` and not about any slot that names it; keying it on a
  use site would store one copy of the answer per consuming coordinate. This is the
  `intent_spelled_table` / `intent_bound_table` layering, and the read side's per-participant
  candidate derivation can later re-source onto it.
* *The instruction population widens where it lives.* `intent_node_id_instruction_live`'s
  `EXPLICIT_TYPE_NAME` arm joins the written `node_type_ref` to `intent_node_type`, so an instruction
  naming a container draws no row today, none downstream, and no defect: silent. The fix is a new
  arm in that relation, at the participant-keyed grain its own comment already reserves
  (`(graph_name, site, type_name, field_name, argument_name or path, participant type name)`), one
  row per member with `node_type_name` = the member, a `basis` value naming the container reading,
  and the container name carried as a column so a message needs no second join. The relation's
  population-boundary sentence ("an instruction whose named or inferred type resolves to no node
  type is not a row") is amended in the same edit, since this item is what retires its
  justification for the container case. `intent_node_id_decode_slot`, `intent_node_id_decode` and
  the endpoint family then see the rows by join, and the `node_type_name` column comment across the
  family ("the node type the instruction names, whose key the decode yields") is audited once: on
  these rows it is the member, the container being the sibling column.
* *A fifth destination and a sibling defect view.* The slot arm of `intent_node_id_decode` gains
  `POLYMORPHIC_RECORD`, produced where the slot's type is an ancestor of every member's record (the
  `sql_table_record_supertype` ∪ `intent_jvm_ancestor` test), one row per member with that member's
  `arity`; its `destination` comment, which today says the slot arm's record is "the node type's own
  table's record", is the sentence this item rewrites. Beside `intent_node_id_decode_defect`, a
  sibling `intent_node_id_polymorphic_decode_defect` with a closed verdict vocabulary:
  `MEMBER_NOT_NODE_TYPE` (one row per offending `@table` member), `NO_TABLE_MEMBERS`,
  `SINGLE_TABLE_CONTAINER`, `SLOT_NOT_SUPERTYPE_OF_MEMBER` (one row per member whose record does not
  declare the slot type). A sibling rather than new arms because the incumbent's whole design is
  "decided by arity alone, one pass over two driving relations" and these verdicts are decided on
  the member set and the slot type, a different fact base; and the two populations are disjoint by
  construction (the incumbent joins `intent_resolved_node_key_shape` on `node_type_name`, which a
  container never resolves), a fact the new view's comment states on the incumbent's terms rather
  than leaves true by accident. Projected into `Rejection` arms by `NodeIdPolymorphicDecodeDefects`
  beside `NodeIdDecodeDefects`, with the same `Verdict.of` drift guard and the same
  `intent_type_domain` narrowing, and wired as a new component of `StoreDetections` (the seam
  `FactCapture` fills), not as a call in `FactCapture` itself.

The walk's refusals and the store's verdicts are the same facts under two names; the pipeline tests
below assert the walk, the fact-store tests assert the rows, and the existing
`NodeIdProducerSlotDecodePipelineTest` pattern (assert that the schema *builds* and that the slot's
transform is the decode, because a silent detection and a red build look alike from above) carries
over to the positive cases.

### Editor

`Behavior.NodeTypeBinding` in `LspVocabulary` maps `@nodeId(typeName:)` onto the node-type keyset,
and `Diagnostics` asks `questions.nodeTypeName` of the written value, so a schema this item makes
valid gets an editor diagnostic and no completion for the container. The keyset the LSP reads is
widened to the containers `intent_node_container_member` has members for, so completion and
diagnostics move with the build from the same relation; a walk-side rule alone would fork the two
views.

## Implementation

Symbol-anchored, no line numbers; re-find by search at pickup.

* `BuildContext.resolveNodeIdRecordDecode`: after the node-type test, the `is not @table-annotated`
  arm forks on `GraphQLInterfaceType` / `GraphQLUnionType` into the member walk, returning the new
  `Polymorphic` arm or one of the three container refusals. `NodeIdLeafResolver.resolve` keeps its
  refusal.
* `InputBeanResolver.buildJooqRecordLeaf`: on `Polymorphic`, `isAssignableFrom` from the member's
  loaded class against each candidate's live record class (the catalog holds it; add an accessor on
  `JooqCatalog` beside `findTable` if none exposes `Table.getRecordType()`), then the new leaf; a miss
  is the candidates-and-shared-supertypes refusal. `singleValuedMemberDeferral` is unchanged (see Out
  of scope).
* `ServiceCatalog.nodeIdSlotExtraction`: on `Polymorphic`, load the parameter's raw type by name
  (one `List` unwrapped, as `takesTheNodeTablesRecord` does today), the same test, the same leaf, and
  a refusal on a miss instead of the `ThrowOnMismatch` fall-through.
* `CallSiteExtraction`: the `NodeIdDecodePolymorphicRecord` leaf with its compact-constructor
  invariants; every exhaustive switch over the sealed interface gains its arm (the compiler lists
  them).
* `InputBeanInstantiationEmitter`, `ServiceMethodCallEmitter.scalarLeaf`, `ArgCallEmitter`: render
  the `decode<Container>Record` / `…RecordList` call and collect the helper onto the `*Fetchers`
  class the way the single-type helpers are collected today; the helper body reuses
  `RecordDecodeFragments.decodeHelper` (null-returning form) per candidate and the failure message
  behind `MultiTablePolymorphicEmitter.dispatchFailureThrow`, lifted to a shared fragment since it is
  private there.
* `FetchersHelperNames`: a name for the container helper beside `decodeSingular` / `decodeList`.
* `CatalogFactCapture` and `graphitron-model.sql`: `sql_table_record_supertype` capture and DDL;
  `intent_node_container_member`; the participant-keyed arm on `intent_node_id_instruction_live`
  with its amended population sentence; the `POLYMORPHIC_RECORD` arm and its rewritten comment; the
  sibling defect view; comment audit of `node_type_name` across the decode family; the relation
  register rows and the `meta_relation` declaration (or `undeclared-relations.txt` entry) each new
  relation owes.
* `graphitron-model`: `NodeIdPolymorphicDecodeDefects` beside `NodeIdDecodeDefects`, a new
  `StoreDetections` component, filled where `FactCapture` fills the others.
* `graphitron-lsp`: the node-type keyset behind `Behavior.NodeTypeBinding` re-sourced to include
  containers with members, for both completion and diagnostics.
* Docs, per the draft below: `docs/manual/reference/directives/nodeId.adoc` (a subsection after
  "Decoding into a producer parameter named for the argument", and a Constraints bullet) and
  `docs/manual/how-to/global-id.adoc` (a subsection after "A `@nodeId` argument on a field returning
  an interface or union").

## User documentation (first-client check)

For `nodeId.adoc`, after the producer-parameter subsection:

> === Decoding an id of any implementation into a record supertype
>
> At a `@service` slot, `typeName:` may name a multitable interface or a union instead of one node
> type. The slot then accepts the id of any `@table` implementation, and the Java side is typed as
> something every implementation's generated record is: `org.jooq.Record`, `TableRecord<?>`,
> `UpdatableRecord<?>` when every implementation's table has a primary key, or an interface your
> jOOQ configuration makes the records implement. The
> generated fetcher reads the id's type prefix, decodes it into that implementation's own record,
> and hands the service a record whose runtime class says which implementation it was; an id of a
> type outside the interface fails the request naming every implementation, before the service runs.
>
> ```graphql
> union AddressOccupant = Customer | Staff
>
> input DeactivateOccupantInput {
>     occupantId: ID! @nodeId(typeName: "AddressOccupant")
> }
> ```
>
> ```java
> public record DeactivateOccupantInput(UpdatableRecord<?> occupantId) {}
>
> public static Boolean deactivate(DeactivateOccupantInput in) {
>     if (in.occupantId() instanceof CustomerRecord c) { ... }
>     if (in.occupantId() instanceof StaffRecord s)    { ... }
> }
> ```
>
> The same works at a producer parameter named for the argument, and at a list slot
> (`[ID!]` into `List<UpdatableRecord<?>>`). Every implementation must be a node type; the build
> names one that is not. Typing the slot as one implementation's record is refused: that is the
> single-type spelling, so name the type instead of the interface.

Constraints bullet:

> * At a `@service` slot, `typeName:` may name a multitable interface or union. The slot must then be
>   typed as a supertype of every implementation's generated record (`org.jooq.Record`,
>   `TableRecord<?>`, `UpdatableRecord<?>` over primary-keyed tables), never one implementation's
>   record or a scalar, and every `@table` implementation must be a node type. A single-table
>   `@discriminate` interface that is not itself a node type is refused here: name one of its object
>   types, or make it a node type. See the subsection above.

For `global-id.adoc`, after the polymorphic-argument subsection, the mutation-side sibling in the
same voice, using the `AddressOccupant` schema that page already uses, showing the request, the
service's `instanceof` dispatch, and the foreign-id error text (identical to the one shown above it).

## Tests

* Pipeline tier (`graphitron`, beside `NodeIdRecordInputBeanPipelineTest` and
  `NodeIdProducerSlotDecodePipelineTest`, over the test catalog's `Film | Inventory` or
  `Customer | Staff` union with `@node` members): an interface-typed `@nodeId` on a bean member typed
  `UpdatableRecord<?>` classifies clean and the emitted helper has one arm per candidate typeId plus
  the failure arm; the same at `TableRecord<?>`, `Record`, and the list shape; the same member typed
  as one candidate's record is refused naming the candidates and the shared supertypes; a scalar
  member is refused; a container with a non-node `@table` member is refused naming it; a `@table
  @discriminate` interface that is not a node type is refused with both remedies; an
  `UpdatableRecord<?>` slot over a candidate whose record is a `TableRecordImpl` is refused naming
  that candidate. The same matrix at a producer parameter, including that the old
  `KEY_ARITY_EXCEEDS_SLOT` no longer fires for an `UpdatableRecord<?>` parameter under a polymorphic
  `typeName`.
* Fact-store tier (`graphitron-model`, `intent` package, beside `NodeIdDecodeDestinationTest` and
  `NodeIdDecodeDefectTest`): seeded anchors for `sql_table_record_supertype`,
  `intent_node_container_member`, the participant-keyed instruction rows, the `POLYMORPHIC_RECORD`
  rows (one per member), each of the four verdicts, and that a polymorphic slot draws no row in
  `intent_node_id_decode_defect`. The mechanical enforcers every new relation owes:
  `FactCaptureAgreementTest` registration (both legs), total comment coverage under
  `FactSchemaGateTest`, and the `meta_relation` declaration or `undeclared-relations.txt` entry
  `MetaDeclarationGateTest` binds.
* Execution tier (`graphitron-sakila-example`, `GraphQLQueryTest` beside
  `assignFilmActorRecord_decodesCompositeNodeIdIntoBothKeyColumns`): a new `@service` mutation over
  `AddressOccupant = Customer | Staff` with a bean member typed `UpdatableRecord<?>` (service in
  `graphitron-sakila-service`, reporting the record class and key it received), driven with a
  `Customer` id, a `Staff` id, a `Film` id (client error naming both candidates), a malformed id, and
  a `Customer`-prefixed id of the wrong arity (the same "not a valid id" wording the read side
  gives it); the producer-parameter twin.
* Compilation tier: the emitted glue compiles under `<release>17</release>` in
  `graphitron-sakila-example` (the execution fixture covers it).
* LSP tier: a container at `@nodeId(typeName:)` completes and draws no unknown-node-type diagnostic;
  a container with no node members still does.

## Out of scope

* `InputBeanResolver.singleValuedMemberDeferral`: a `@nodeId` on a *scalar* member of a hand-written
  bean, at one concrete type, is refused as deferred today. This item leaves that refusal in place and
  does not depend on lifting it: the polymorphic slot is a record supertype, which lands on the
  record arm, and a scalar slot has nowhere to carry the type in any case.
* Bare `@nodeId` at a slot inheriting a *polymorphic* target (the read side's rule): a mutation
  returning a payload has no return-type candidate set, and inventing one from `@mutation(table:)`
  would be a new inference. The interface has to be named.
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
* *Widening `typeName:` alone*, keeping the slot typed as one record. Rejected under Slot typing: it
  is the single-type behaviour with a misleading spelling.
* *A closed set of three jOOQ supertype names* (`Record`, `TableRecord`, `UpdatableRecord`),
  compared by string at both slot kinds and in the DDL, with a primary-key rule admitting the third.
  Three copies of a list bound to jOOQ by nothing, a parallel type system beside a fact the catalog
  walk already holds, and a codegen guess (synthetic keys, function tables) where the record class
  itself says what it extends. Capturing the record's supertypes once answers the same question from
  one fact and admits the `recordImplements` case for free.
* *A use-site-keyed candidate relation* joined into the slot arm. It cannot resurrect a use site the
  instruction relation never admitted, it stores one copy of a container fact per consuming
  coordinate, and it mints a second spelling of the participant-keyed grain the instruction
  relation's comment already reserves.
* *Widening `NodeIdDecodeRecord`* to carry a candidate list instead of a sibling leaf. Rejected
  under Emitted glue: every consumer of the existing leaf reads one table off it.
* *New arms on `intent_node_id_decode_defect`* instead of a sibling view. Rejected under Fact store:
  the existing view's verdicts are a function of arity over two relations, and these are not.
* *A new directive argument* (`@nodeId(anyOf: [...])`) listing the implementations. The interface
  already lists them, and a hand-maintained list drifts from it when an implementation is added.
