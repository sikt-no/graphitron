---
id: R933
title: "@nodeId(typeName:) may name an interface at a @service input, decoding into a record-supertype slot"
status: In Progress
bucket: feature
priority: 3
theme: nodeid
depends-on: []
created: 2026-09-08
last-updated: 2026-09-11
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
`FetchersHelperNames.decodeSingular` / `decodeList`. The slot side is mostly those three steps
composed at one slot; the one primitive it adds is a fourth `decodeHelper` overload, because none of
the three public ones returns null on an arity mismatch (see Emitted glue).

## Design

One rule, stated in the author's terms: **at a slot, `typeName:` may name a multitable interface or a
union whose `@table` members are all node types; the slot's declared Java type must be one every
member's generated record is; the decode dispatches on the typeId into that member's record.** Five
decisions carry it, and one ordering rule sits above them: `resolveNodeIdRecordDecode` asks "does
`typeName:` name a node type" first, and asks about members only when it does not. So a type that is
itself a node type keeps the single-type decode whatever its kind, and the container arms below never
see it.

That ordering is a change rather than the status quo, and stating it as one matters because it is
what makes the two populations disjoint. Today the method asks the `@table`-object question first and
node-ness only afterwards, inside `resolveTargetKeys`, whose parameter is a `GraphQLObjectType` and
so cannot be handed an interface at all. The reordering has to be answer-preserving for every shape
that resolves or refuses today: an object type carrying `@table` whose table backs a node type
reaches the same `Resolved` arm, and both existing refusal messages keep their coordinates and their
wording. The one shape the new order moves is a container that is itself a node type, which now
fails the `@table`-object test after the nodehood question rather than before, with the same message,
so no consumer sees a different answer. (Whether that shape should instead resolve is the
pre-existing gap the parenthetical under Resolution reports rather than fixes.)

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

**How many admissible members the container has is not a fourth refusal.** A container with exactly
one, which `union U = Customer` and a one-implementation interface both are, resolves and decodes
through the same chain with one dispatch arm. The fork is real and the other answer is defensible, so
the reason is stated rather than assumed: the member set belongs to the schema and grows, and refusing
at one would make the correct spelling of a slot a function of the implementation count on the day it
was written. The author would name the member today, then move both the SDL and the Java slot the
moment a second implementation lands, and a slot left un-moved would silently accept one
implementation's ids forever. That is the drift this item already refuses to build in when it declines
`@nodeId(anyOf: [...])` under "Other solutions we've considered", and the count is the same list kept
implicitly. The single-table container above stays refused for a reason the count does not share: it
binds one table, so no member it ever gains adds a record class to dispatch on, and the single-type
decode is its permanent answer rather than today's.

The store and the editor reach the same answer by asking no count anywhere along the chain, which is
what makes this the pick that leaves the three surfaces agreeing without a verdict of its own: no
relation between `intent_node_container_member` and `POLYMORPHIC_RECORD` counts members, and the
LSP keyset arm asks only that some member be table-bound and a node type. What the walk gives up is
one refusal arm; what the leaf gives up is a two-candidate invariant, which becomes a one-candidate
one, since what tells a polymorphic decode from a single-type one is the type the author *named* and
not the size of its member set.

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
rung. It holds the *closure* and not the edges, unlike the census pair below it
(`jvm_class_supertype` edges closed by `intent_jvm_ancestor`), and the comment has to say why or a
reader will reasonably try to turn it into a view: the chain above a generated record runs through
jOOQ's own runtime classes, which no census scans and which are not tables, so the store holds no
edges to recompute a closure from. What the walk climbs is a live class hierarchy, and capture
transcribing what it climbed is a stratum-one fact rather than a denormalized derivation.

Assignability is then one relation over that one captured fact. Two readers ask it on day one, the
`POLYMORPHIC_RECORD` destination and `SLOT_NOT_SUPERTYPE_OF_MEMBER`, so it gets a relation of its own
rather than the same expression written twice: a view keyed on (graph, node type, slot type name).

*The composition this section first specified is retired, and both halves of it were measured.* The
plan said the `recordImplements` case wanted a join through `intent_jvm_ancestor`, on the reading
that the capture held a record's direct parents and the census had to climb from the shared consumer
interface upward. That was wrong twice. It is redundant, because the capture climbs the live record
class and therefore already holds every supertype of that interface at any depth, which is what "it
holds the closure and not the edges" means. And it is unaffordable: H2 inlines a recursive view and
re-evaluated the whole closure once per driving row, which against the sakila capture never returned
at all, where the two arms that remain answer in about a tenth of a second. Two rewrites of the
census leg were measured and neither helped, one unchanged and one worse; the snapshot control put
the leg at 20 s against 0.13 s with the closure as a table, which is what identified it. Deleting it
is what fixed it, and the relation's own comment carries the arithmetic. The seeding half fell to the
same measurement: driving the view from the distinct slot types `intent_node_id_decode_slot` reports
pulled that relation into the join and H2 re-evaluated it per driving row, so the relation is total
over the node population instead.
The walk answers the same question with `Class.isAssignableFrom` against each candidate's live record
class from the catalog:
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

A new leaf `CallSiteExtraction.NodeIdDecodePolymorphicRecord(encoderClass, List<Candidate> candidates,
AdmittedSlotType slotType, boolean nonNull)` beside `NodeIdDecodeRecord`, where each candidate
carries exactly what `NodeIdDecodeRecord` carries for one type (typeId, key columns, table). A list
rather than a map keyed on the typeId, which each entry already holds: the order is the emission
order and a list states it without spelling one value twice. `slotType` is the admitted supertype the
helper returns, wrapped in a one-component record the classifier mints only where the assignability
check passed, so the acceptance is carried in the type instead of being a `ClassName` an emit site
could be handed from anywhere. Its compact constructor refuses an empty candidate list, a container the walk refuses rather than
resolves; it puts no upper or lower bound beyond that, per the member-count rule under Resolution. It is a sibling rather than a widening because every consumer of `NodeIdDecodeRecord`
(`InputBeanInstantiationEmitter`, `ServiceMethodCallEmitter.scalarLeaf`, `ArgCallEmitter`,
`ConditionGlueRenderer`, `TypeFetcherGenerator`) reads a single table off it and emits a single
helper call; a widened record would hand each of them a list they must not receive, and the
exhaustive switches over `CallSiteExtraction` would lose the compiler's help telling the two apart.

The emitter renders, per slot, one helper `decode<Container>Record(Object wire)` (`…RecordList` for
the list shape, mirroring the existing pair) on the enclosing `*Fetchers` class, returning the
slot's declared supertype:

1. `String wireString = wire instanceof String ? (String) wire : null;` then
   `String peeked = NodeIdEncoder.peekTypeId(wireString);`: named locals rather than throwaway
   pattern-binding names, per the emitted-code readability rules.
2. One arm per candidate, `if ("<typeId>".equals(peeked)) { <Candidate>Record decodedRecord = decode<Candidate>Record(wire); if (decodedRecord != null) return decodedRecord; }`,
   each calling the candidate's record-materialising helper in a *null-returning* form, so a
   right-prefix-wrong-arity id falls through to the failure arm below instead of surfacing that
   helper's own mismatch error. That form does not exist yet and is the one primitive this item
   adds: `RecordDecodeFragments.decodeHelper` has three public overloads and every one of them
   throws on an arity mismatch, the generic `GraphqlErrorException` on one and
   `NodeIdDecodeFailure`'s client error on another; the only `return null` in the shared body is
   the not-a-`String` case. So the item adds a fourth overload passing `return null` as the
   `mismatchThrow`, which is that file's existing private-shared-body pattern with a fourth caller
   and no new decode logic. `decodeValues` already returns null for a foreign prefix, so one arm
   answers both misses.
3. Otherwise the client error, built by the message builder behind `dispatchFailureThrow` so a
   Relay client sees one wording for a bad id whether it hit a lookup argument or a mutation input:
   a malformed id, a foreign typeId, and a right-prefix-wrong-arity id all land here, classified
   the way the read side classifies them. The parity is pinned at the execution tier, not asserted
   in prose. The multi-candidate message lands in
   `no.sikt.graphitron.render.NodeIdDecodeFailure`, which already holds the single-type two-branch
   message and whose class comment gives this item's own reason for the move: one bad id must fail
   the same way at every grain that reads it. So the fragment goes beside its single-type sibling
   rather than into a third place, and `MultiTablePolymorphicEmitter` reads it there.

An `if` chain rather than a string `switch`: the emitted source must compile at Java 17
(`graphitron-sakila-example` pins `<release>17</release>`), and a `switch` on a null `String` throws,
so the chain is both the simpler and the correct shape. Nullability keeps the existing contract: a
nullable (`ID`) slot leaves the member unset when omitted and null on explicit `null`; a non-null
(`ID!`) slot always decodes or throws.

### Fact store: the same rule as rows

The store's job is to state the predicate the walk applies, so the editor and the build refuse the
same schemas and the LSP's node-type keyset moves with them. All of it at the container, and the
slot-typing half only where the slot relation can see the slot; the limit is stated at the end of
this section, because it lands on the field report's own shape.

**The grain, decided first, because the rest of the slice follows from it: one instruction row per
use site, carrying the container, and the member multiplicity kept on the container where it already
lives.** A *use site* is the consuming coordinate an instruction is read at, an argument or one
occurrence path of an input field. The rejected alternative is one instruction row per member at one
use site, and it is rejected for three reasons that are one reason.

First, it would report a determinate schema as producer ambiguity.
`intent_node_id_decode_slot.candidates` is `COUNT(*) OVER (PARTITION BY graph_name, use_site)` over
that relation's own rows, and its comment reads any value above one as "an overloaded method or a
class two classpath entries declare, and this relation distinguishes neither". The slot relation
leans on that reading: it deliberately keeps ambiguous rows rather than filtering them, because a use
site with no row there reads as binding a table predicate, so keeping them "makes that use site a
decode with several candidate slots, which a consumer refuses to carry out rather than mistaking for
a predicate". Member rows would arrive on that same column, so a two-member container would be
indistinguishable from an author whose `@service` names two overloads. Second, the arm this item
needs would then produce nothing: the slot arm of `intent_node_id_decode` and
`intent_node_id_decode_defect` both carry `WHERE s.candidates = 1`. Third, the member set is a fact
about the container, so a row per member per use site stores one copy of it per consuming
coordinate, which is the objection this plan already makes to a use-site-keyed candidate relation
under "Other solutions we've considered"; and it would mint the participant-keyed grain
`intent_node_id_instruction`'s comment reserves for a different question, that comment's grain being
about which *branch* of a multi-table consuming field's scope resolved which node type, a fact on the
departure axis rather than about membership in a container the author named.

So the instruction row is one per use site and the type it names is the container.

**The population edge, decided second, because the grain leaves it open: the container arm admits
every site, and the sibling defect view gains a fifth verdict for a container named where the
slot-side walk does not reach.** The arm cannot carry a predicate that matches the walk, and the
reason is worth stating rather than working around. What the walk's two answers fork on is not the
site: `resolveNodeIdRecordDecode` answers where the instruction's value descends into a Java slot and
`NodeIdLeafResolver.resolve` where it binds a table predicate, and both decode sites carry both
shapes, a `@service` argument and a lookup argument on a generated fetch field being `ARGUMENT`
alike. The store does state that fork, in `intent_node_id_decode_slot`, which drives off the
instruction relation; restating it inside `intent_node_id_instruction_live` would be one rule spelled
twice, and it would put consumer-side facts (an argMapping pair, a producer method's parameter names)
into a population this relation keeps SDL-shaped.

So the row is admitted and a verdict carries the refusal. That applies the incumbent's population
boundary rather than bending it: what that boundary forbids is a row that "neither resolves nor draws
either of the defect view's verdicts, breaking the partition to restate a message", and a container
at an unreached coordinate now draws one. Nor is the verdict a restatement, which is the boundary's
other half. What a container meets at those coordinates today is `NodeIdLeafResolver`'s `is not
@table-annotated`, a message describing a mistake the author did not make: the type is an interface
on purpose, and what is wrong is where it was named. That is the same complaint this item already
makes about `KEY_ARITY_EXCEEDS_SLOT` firing at an `UpdatableRecord<?>` parameter, and it takes the
same remedy: the verdict says what happened, and the walk's refusal at those coordinates forks its
message to say it too, so the two still agree in wording.

Four consequences carry the rest of the slice, and each is stated because a reader of this family
will look for it.

*The column that holds the named type is renamed and gains a kind beside it, and `basis` stays at
five.* `intent_node_id_instruction.node_type_name` promises "the node type the instruction names,
written or inferred; never NULL", and six relations read it directly and thread that promise forward
restating it: `intent_condition_param_decode`, `intent_node_id_decode_endpoint`,
`intent_node_id_decode_slot`, `intent_node_id_encode`, `intent_node_id_decode_landing_defect`, and
`intent_argument_filter_role`, whose `argument_node_id` CTE reaches the key shape by left-joining
`intent_resolved_node_key_shape` on it. The sixth is the one the next consequence turns on, and
two relations one rung further out (`intent_node_id_decode` and `intent_node_id_decode_defect`) read
the renamed column off the slot relation while keeping `node_type_name` as their own output name, per
the seam below. A container is not a node type, so the honest move is not to bend the promise but to rename the
column to what it actually holds, `resolved_type_name`, the type the instruction's basis resolved,
with `resolved_type_kind` beside it in a closed vocabulary of two, `NODE_TYPE` and `POLY_CONTAINER`.
The precedent is in the store already: `graphitron_field_navigation.navigated_type_name` is "a name
and never a binding, so a consumer joins `intent_resolved_type_binding`, `intent_poly_member` or
`graphql_type` on it according to what it actually needs to know". The kind is a column rather than a
predicate each reader re-evaluates, on `carries_reference_path`'s own justification in the same
relation. And `basis` stays at five deliberately: its comment defends those five as disjoint by
*authored* predicates, and a written `typeName:` is one authored rule whatever it resolves to, so
splitting `EXPLICIT_TYPE_NAME` in two would split one rule on a resolution fact, which is what the
new kind column is for. The cost is a mechanical rename across the family's carriers, their comments
and the fact-store tests that read the column; it is compiler- and gate-checked, and it is the price
of the column meaning one thing.

*Three incumbent predicates change, and the container rows are quiet everywhere else.* Most of the
family judges an instruction by reaching the node key through the named type, and a container never
resolves one: `graphitron_node_keycolumn`'s own column comment says "a type that is not a node has no
key columns here however well its table is keyed", by a foreign key into the node population. So
`intent_node_id_decode_defect` draws nothing on these rows, its join to
`intent_resolved_node_key_shape` missing; `intent_node_id_decode`'s table arm draws nothing, its
key-column child finding no positions to pair; `intent_node_id_encode` draws nothing at the
output-field site, inner-joining the key-column count on the same name; and
`intent_condition_param_decode` draws nothing on all three of its arms, on the same missing join,
which is the silence Out of scope discloses. Three relations reach the container by some other route
and each takes one predicate, `resolved_type_kind = 'NODE_TYPE'`, with the reason on its own comment.

`intent_node_id_decode_endpoint` resolves the *arrival* through `graphitron_tabletype` on the named
type, and `graphitron_tabletype` constrains no kind (`graphitron_table_entry` is keyed on the type
alone): so a container that carries `@table` itself, which is exactly the `SINGLE_TABLE_CONTAINER`
shape this item refuses, would draw a real endpoint row whose named type is not a node type, against
that view's own column comment, and `intent_node_id_decode_landing_defect` drives off the endpoint
and its hop child rather than off the key columns, so such a slot carrying an `@reference` path could
draw `PATH_STOPS_SHORT` about a decode that should be refused as a single-table container. The reason
on the comment: the arrival is the node type's own binding, and a container that binds a table has
one binding and no key.

`intent_argument_filter_role` is the one place a container row would not be quiet but wrong. Its
`argument_node_id` CTE reads `implicit` as `basis = 'TARGET_ID_NAME'`, false on
`EXPLICIT_TYPE_NAME`, so `wired` reduces through `NOT n.implicit` to `is_id AND NOT has_binding`: the
arity, shadowing and list clauses short-circuit, and the `COALESCE(ks.arity, 1)` default the missing
key shape produces is never read. A container-naming `ID` argument with no `@field(name:)` binding
would therefore draw `role = 'NODE_ID'` at precedence 4, where today it draws no precedence-4 row at
all and falls through to the name match or to nothing; and the flip propagates, `intent_condition_membership`
reading `role IN ('NODE_ID', 'NAME_MATCHED')` into its contributor set. The predicate restores that
role's stated meaning rather than patching around the flip: the comment says NODE_ID means "the
predicate's columns come from the resolved node key rather than from a name", and a container
resolves no key to take columns from. It also replaces the escape hatch that comment offers for a
keyless type, "such a type is a rejection's population", which this item invalidates by making a
keyless named type legal somewhere: the sentence becomes the kind fork, a container excluded here by
what it is rather than read as the single-column shape a node has when nothing says otherwise.

`intent_input_field_filter_role_live` is the same flip one step out, and it is why the edge is about
reach rather than about the `@service` coordinate alone. Its precedence-3 arm is driven by an `ID`-typed input field
carrying a `graphitron_field_node_id_entry`, left-joins the `node_id_at_table` CTE, and emits
`NODE_ID` where the join hits and `NONE` where it does not, the outer `role <> 'NONE'` dropping the
miss. Today a container-naming input field misses and is dropped; after the widening that CTE draws a
group for it wherever the root argument's scope table resolves, so the role flips at a read-side
filter input. The CTE reads `i.basis`, `i.path` and `i.site` and never the renamed column, so only the
population half of the widening reaches this relation, and the predicate it takes is the new kind
column and nothing else.

With those three, a container contributes to no filter surface at any site, and the flip is a claim
the Tests section pins rather than a discovery.

*The seam the rename dissolves.* On the two authored rungs, the instruction relation and
`intent_node_id_decode_slot` that carries its columns forward, the column is the type the instruction
resolved and the kind says which sort it is. On `intent_node_id_decode` the column stays
`node_type_name` and stays the node type whose key the decode yields, which on the polymorphic
destination is the member, one row per member. Two facts with two names and a keying between them,
which is the `intent_spelled_table` / `intent_bound_table` layering rather than one name meaning two
things at two rungs.

*The precedent is declined rather than claimed.* The conflation is not invented here:
`intent_node_id_instruction`'s comment already describes a use site drawing one row per branch for
the two bare inference bases at a multi-table container, so a `@service` producer field of that shape
already reports several node types at one use site and `candidates` already counts them. What that
shape reads today is nothing at all, `candidates = 2` silencing the slot arm for it exactly as it
would silence this item's, which is an argument against adding a second producer of the same
conflation rather than for it. That pre-existing hole is reported under Out of scope, not fixed here.
What the reading does show is that the relation welds two axes onto one row, the population (which
use sites carry the instruction) and the resolution (which node types that instruction stands for);
the shape above keeps the population axis at the instruction's own grain and moves the resolution
axis onto a relation of its own, which is the normalization "Other solutions we've considered"
describes and defers. It also answers what the reserved participant-keyed grain is still for: it is
the *departure* discriminator, recovering which branch of a multi-table consuming field's scope
resolved which node type, and for a container the member simply *is* the node type, so a participant
column there would restate `node_type_name` rather than complete a key. This item is not that
grain's second reader, and the grain stays reserved for the reader its own comment describes.

Six additions, each at the rung the existing relations reserve for it:

* *Record supertypes as a captured fact, and assignability as one derivation over it.*
  `sql_table_record_supertype` and the assignability view above it, both described under Slot typing:
  the capture sits at the catalog walk beside `sql_table`, one row per (table, supertype name), with
  a comment stating that it exists because the classpath census excludes the generated package, that
  `intent_jvm_ancestor` is its census-side twin, and why it holds the closure rather than edges; the
  view composes the two and is the one relation both the destination and the slot-typing verdict
  read.
* *Container members as a container-keyed rung, total, with the two predicates as columns.* A view
  `intent_node_container_member (graph_name, container_name, container_kind, member_type_name,
  is_table_bound, is_node_type)` over `intent_poly_member` (the `intent_` family's own name for the
  membership, which is where a derivation stands rather than on the captured `graphql_poly_member`
  under it), one row per member of every container and not only the admissible ones. Keyed on the
  container, because "which node types are members of `Applikasjon`" is a fact about `Applikasjon`
  and not about any slot that names it; keying it on a use site would store one copy of the answer
  per consuming coordinate. Total rather than filtered because three readers want three populations
  off it: the resolution axis below wants the members that are both, `MEMBER_NOT_NODE_TYPE` wants
  the table-bound members that are *not* node types and so could not read a filtered relation at
  all, and the LSP keyset wants the containers with at least one of each. Two flags rather than a
  name that bakes one caller's filter in, on `intent_bound_table.candidates`' own terms: a fact
  "stated as a column rather than left to each reader's own count", because whether it holds decides
  the reading. This is the
  `intent_spelled_table` / `intent_bound_table` layering, and the read side's per-participant
  candidate derivation can later re-source onto it.
* *The resolution axis as its own relation.* A view `intent_node_id_candidate_node_type (graph_name,
  resolved_type_name, node_type_name)`: one identity row for a resolved type that is a node type,
  one row per admissible member for one that is a container. It is what makes the destination below a
  branch inside the existing slot arm instead of a second arm, and it is keyed on the resolved type
  rather than on a use site, so the member set is stored once for the container however many
  coordinates name it. Two readers ask it on day one, the destination and the sibling defect view,
  which is the threshold the fact model sets for a derivation to get a name.
* *The instruction population widens where it lives.* `intent_node_id_instruction_live`'s
  `EXPLICIT_TYPE_NAME` arm joins the written `node_type_ref` to `intent_node_type`, so an instruction
  naming a container draws no row today, none downstream, and no defect: silent. The fix is a second
  `EXPLICIT_TYPE_NAME` arm at the grain decided above, one row per use site, `resolved_type_name` =
  the container, `resolved_type_kind` = `POLY_CONTAINER`, admitted where the written `node_type_ref`
  names a type whose `graphql_type.kind` is `INTERFACE` or `UNION` and that name is *not* in
  `intent_node_type`. Those two predicates are what keep it disjoint from the node-type arm by
  construction, the way the five bases are disjoint from each other: a container carrying `@node`
  resolves `NODE_TYPE` on the existing arm, which is the store's spelling of the walk's ordering
  rule, and no instruction draws both rows. One authored rule, two resolved kinds, which is why the
  kind is the new column and `basis` does not move. The population widens to every container-naming
  instruction, including one whose membership is defective (no `@table` members, a non-node member,
  a single-table container): those coordinates are the sibling defect view's to name, and admitting
  them is what gives it a population to be keyed on. It widens at every site, deliberately, on the
  edge decided above: the arm joins `instructed`, which carries all three sites, and takes no site
  predicate, because the fork the walk makes is the slot relation's and not the site's. A container
  named where the slot side does not reach draws the sibling view's site verdict, and the three
  predicates named under the consequences keep it out of every filter surface meanwhile.
* *A fifth destination, inside the slot arm rather than as a third arm.* `intent_node_id_decode`'s
  slot arm today joins `intent_resolved_node_key_shape` on the slot's named type; it instead joins
  `intent_node_id_candidate_node_type` on that name and the key shape on *its* `node_type_name`, so
  a node-typed instruction yields the one row it yields today and a container yields one row per
  member with that member's own `arity`. `WHERE s.candidates = 1` survives verbatim, the
  multiplicity having moved off the slot relation's partition and onto the resolution axis, and the
  `destination` `CASE` gains one branch, `POLYMORPHIC_RECORD`, forked on `resolved_type_kind` rather
  than on a re-derived predicate. A branch and not a third arm because a third arm would drive off
  `intent_node_id_decode_slot` a second time and re-join the key shape, which is what that view's
  own comment forbids ("they read different driving relations for different facts ... and neither
  re-joins the other's operands") in the relation the DDL calls the deepest derived read in the
  schema; `intent_node_id_decode_defect`'s comment makes the same argument against itself. The
  branch is admitted only where no member's record fails the ancestry test, a `NOT EXISTS` over the
  members rather than a per-member predicate, since one member that fails refuses the whole slot.
  The `destination` comment, which today says the slot arm's record is "the node type's own table's
  record", is the sentence this item rewrites, and the vocabulary goes from four values to five.
* *A sibling defect view.* Beside `intent_node_id_decode_defect`, a sibling
  `intent_node_id_polymorphic_decode_defect` with a closed verdict vocabulary:
  `MEMBER_NOT_NODE_TYPE` (one row per member that `intent_node_container_member` reports table-bound
  and not a node type), `NO_TABLE_MEMBERS` (no member reports table-bound),
  `SINGLE_TABLE_CONTAINER` (the container is itself in `graphitron_tabletype`, which admits an
  interface: `graphitron_table_entry` is keyed on the type and constrains no kind),
  `SLOT_NOT_SUPERTYPE_OF_MEMBER` (one row per member whose record does not admit the slot type, read
  off the assignability relation named under Slot typing rather than re-derived here), and
  `CONTAINER_NOT_AT_A_SLOT`, the population edge's verdict: the use site draws no
  `intent_node_id_decode_slot` row, so the value binds a table predicate or encodes rather than
  descending into Java, and the polymorphic rule does not reach it. That is an output field, an
  argument or filter input on a generated fetch field, and a `@service` argument no parameter is fed
  from, all three read as the slot relation's absence rather than re-derived, which is the same
  absence `intent_node_id_decode`'s own arm fork already reads and is what lets the instruction arm
  above carry no site predicate. One cause of that absence is excluded, the census's rather than the
  author's; the limits below state it. The first two verdicts
  are counts and complements over one relation rather than three joins of their own, which is what
  making the member relation total buys. Two grains in one relation, with
  `member_type_name` NULL on the three container-grain verdicts and set on the two member ones,
  determined by the verdict the way the incumbent family determines nullness by its discriminator.
  Precedence runs outward in: `CONTAINER_NOT_AT_A_SLOT` first, the coordinate deciding whether the
  rule applies at all before the member set is worth reading; then the two container verdicts, the
  remedy being the container's; then the two member ones. The
  walk's two slot-side messages are one verdict here, a slot typed as one member's record and a
  scalar slot both being the ancestry test failing, and the split is the walk shaping a remedy
  rather than a second fact. A sibling rather than new arms because the incumbent's whole design is
  "decided by arity alone, one pass over two driving relations" and these verdicts are decided on
  the member set and the slot type, a different fact base; and the two populations are disjoint for
  the reason spelled out under the grain above, the incumbent's join to
  `intent_resolved_node_key_shape` on the slot's resolved type missing on every container row, which
  the new view's comment states on the incumbent's terms rather than leaving true by accident. Projected
  into `Rejection` arms by `NodeIdPolymorphicDecodeDefects` beside `NodeIdDecodeDefects`, with the
  same `Verdict.of` drift guard and the same `intent_type_domain` narrowing, and wired as a new
  component of `StoreDetections` (the seam `FactCapture` fills), not as a call in `FactCapture`
  itself.

One limit on how much of the rule the store can state, and it is the incumbent's own population edge
rather than a hole this item opens. At an input-field site, which is the field report's own shape (a
hand-written input record whose member is typed `UpdatableRecord<?>`),
`intent_node_id_decode_slot.java_type` is the *bean's* type and not the member's: the slot relation
resolves the parameter at the root of the use site and deliberately does not walk into the class,
which is why `intent_node_id_decode_defect` excludes that site outright and calls the shape "owed an
emitter rather than a verdict". So the container-side half of the rule is stated at both sites, and
the slot-typing half, `SLOT_NOT_SUPERTYPE_OF_MEMBER` and the `POLYMORPHIC_RECORD` destination with
it, only where the slot relation types the slot the author annotated: a producer parameter, or a bean
that is itself a jOOQ record. The walk refuses the mistyped bean member on its own, so no build goes
silent; what narrows is the store's agreement. The new view's comment says so on the incumbent's
terms, and names what would close it: the descent from a bean parameter to the member receiving the
value, which is `intent_class_member_slot`'s territory and the walk the slot relation deliberately
declines to perform. Stating the edge is the point; a verdict that compared a container's type
against a member's key would refuse a bean the author was right to declare.

A second limit sits on the coordinate verdict rather than on the rule, it falls in one direction, and
it is why `CONTAINER_NOT_AT_A_SLOT` is not simply the slot relation's absence.
That absence has two causes and only one of them is the author's: the value binds a table predicate
or encodes, which is what the verdict is about, or the coordinate's producer class is one the
classpath census never reached, which is the capture's silence.
`intent_field_producer_method`'s comment already separates the two, no `jvm_class` row under the
graph's sources meaning the census never read the class and a class row with no method row meaning
the class declares no such method. So the verdict stands aside on the first: a coordinate whose
producer class no source in the graph declares draws nothing here. This was found in flight rather
than designed in, three coordinates in the reactor's own captures drawing the verdict for a class the
capture had not read, and a refusal an author cannot act on is the silence this family exists to
close rather than a second one to open. The stand-aside is one-directional by construction, so a
coordinate the census did reach is judged as before.

The walk's refusals and the store's verdicts are the same facts under two names, within the two limits
just stated; the pipeline tests below assert the walk, the fact-store tests assert the rows, and the existing
`NodeIdProducerSlotDecodePipelineTest` pattern (assert that the schema *builds* and that the slot's
transform is the decode, because a silent detection and a red build look alike from above) carries
over to the positive cases.

### Editor

`Behavior.NodeTypeBinding` in `LspVocabulary` maps `@nodeId(typeName:)` onto the node-type keyset,
and `Diagnostics` asks `questions.nodeTypeName` of the written value, so a schema this item makes
valid gets an editor diagnostic and no completion for the container. The keyset the LSP reads is
widened to the containers `intent_node_container_member` reports at least one table-bound node-type
member for, which is one of the three readings that relation is total for, so completion and
diagnostics move with the build from the same relation; a walk-side rule alone would fork the two
views. The keyset is keyed on the type name and not on the coordinate, so it says a container is a
legal value for `typeName:` and never that this is a coordinate the rule reaches; the coordinate
half is `CONTAINER_NOT_AT_A_SLOT`, which reaches the editor the way the incumbent family's verdicts
do, through the projected `Rejection` arms read ungated. Completing a container at a read-side
argument and then diagnosing it there is the same division the incumbent already runs, the keyset
answering what the value may name and the defect rows answering what happened.

## Implementation

Shipped at `ad09411`, with the round-4 rework at `50492f1` (three test cases, no production code: the
two LSP diagnostic cases and the read-side refusal's pipeline case named under Tests). The round-5
rework rides in the commit carrying this revision: the walk's one-member refusal removed, both
candidate-count invariants relaxed to one, and the two cases that pin the admission. The file-by-file
list below is what landed, and it stays rather than collapsing to the notes alone because the Done
gate reads it against the Design section above.

Symbol-anchored, no line numbers; re-find by search at pickup.

* `BuildContext.resolveNodeIdRecordDecode`: the node-type test moves ahead of the `@table`-object
  test (see the ordering rule under Design, which states what that reordering may not change), and
  the `is not @table-annotated` arm then forks on `GraphQLInterfaceType` / `GraphQLUnionType` into
  the member walk, returning the new `Polymorphic` arm or one of the three container refusals.
  `NodeIdLeafResolver.resolve` keeps its refusal, and forks its message where the named type is a
  multitable interface or union: `is not @table-annotated` describes a mistake that author did not
  make, and the accurate refusal says the polymorphic spelling is a `@service` slot rule and this
  coordinate is not one. That is the wording `CONTAINER_NOT_AT_A_SLOT` states as a fact, so the walk
  and the store still agree at the refused coordinates rather than only at the reached ones.
* `InputBeanResolver.buildJooqRecordLeaf`: on `Polymorphic`, `isAssignableFrom` from the member's
  loaded class against each candidate's live record class (the catalog holds it; add an accessor on
  `JooqCatalog` beside `findTable` if none exposes `Table.getRecordType()`), then the new leaf; a miss
  is the candidates-and-shared-supertypes refusal. `singleValuedMemberDeferral` is unchanged (see Out
  of scope).
* `ServiceCatalog.nodeIdSlotExtraction`: on `Polymorphic`, load the parameter's raw type by name
  (one `List` unwrapped, as `takesTheNodeTablesRecord` does today), the same test, the same leaf, and
  a refusal on a miss instead of the `ThrowOnMismatch` fall-through.
* `CallSiteExtraction`: the `NodeIdDecodePolymorphicRecord` leaf with its compact-constructor
  invariants and the admitted-slot-type wrapper the classifier mints; every exhaustive switch over
  the sealed interface gains its arm (the compiler lists them). Its candidate-count invariant, and
  the twin on `BuildContext.NodeIdRecordDecode.Polymorphic`, admit one candidate and refuse only the
  empty list, per the member-count rule under Resolution.
* `InputBeanInstantiationEmitter`, `ServiceMethodCallEmitter.scalarLeaf`, `ArgCallEmitter`: render
  the `decode<Container>Record` / `…RecordList` call and collect the helper onto the `*Fetchers`
  class the way the single-type helpers are collected today; the helper body reuses
  `RecordDecodeFragments.decodeHelper` per candidate and the multi-candidate failure message.
* `RecordDecodeFragments`: a fourth `decodeHelper` overload passing `return null` as the shared
  body's `mismatchThrow`, the null-returning form step 2 above needs; the three existing overloads
  all throw.
* `NodeIdDecodeFailure`: the multi-candidate message beside the single-type one, moved off
  `MultiTablePolymorphicEmitter.dispatchFailureThrow` (private there) so both grains read one
  spelling; `MultiTablePolymorphicEmitter` then calls it.
* `FetchersHelperNames`: a name for the container helper beside `decodeSingular` / `decodeList`.
* `CatalogFactCapture` and `graphitron-model.sql`, in the order a build can stay green through:
  1. the `node_type_name` -> `resolved_type_name` rename plus the `resolved_type_kind` column on
     `intent_node_id_instruction` and its `_live` rule view, threaded through all six relations that
     read the column directly (`intent_condition_param_decode`, `intent_node_id_decode_endpoint`,
     `intent_node_id_decode_slot`, `intent_node_id_encode`,
     `intent_node_id_decode_landing_defect`, `intent_argument_filter_role`) and through the two that
     read it off the slot relation while keeping `node_type_name` as their own output name
     (`intent_node_id_decode`, `intent_node_id_decode_defect`), with `NODE_TYPE` the only value any
     existing arm writes, so this step changes no row;
  2. `resolved_type_kind = 'NODE_TYPE'` on the three relations the population edge names,
     `intent_node_id_decode_endpoint`, `intent_argument_filter_role`'s `argument_node_id` CTE and
     `intent_input_field_filter_role_live`'s `node_id_at_table` CTE, each with its reason on its own
     comment, and the filter-role comment's keyless-type escape hatch replaced by the kind fork;
  3. `sql_table_record_supertype` capture and DDL, the assignability view over it, and
     `intent_node_container_member`;
  4. the container arm on `intent_node_id_instruction_live` (kind `POLY_CONTAINER`, `basis` staying
     `EXPLICIT_TYPE_NAME`) with the amended population sentence;
  5. `intent_node_id_candidate_node_type`, the slot arm re-sourced onto it, `POLYMORPHIC_RECORD` in
     the `destination` `CASE` and the rewritten `destination` comment (four values to five);
  6. the sibling defect view.

  Plus the relation register rows and the `meta_relation` declaration (or
  `undeclared-relations.txt` entry) each new relation owes, and the comment audit the rename and the
  new destination owe at both rungs, per the seam stated under Fact store. What the grain decision
  buys is that step 2 is three predicates and no re-derivation: `candidates` keeps its definition and
  its comment, `intent_node_id_decode_defect` keeps every predicate and its whole population (step 1
  does touch its text, which selects and joins on the renamed column), and the slot arm gains a join
  and a `CASE` branch rather than a sibling arm.
* `graphitron-model`: `NodeIdPolymorphicDecodeDefects` beside `NodeIdDecodeDefects`, a new
  `StoreDetections` component, filled where `FactCapture` fills the others.
* `graphitron-lsp`: the node-type keyset behind `Behavior.NodeTypeBinding` re-sourced to include
  the containers with at least one table-bound node-type member, for both completion and
  diagnostics.
* Docs, per the draft below: `docs/manual/reference/directives/nodeId.adoc` (a subsection after
  "Decoding into a producer parameter named for the argument", and a Constraints bullet) and
  `docs/manual/how-to/global-id.adoc` (a subsection after "Multitable filter inputs", the last of
  the two read-side siblings under "Decode-side errors", rather than between them).

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

For `global-id.adoc`, the mutation-side sibling in the same voice, using the `AddressOccupant` schema
that page already uses, showing the request, the service's `instanceof` dispatch, and the foreign-id
error text (identical to the one shown above it). It goes after "Multitable filter inputs" rather
than between the two read-side subsections, so the read side's argument and filter siblings stay
adjacent and the write side follows both.

## Tests

* Pipeline tier (`graphitron`, beside `NodeIdRecordInputBeanPipelineTest` and
  `NodeIdProducerSlotDecodePipelineTest`, over the test catalog's `Film | Inventory` or
  `Customer | Staff` union with `@node` members): an interface-typed `@nodeId` on a bean member typed
  `UpdatableRecord<?>` classifies clean and the slot's leaf carries the candidates in member order,
  asserted over the leaf's own candidate list rather than over the emitted source text (the dispatch
  itself is pinned at the execution tier, where it is behaviour rather than a string); the same at
  `TableRecord<?>`, `Record`, and the list shape; the same member typed
  as one candidate's record is refused naming the candidates and the shared supertypes; a scalar
  member is refused; a container with a non-node `@table` member is refused naming it; a `@table
  @discriminate` interface that is not a node type is refused with both remedies; an
  `UpdatableRecord<?>` slot over a candidate whose record is a `TableRecordImpl` is refused naming
  that candidate. The same matrix at a producer parameter, including that the old
  `KEY_ARITY_EXCEEDS_SLOT` no longer fires for an `UpdatableRecord<?>` parameter under a polymorphic
  `typeName`. A container with one admissible member decodes, carrying its one candidate and emitting
  the same container helper with one dispatch arm, which is the member-count rule under Resolution as
  a case rather than as prose. One case is not a slot's: a container named at a read-side lookup
  argument is refused for its coordinate rather than for its kind, which is the wording that has to
  agree with `CONTAINER_NOT_AT_A_SLOT` and which the store's rows cannot pin.
* Fact-store tier (`graphitron-model`, `intent` package, beside `NodeIdDecodeDestinationTest` and
  `NodeIdDecodeDefectTest`): seeded anchors for `sql_table_record_supertype`,
  `intent_node_container_member` (a container with a non-node `@table` member, and one with none,
  both drawing their rows with the flags they draw), the assignability view (a slot typed above a
  `recordImplements` interface resolving off the captured closure, which is the one leg that
  relation has),
  `intent_node_id_candidate_node_type` (the identity row for a node type and one row per member for
  a container, which is what makes the destination one arm), the one instruction row per use site
  with `resolved_type_kind = 'POLY_CONTAINER'` and `basis = 'EXPLICIT_TYPE_NAME'` (and that a
  `@node`-carrying container draws `NODE_TYPE` instead, the kind fork being a claim and not a hope),
  the `POLYMORPHIC_RECORD` rows (one per member, each with that member's arity, at a
  producer-parameter slot where the store can see the slot's type), each of the five verdicts, and a
  one-member container drawing its one candidate row, its one destination row and no verdict, which is
  the store half of the member-count rule and the reason the three surfaces answer that schema alike.
  The population edge gets its own three, all at coordinates the walk refuses, so the widening's
  reach is a claim and not a discovery: a container-naming `@nodeId(typeName:)` argument on a
  generated fetch field draws `CONTAINER_NOT_AT_A_SLOT` and draws no `intent_argument_filter_role`
  row with `role = 'NODE_ID'`, the same argument keeping whatever role it carried before the widening;
  a container-naming filter input under such a field draws the same verdict and still no live
  `intent_input_field_filter_role_live` row, its precedence-3 arm landing `NONE`; and a container at
  an output field draws the verdict and no `intent_node_id_encode` row.
  Three further negative assertions, which are the ones the grain decision is answerable for: `candidates`
  is 1 at a polymorphic use site however many members the container has, so a two-member container
  is still told apart from a two-overload producer; a polymorphic slot draws no row in
  `intent_node_id_decode_defect`, which holds because that view joins
  `intent_resolved_node_key_shape` on the slot's resolved type and a container resolves none, so the
  seeded fixture asserts the absence *and* that the same use site's `candidates` is 1, pinning that
  the quiet comes from the missing key shape rather than from the ambiguity filter; and a
  `@table @discriminate` container at a slot draws no `intent_node_id_decode_endpoint` row and hence
  no `intent_node_id_decode_landing_defect` row, which is the one incumbent predicate this item
  adds and the one place the quiet is a predicate rather than a missing join. The mechanical
  enforcers every new relation owes:
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
* LSP tier, the two halves the Editor section separates. The keyset half is four cases over the same
  container SDL, two in `NodeTypeCompletionsTest` and two in `DiagnosticsTest`: a container with node
  members completes and draws no unknown-node-type diagnostic, and one with none is offered by
  neither, no completion and the diagnostic still firing. The two
  diagnostic cases share a fixture deliberately, so the refusal is the silence's control: a container
  admitted by a store that answered nothing would look the same from above as one admitted by the
  keyset. The coordinate half owes no LSP case of its own. A `CONTAINER_NOT_AT_A_SLOT` verdict reaches
  the editor as the rejection residue the build writes, and `RejectionSeverityCoverageTest` replays
  every `Rejection` permit generically, so a per-verdict test here would restate that meta-test; what
  the editor does owe at that coordinate is the completion, which is one of the two completion cases
  above.

## Out of scope

* `InputBeanResolver.singleValuedMemberDeferral`: a `@nodeId` on a *scalar* member of a hand-written
  bean, at one concrete type, is refused as deferred today. This item leaves that refusal in place and
  does not depend on lifting it: the polymorphic slot is a record supertype, which lands on the
  record arm, and a scalar slot has nowhere to carry the type in any case.
* Bare `@nodeId` at a slot inheriting a *polymorphic* target (the read side's rule): a mutation
  returning a payload has no return-type candidate set, and inventing one from `@mutation(table:)`
  would be a new inference. The interface has to be named.
* The decoded-id value type the issue proposes; see below.
* The pre-existing hole the grain decision reports rather than fixes: a bare `@nodeId` whose two
  inference bases resolve one node type per branch at a multi-table container draws several
  instruction rows at one use site, so `intent_node_id_decode_slot.candidates` counts them and the
  slot arm of `intent_node_id_decode` produces nothing for that shape. That is the conflation this
  item declines to add a second producer of, and closing it is the re-keying
  `intent_node_id_instruction`'s comment describes (the participant-keyed grain), not a change any
  relation this item touches can make.
* A `@condition` parameter bound to a polymorphic `@nodeId` slot: `intent_condition_param_decode`
  joins the key shape on the slot's resolved type, so a container row draws no exemption there. That
  relation's own comment says absence is an assertion rather than a gap ("presence here is the whole
  of what says the exception applies"), so the silence asserts the declared-type extraction rule at
  a coordinate where a polymorphic decode is now legal. This item neither widens that relation nor
  claims the assertion is right; the walk refuses what it refuses, and what a condition method
  should receive from a polymorphic slot is its own question. Its population edge is stated on that
  view's comment in the same edit, so the new silence is disclosed rather than latent.
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
* *A site predicate on the container arm*, admitting the instruction only where the slot-side walk
  reaches it. Rejected under Fact store's population edge: the predicate that would match the walk is
  "does the value descend into a Java slot", which is `intent_node_id_decode_slot`'s whole content and
  drives off the instruction relation, so spelling it on the instruction arm is one rule spelled twice
  and pulls argMapping pairs and producer parameter names into an SDL-shaped population. A coarser
  predicate on `site` does not match the walk at all, both decode sites carrying `@service` slots and
  read-side coordinates alike.
* *Admitting every site and arguing the rows are right*, leaving the filter-role reading as it falls.
  Rejected on the reading itself: a container-naming argument would draw `NODE_ID` in
  `intent_argument_filter_role`, whose own comment says that role means the predicate's columns come
  from the resolved node key, and a container resolves none, so the classifier would be handed a
  filter contribution with nothing behind it and `intent_condition_membership` would carry it into a
  contributor set. The rows are wrong, which is why the population edge pays for them with a verdict
  and three predicates instead.
* *Member-keyed instruction rows*, one row per member at one use site with the member as the resolved
  type. Rejected under Fact store, where the argument is spelled out: it reports a determinate schema
  as producer ambiguity on `intent_node_id_decode_slot.candidates`, it silences the very arm this
  item needs (`candidates = 1`), and it stores a container fact once per consuming coordinate. It
  would also break `intent_condition_param_decode` outright, whose key is the coordinate plus the
  condition class and method: several member rows there give one method several `key_arity` values
  at one coordinate.
* *A use-site-keyed candidate relation* joined into the slot arm, the same storage one relation
  later. It stores one copy of a container fact per consuming coordinate, and it mints a second
  spelling of the participant-keyed grain the instruction relation's comment reserves for the
  departure axis. Keying the resolution axis on the resolved type instead, which is what
  `intent_node_id_candidate_node_type` does, answers the same question once per container.
* *Splitting the instruction relation's two axes now*: the population axis keyed on
  `(graph, site, coordinate)` and a resolution relation keyed on
  `(graph, use_site, node_type_name)` carrying the departure where it has one. That is the
  normalization this item's shape half-performs and defers, and it is the change that would also
  close the bare-inference silence named under Out of scope, since both silences come from welding
  the two axes onto one row. Deferred because it re-keys the endpoint, slot, column, encode and
  condition-decode readers plus the register and the index, over the store's most expensive family,
  which is a change worth its own item rather than a rider on this one.
* *Widening `NodeIdDecodeRecord`* to carry a candidate list instead of a sibling leaf. Rejected
  under Emitted glue: every consumer of the existing leaf reads one table off it.
* *New arms on `intent_node_id_decode_defect`* instead of a sibling view. Rejected under Fact store:
  the existing view's verdicts are a function of arity over two relations, and these are not.
* *A new directive argument* (`@nodeId(anyOf: [...])`) listing the implementations. The interface
  already lists them, and a hand-maintained list drifts from it when an implementation is added.

## Retired vocabulary

Two column names, both renamed rather than removed, and both compiler- and gate-checked so nothing
reads the old spelling silently:

* `intent_node_id_instruction.node_type_name` and its `_live` sibling: now `resolved_type_name`,
  with `resolved_type_kind` beside it. The old name promised a node type and the column now holds
  either a node type or a polymorphic container.
* `intent_node_id_decode_slot.node_type_name`: now `resolved_type_name`, plus a new
  `resolved_type_kind`, carried forward from the instruction it drives off.

Both survive under their old names one rung out: `intent_node_id_decode`,
`intent_node_id_decode_defect`, `intent_node_id_decode_endpoint`, `intent_node_id_encode` and
`intent_condition_param_decode` each keep `node_type_name` as their own output name and each still
means a node type there. That is the seam the Design section describes rather than an inconsistency,
and the destination relation's own column comment states it.

No Java symbol is retired. `intent_argument_filter_role`'s comment loses one sentence, the
keyless-type escape hatch ("such a type is a rejection's population"), which the widening
invalidates by making a keyless named type legal somewhere; it is replaced by the kind fork.

## Reviewer findings

### Round 1 (2026-09-08, Spec -> Ready, reviewer session 01PafYmb9t83c4Z5rmUdD9j7)

Verdict: revisions requested; status stays `Spec`. One blocking finding on the second gate question
(does the proposed solution fit the architecture we have), located in the fact-store slice. The first
gate question passes: the goal is legible without reading the phase list, and it is reachable here.

The goal reads clean. An author writes `@nodeId(typeName: "<Interface>")` on a `@service` input slot,
types the receiving Java slot as something every implementation's generated record is, and receives a
populated record whose class says which implementation the id named; a foreign id fails the request
with the wording the read side already gives, before the service runs. What they write today is a
bare `ID!` plus a hand-rolled `peekTypeId`/`decodeValues` dispatch, which is the wire-format
knowledge the directive exists to remove, and the field report shows it written twice. Viability
holds too: the four "what exists today" facts are accurate to the symbol. `resolveNodeIdRecordDecode`
does reject a non-`@table` object type with the quoted message fragment; `buildJooqRecordLeaf` does
compare the declared record class by string equality and `isJooqRecord` does already route an
`UpdatableRecord<?>`-typed member into it, so the bean path really needs no new arm;
`takesTheNodeTablesRecord` really compares a javapoet `TypeName` and really falls through to
`ThrowOnMismatch`; `MultiTablePolymorphicEmitter.dispatchFailureThrow` is private and its message is
verbatim what the plan quotes. `intent_jvm_ancestor`'s own comment states the gap
`sql_table_record_supertype` is proposed to fill ("the scan drops nested classes and the generated
jOOQ package"), and `intent_node_id_instruction`'s comment does reserve the participant-keyed grain
in the exact spelling the plan reuses, including the sentence that the arm "is worth having the day a
second one asks". `intent_node_id_decode.destination` is a closed vocabulary of four, so "a fifth
destination" is the right count. `AddressOccupant = Customer | Staff` exists in the sakila-example
schema with both members `@table @node`, and both documentation anchors exist under the headings
named. Every code, test and symbol the plan names exists as named, with one exception noted below.

**Blocking: the instruction grain the plan picks and the `candidates = 1` gate on the arm it extends
are in conflict, and the plan's two arguments about the store assume opposite values for one
column.**

`intent_node_id_decode`'s slot arm reads `FROM intent_node_id_decode_slot s JOIN
intent_resolved_node_key_shape k ... WHERE s.candidates = 1`, and
`intent_node_id_decode_slot.candidates` is `COUNT(*) OVER (PARTITION BY graph_name, use_site)`. The
plan's new instruction arm draws "one row per member" at one use site, so a two-member container
gives `candidates = 2` and that arm emits nothing at all: `POLYMORPHIC_RECORD` cannot be produced
where the plan puts it. That is not a filter the implementer can quietly drop, because `candidates`
carries a meaning the neighbouring relations lean on. Its own comment reads any value above one as
"an overloaded method or a class two classpath entries declare, and this relation distinguishes
neither", and the slot relation's comment makes that reading load-bearing: keeping the ambiguous rows
is what "makes that use site a decode with several candidate slots, which a consumer refuses to carry
out rather than mistaking for a predicate". Member multiplicity arriving on that column is a legal
shape being reported as producer ambiguity, not just rows being dropped.

The same fork shows up as an internal contradiction. The sibling-defect-view bullet argues the two
populations are "disjoint by construction (the incumbent joins `intent_resolved_node_key_shape` on
`node_type_name`, which a container never resolves)". That holds only if `node_type_name` carries the
container; the instruction bullet one paragraph earlier sets it to the *member*, which does resolve
there. So the incumbent's join succeeds on the new rows, its remaining predicates are satisfied for
the motivating shape (`site = 'ARGUMENT'`, `carrier = 'NAMED_PARAMETER'`, `java_type` of
`org.jooq.UpdatableRecord` unequal to both `record_class` and the sole key column's Java type), and
the only thing keeping it quiet is `candidates = 1`: the same predicate that silences the arm the
item needs. The two bullets cannot both be describing the relation this item builds. The Tests
section's "a polymorphic slot draws no row in `intent_node_id_decode_defect`" therefore currently
asserts a true outcome for a reason the plan does not give, and has to be re-derived under whichever
shape is chosen.

Two shapes would satisfy this, and the pick decides the DDL, whether the incumbent relations are
amended, and whether the sibling-view argument survives. Either is defensible; the plan has to choose
one in the body rather than leave it to pickup.

* *Member-keyed instruction rows*, what the instruction bullet says. Then state how a reader tells
  member multiplicity from slot ambiguity: a second count column, a redefined `candidates` with its
  comment rewritten, or the container column as the discriminator. Name which existing
  `candidates = 1` readers change and how, the decode slot arm and the defect view being the two in
  this family.
* *One instruction row per use site carrying the container*, what the disjointness argument assumes.
  Then say where member multiplicity lives instead (`intent_node_container_member`, joined at the
  decode arm) and what the reserved participant-keyed grain is still for, since this item would no
  longer be the second reader that asks for it.

One fairness note so the revision does not chase a phantom: the conflation is not invented by this
item. `intent_node_id_instruction`'s comment already describes a use site drawing one row per branch
for the two bare inference bases at a multi-table container, so a `@service` producer field of that
shape already reports member multiplicity as `candidates`. The plan may legitimately claim that as
precedent, but it has to claim it out loud and say what the arm then reads, because as written the
arm reads nothing.

### Non-blocking

* `RecordDecodeFragments.decodeHelper` has no null-returning form. All three public overloads throw
  on an arity mismatch, either the generic `GraphqlErrorException` or `NodeIdDecodeFailure`'s client
  error; the only `return null` is the not-a-`String` case. So "the candidate's record-materialising
  helper in its null-returning form ... emitted through `RecordDecodeFragments.decodeHelper`" is a
  fourth overload passing `return null` as the `mismatchThrow`, which the Implementation list does
  not name. It follows that file's existing private-shared-body pattern and has no design
  consequence, but the claim that the slot side "needs the three steps composed at one slot, not new
  primitives" is slightly stronger than the tree supports.
* The ordering rule says `resolveNodeIdRecordDecode` "asks 'does `typeName:` name a node type' first,
  exactly as today". Today it asks the `@table`-object question first and node-ness only after,
  through `resolveTargetKeys`, whose parameter is a `GraphQLObjectType` and so cannot be handed an
  interface. The ordering the design wants is a change rather than the status quo. The plan's own
  parenthetical already routes the consequence for a `@node`-carrying interface to pickup, so this is
  precision rather than a second fork.
* `dispatchFailureThrow` is to be "lifted to a shared fragment". There is already a home for exactly
  this fragment, `no.sikt.graphitron.render.NodeIdDecodeFailure`, which holds the single-type
  two-branch message and whose class comment gives the same reason this item gives (one bad id must
  fail the same way at every grain that reads it). Worth naming as the destination so the multi-
  candidate message lands beside its single-type sibling instead of in a third place.
* In `global-id.adoc` the plan places the new subsection "after the polymorphic-argument subsection",
  which puts it between `=== A @nodeId argument on a field returning an interface or union` and
  `=== Multitable filter inputs`, splitting the two read-side siblings. Placing it after the filter
  subsection may read better. Author's call; it bears on neither gate question.

### Round 2 (2026-09-08, Spec -> Ready, reviewer session 012LrTc9ZDzhB88ERqQAQTDw)

Verdict: revisions requested; status stays `Spec`. Round 1's blocking finding is answered, and
answered well: the grain is picked and argued rather than left to pickup, `candidates` keeps the one
meaning its neighbours lean on, member multiplicity moves onto relations keyed where the fact lives,
and the fifth destination lands as a `CASE` branch instead of a second naming of the deepest derived
read in the schema. All four non-blocking notes are folded in. The first gate question passes again.
One new blocking finding on the second gate question, in the same slice and of the same kind as round
1's: the plan states an audit of the widening and the audit is incomplete.

The goal, in my own words and without the phase list: an author who today spells a polymorphic
mutation input as a bare `ID!` and hand-writes a `peekTypeId` plus `decodeValues` dispatch against
three typeIds writes `@nodeId(typeName: "<Interface>")` instead, types the receiving Java slot as
something every implementation's generated record is, and receives a loaded record whose class tells
them which implementation the id named. A foreign id fails the request before the service runs, in
the wording the read side already uses. Reachable here: the four "what exists today" facts are
accurate to the symbol, `RecordDecodeFragments` really has three throwing public `decodeHelper`
overloads over one private shared body so the fourth is the one primitive, `sql_table`'s primary key
really is `(source_name, table_schema, table_name)` so the proposed capture relation keys cleanly
onto it, `intent_node_id_decode.destination` really is a closed vocabulary of four, and
`graphitron_field_navigation.navigated_type_name` carries the name-and-never-a-binding sentence
verbatim as quoted. Fixtures and anchors check out: the `Signal` interface carries `@table` and
`@discriminate` in `multischema.graphqls`, `AddressOccupant = Customer | Staff` is a fully
node-backed union in the sakila-example schema, and both documentation subsections exist under the
headings named.

**Blocking: the widened instruction population has a sixth reader and a site edge, and the plan names
neither. "Everywhere else the container rows are quiet by construction" is not true as written.**

The seam paragraph says five carrier relations thread `node_type_name`'s promise forward, and step 1
lists them. Six relations read `intent_node_id_instruction.node_type_name` directly:
`intent_condition_param_decode`, `intent_node_id_decode_endpoint`, `intent_node_id_decode_slot`,
`intent_node_id_encode`, `intent_node_id_decode_landing_defect`, and `intent_argument_filter_role`,
whose `argument_node_id` CTE reaches the key shape with `LEFT JOIN intent_resolved_node_key_shape ks
ON ks.graph_name = i.graph_name AND ks.type_name = i.node_type_name`. The sixth is the one the plan
does not name, and it is the one where the container rows are not quiet.

Follow a container-naming instruction into it. `implicit` is `basis = 'TARGET_ID_NAME'`, so on
`EXPLICIT_TYPE_NAME` it is FALSE, and `wired` reduces through `NOT n.implicit` to `is_id AND NOT
has_binding`: the arity, shadowing and list clauses short-circuit, and the `COALESCE(ks.arity, 1)`
default the missing key shape produces is never read. So a container-naming `ID!` argument with no
`@field(name:)` binding draws `role = 'NODE_ID'` at precedence 4, where today it draws no row in that
CTE at all and the argument falls through to the name match or to nothing. That is a row flip on an
incumbent relation, at this item's own supported coordinate (the plan's "the same shape holds at a
top-level `@service` argument"), and it propagates: `intent_condition_membership` reads
`intent_argument_filter_role` `WHERE r.role IN ('NODE_ID', 'NAME_MATCHED') AND r.suppressed = FALSE
AND r.lookup_key = FALSE` into its contributor set. The relation's own comment says NODE_ID means
"the predicate's columns come from the resolved node key rather than from a name", and its one escape
hatch for a keyless type is that "such a type is a rejection's population", which is exactly what
this item stops it from being.

`intent_input_field_filter_role_live` is the second instance, one step further out. Its
precedence-3 arm is driven by an `ID`-typed input field carrying a `graphitron_field_node_id_entry`
and left-joins `node_id_at_table`, emitting `NODE_ID` when the join hits and `NONE` when it does not.
Today a container-naming input field misses that join, lands `NONE`, and is dropped by the outer
`role <> 'NONE'`. After the widening, `node_id_at_table` draws a group for it wherever the root
argument's scope table resolves, so the role flips to NODE_ID; that condition holds at a read-side
filter input rather than at a `@service` payload, which is why this one is the site edge below rather
than a second flip at the item's own coordinate.

That site edge is the other half of the finding, and it is the sharper half. The incumbent
`EXPLICIT_TYPE_NAME` arm is site-agnostic: it joins `instructed`, which carries all three sites, to
`intent_node_type` on `node_type_ref`. The plan's second arm swaps that join for a kind test plus the
anti-join and says nothing about the site, so it is site-agnostic too, and `NodeIdLeafResolver.resolve`
keeps its refusal by the plan's own decision. A container named at an output field, or at an argument
or filter input on a fetch field, is therefore a coordinate the walk refuses and the store admits. At
an output field the row is silent, `intent_node_id_encode` inner-joining the key-column count on
`i.node_type_name`; at a read-side argument it is the NODE_ID role above. Neither draws a verdict:
the sibling view's four verdicts are decided on the member set and the slot type, and none of them
says "named where the slot-side walk does not reach". `intent_node_id_instruction`'s own population
boundary rules that shape out: "admitting them would put an instruction in the population that
neither resolves nor draws either of the defect view's verdicts, breaking the partition to restate a
message". The plan's justification for admitting defective containers ("those coordinates are the
sibling defect view's to name") covers the membership-defective container and not this one.

What would satisfy the finding is the population edge stated as deliberately as the grain now is.
Three shapes, and any of them is defensible; the pick decides the DDL and one relation's comment:

* *A site predicate on the new arm*, admitting the container only where the slot side can resolve it.
  Then say what states the refusal at the refused sites, since the walk's message becomes the only
  signal and this plan elsewhere holds the store to agreeing with the walk.
* *Admit every site and give the sibling view a fifth verdict* for a container named where the
  slot-side walk does not reach, which keeps the partition whole on the incumbent's own terms.
* *Admit every site and argue the rows are right*, which then owes the filter-role reading an answer
  rather than leaving it to be discovered.

Under all three, `intent_argument_filter_role` joins step 1's rename list, the seam paragraph's count
goes from five to six, and the NODE_ID role for a container needs either a predicate excluding it or
a sentence on that relation's comment replacing the rejection's-population escape hatch this item
invalidates. The Tests section's negative assertions are the right instinct and want one more in the
same spirit: that a container-naming argument draws whatever role the pick decides on, so the flip is
a claim rather than a discovery.

**Response.** Confirmed at the DDL, both halves. `intent_argument_filter_role` is the sixth direct
reader and its `argument_node_id` CTE reduces exactly as described, and
`intent_input_field_filter_role_live`'s `node_id_at_table` draws its group off `basis`, `path` and
`site` with no reference to the renamed column. Taken the second of the three shapes, admit every
site and give the sibling view a fifth verdict, and the choice is now argued in the body as
deliberately as the grain, under a new "population edge, decided second" paragraph in Fact store. The
reason the first shape is not available is stated there rather than asserted: the predicate that
would match the walk is "does the value descend into a Java slot", which is
`intent_node_id_decode_slot`'s whole content and drives off the instruction relation, so putting it on
the instruction arm is one rule spelled twice and pulls consumer facts into an SDL-shaped population;
a `site` predicate does not match the walk at all, both decode sites carrying `@service` slots and
read-side coordinates alike. The third shape is rejected on the filter-role reading itself, and both
rejections are now in "Other solutions we've considered". `CONTAINER_NOT_AT_A_SLOT` reads the slot
relation's absence, which covers the output field, the read-side argument and filter input, and a
`@service` argument no parameter is fed from; it takes precedence over the four existing verdicts,
the coordinate deciding whether the rule applies before the member set is worth reading. The
partition holds on the incumbent's own terms, and the verdict is not a restatement: what a container
meets at those coordinates today is `is not @table-annotated`, describing a mistake the author did not
make, so `NodeIdLeafResolver.resolve` now forks its message there too and the two surfaces agree in
wording rather than only in outcome. Under that pick the consequence bullet is no longer "one
incumbent predicate, and exactly one": it is three, the endpoint's plus the kind predicate on each
filter-role relation, each with its own reason, and the filter-role comment's keyless-type escape
hatch is replaced by the kind fork rather than left invalidated. `intent_argument_filter_role` joins
step 1's rename list, the seam paragraph names all six readers plus the two that read the column off
the slot relation, and Tests gains the three assertions the finding asks for, one per refused
coordinate.

### Non-blocking

* Step 2's aside says `intent_node_id_decode_defect` "keeps its body verbatim". It selects
  `s.node_type_name` and joins on it, so the rename does touch its text. The intent, that no
  predicate and no population changes there, holds and is the part that matters; the wording
  overstates it.
* `intent_input_field_filter_role_live` also reads the instruction relation for `i.basis` and
  `i.path`, which the rename does not reach, so only the population half of the finding above lands
  on it.

**Response to both.** The aside now says what it means: step 2 is three predicates and no
re-derivation, `intent_node_id_decode_defect` keeping every predicate and its whole population, with
the parenthetical that step 1 does touch its text. And the `intent_input_field_filter_role_live`
paragraph in the consequences says the CTE reads `basis`, `path` and `site` and never the renamed
column, so only the population half reaches it and the predicate it takes is the new kind column
alone.

### Round 3 (2026-09-08, Spec -> Ready, reviewer session 01XoeWvqCi2MkseUEMSjWWn9)

Verdict: signed off; status moves to `Ready`. Round 2's blocking finding is answered at the DDL
rather than in prose, and both gate questions pass. No blocking findings.

The goal, in my own words and without the phase list: a consumer whose mutation takes the global id
of any one of several table-backed types behind an interface, deactivate *an application* where an
application may be a Feide, Maskinporten or Maskinbruker application and each is its own table and
node type, cannot say so in the schema today. `@nodeId(typeName:)` admits one `@table` object type,
so the field is spelled as a bare `ID!` and the service base64-decodes the wire id itself, peeks the
type prefix and branches on three typeIds by hand: the wire-format knowledge in author code that the
directive exists to remove, and the field report shows it written twice. After this lands they write
`@nodeId(typeName: "Applikasjon")`, type the receiving Java slot as something every implementation's
generated record is, and the generated fetcher decodes into that implementation's own record before
the service runs. The service dispatches on the record's runtime class and reads keys off loaded
columns; an id belonging to no implementation fails the request naming every implementation, in the
wording the read side already uses. The same at a top-level `@service` argument and at a list slot.

Reachable here. Every code, test and symbol the plan names exists as named, checked FQN-aware, with
the two new types (`AdmittedSlotType`, `NodeIdPolymorphicDecodeDefects`) correctly absent. The four
"what exists today" facts hold to the symbol: `resolveNodeIdRecordDecode` and
`NodeIdLeafResolver.resolve` both refuse with the `is not @table-annotated` fragment verbatim and
both really ask the `@table`-object question ahead of nodehood, so the ordering rule is correctly
stated as a change; `isJooqRecord` walks the hierarchy for `org.jooq.Record`, so an
`UpdatableRecord<?>` bean member does route into `buildJooqRecordLeaf` today and the equality gate
inside it is where the change goes; `takesTheNodeTablesRecord` compares javapoet `TypeName`s with
one `List` unwrapped and falls to `ThrowOnMismatch`; `RecordDecodeFragments` has exactly three
throwing public `decodeHelper` overloads over one private body taking `mismatchThrow`, whose only
`return null` is the not-a-`String` case, so the fourth overload is precisely the one primitive;
`MultiTablePolymorphicEmitter.dispatchFailureThrow` is private and its two-branch message is
verbatim what the plan quotes, including that a right-prefix-wrong-arity id lands on "not a valid
id". The capture the slot-typing fact rests on is in hand: `CatalogFactCapture` reads
`table.getRecordType().getName()` for `sql_table.record_class_fqn`, so the live record `Class<?>` is
at the walk the new relation sits beside. Every DDL claim checks out too, including each closed
vocabulary count (`basis` five, `destination` four, the incumbent defect view's two decided by arity
alone and excluding the input-field site as "owed an emitter rather than a verdict"), `candidates` as
the windowed count with its overload reading, `graphitron_table_entry` and `graphitron_node_entry`
keyed on the type with no kind constraint, `graphitron_tabletype` admitting `INTERFACE`,
`intent_node_type` as the node-entry union, and every quoted comment sentence.

The population edge is the round-2 finding and it is answered. The `EXPLICIT_TYPE_NAME` arm really is
site-agnostic, joining `instructed` to `intent_node_type` on `node_type_ref` with no site predicate,
and `intent_node_id_decode_slot` really is where the walk's fork lives, driving off the instruction
relation and excluding `OUTPUT_FIELD`. So `CONTAINER_NOT_AT_A_SLOT` reading that relation's absence
does cover all three refused coordinates: an output field by the slot relation's own `WHERE`, a
read-side argument or filter input on a generated fetch field by there being neither an argMapping
match nor a producer method, and a `@service` argument no parameter is fed from. The table arm's
`NOT EXISTS` over the slot relation confirms the absence is a fork the family already reads. All
three predicates are warranted at the DDL: `intent_node_id_decode_endpoint` joins
`graphitron_tabletype` on the named type and that relation admits an interface, so the
`SINGLE_TABLE_CONTAINER` shape would draw a bogus endpoint row; `intent_argument_filter_role`'s
`argument_node_id` CTE reduces exactly as claimed, `implicit` being false on `EXPLICIT_TYPE_NAME` so
`wired` collapses to `is_id AND NOT has_binding` and a container-naming `ID!` argument draws
`NODE_ID` at precedence 4, and that relation's keyless-type escape hatch ("such a type is a
rejection's population") is there verbatim and is what the item invalidates; and
`intent_input_field_filter_role_live`'s `node_id_at_table` CTE reads `basis`, `path` and `site` and
never the renamed column, so only the population half reaches it. The seam count is right: seven
relations read the instruction relation directly, six of them the renamed column, and the seventh is
disclosed as reading only the others.

On the second gate question, each piece extends a shape already in the tree at the rung that shape
reserves. The candidate set resolves through the existing single-type path, so per-candidate facts
are the ones the single-type decode already carries. Slot typing is one captured fact read twice,
the walk over the live class and the store over the captured closure composed with the census, which
is the agree-by-construction discipline rather than a parallel type system. The emitted glue is a
sibling leaf, and the argument holds: all five named emit consumers do read a single table off
`NodeIdDecodeRecord`. The fifth destination lands as a `CASE` branch inside the existing slot arm,
which the arm's shape makes a plain addition, rather than a second naming of what the DDL calls the
deepest derived read in the schema. `intent_node_container_member` is keyed on the container and
`intent_node_id_candidate_node_type` on the resolved type, both meeting the two-reader threshold and
both matching the `intent_spelled_table` / `intent_bound_table` layering. The rename plus kind
column is argued against splitting `basis` with a precedent in the store. The Implementation list is
ordered so a build stays green through it and names the mechanical enforcers each new relation owes.
Out of scope discloses what goes silent rather than leaving it latent. I would hand this to an
implementer as-is: the grain, the population edge, the assignability fact, the sibling-versus-widening
call and the ordering rule with its answer-preservation constraint are all decided, and what is left
is implementation.

#### Non-blocking

* The pipeline tier offers "the test catalog's `Film | Inventory` or `Customer | Staff` union". There
  is no `Film | Inventory` union in the tree; `Customer | Staff` exists as `union Person` in
  `graphitron/src/test/resources/corpus/polymorphic-filter.graphqls`. The `or` leaves the implementer
  a working fixture, so this is a stale alternative rather than a gap.
* Slot typing says the walk answers with `Class.isAssignableFrom`, and `isJooqRecord`'s javadoc
  explains that it deliberately avoids `org.jooq.Record.class.isAssignableFrom(cls)` so the result
  cannot depend on classloader identity. The two are not in conflict, because the plan's comparison
  puts both operands on one loader: `tryLoad` resolves through `ctx.codegenLoader()`, `JooqCatalog`
  holds that same loader, and `ServiceCatalog.resolveTableByRecordClassName` already loads a record
  class through it. Worth a sentence saying so, since an implementer who reads `isJooqRecord`'s
  comment first will reasonably wonder.
* The slot arm's admission is a `WHERE` clause as well as a `CASE`: today it admits a row only where
  `s.java_type = k.record_class` or the arity-one single-column shape holds, and an
  `UpdatableRecord<?>` slot satisfies neither. The plan's sentence about the branch being admitted by
  a `NOT EXISTS` over the members covers this in substance; it reads as being about the `CASE` alone.

### Round 4 (2026-09-09, In Review -> Done, reviewer session 01AJtvRiz8xHfz1RzGh7CqAL)

Verdict: rework; status moves back to `Ready`. The goal is delivered and demonstrated, the first
gate question passes, and the second fails narrowly on evidence the Tests section named and the
delivery did not produce, plus one substitution the spec body does not carry. Both are small; the
round exists because the gate holds the delivery to its own named evidence, not because the code is
in doubt.

The first question passes. Every piece the Design names is in the tree as designed, checked at the
symbol: `resolveNodeIdRecordDecode` asks nodehood before kind and forks into
`resolvePolymorphicRecordDecode`, whose three refusals carry the precedence and wording the spec
gives them; `admitPolymorphicSlotType` answers assignability with `isAssignableFrom` against the
catalog's live record classes on the codegen loader, and both slot kinds reach it and build one leaf
through `InputBeanResolver.polymorphicLeaf`; `NodeIdDecodePolymorphicRecord` is a sibling with the
two-candidate invariant in its compact constructor and `AdmittedSlotType` minted only at the
admission; `RecordDecodeFragments.decodeHelperOrNull` is the one primitive, and the container helper
is the `if` chain the spec draws; the multi-candidate message lives in `NodeIdDecodeFailure` and
`MultiTablePolymorphicEmitter` reads it there. On the store: the rename plus `resolved_type_kind`,
the container arm on `intent_node_id_instruction_live` disjoint from the node-type arm by the
`NOT EXISTS` on `intent_node_type`, the three `resolved_type_kind = 'NODE_TYPE'` predicates at the
endpoint, the argument-filter CTE and the input-field-filter CTE, `sql_table_record_supertype` as a
captured closure, `intent_node_container_member` total with its two flags,
`intent_node_id_candidate_node_type` with the identity arm, `POLYMORPHIC_RECORD` as a `CASE` branch
inside the slot arm admitted by a `NOT EXISTS` over the members, and the sibling defect view with the
five verdicts in the stated precedence. The LSP keyset reads the member relation. The census-leg
retirement is a substitution made on a measurement and it is in the spec body, which is where it
belongs.

**Finding 1 (question 2, blocking): the LSP tier's diagnostic half is missing.** The Tests section
names three LSP assertions. The delivery adds two completion tests
(`NodeTypeCompletionsTest.typeNameCompletionAlsoOffersContainersWithNodeMembers` and
`aContainerCompletesAtAReadSideCoordinateToo`) and no diagnostic test: nothing pins that a container
at `@nodeId(typeName:)` draws no unknown-node-type diagnostic after `DiagnosticFacts` widened the
keyset, and nothing pins that a container with no node members still draws it. `DiagnosticsTest`
already holds the template (`nodeIdTypeName_knownNodeType_producesNoError`, over `Film`), so both are
one seeded case each. This matters because the widening is the whole of what the Editor section
delivers for a valid schema: if the union arm in `nodeTypeBindingArm` were wrong, every polymorphic
slot in a consumer's editor would carry a false squiggle and nothing in the tree would say so, which
is the "green build compatible with the goal being half-delivered" case this gate exists for. The
third named assertion, a `CONTAINER_NOT_AT_A_SLOT` diagnostic at a read-side argument, is a different
matter: the verdict reaches the editor through the rejection residue the build writes and
`RejectionSeverityCoverageTest` replays every `Rejection` permit generically, so a per-verdict LSP
test would restate that meta-test; the Tests section should say so rather than promise a test that
does not arrive. Satisfied by: the two `DiagnosticsTest` cases, and the LSP bullet in Tests amended to
name the residue replay as the third assertion's evidence.

Response: both cases landed in `DiagnosticsTest`, over a container fixture of their own
(`nodeIdTypeName_polymorphicContainer_producesNoError` and
`nodeIdTypeName_containerWithNoNodeMembers_stillFlagsTheReference`), sharing one capture so that the
refusing case is the admitting case's control: silence from a store that answered nothing would
otherwise read the same as silence from the widened keyset. The LSP bullet in Tests now names the
four keyset cases and says the coordinate half's evidence is the rejection-residue replay rather than
a test that does not arrive.

**Finding 2 (question 1, recording): one design substitution is not in the spec body.**
`CONTAINER_NOT_AT_A_SLOT` stands aside where the coordinate's producer class has no `jvm_class` row
under the graph's sources, on `intent_field_producer_method`'s reading of that absence as the
census's rather than the author's. That is a narrowing of the verdict's population the Fact store
section does not state; it lives in the view's comment and in the implementation commit's message,
and the determinism run that motivated it (three false refusals) is recorded nowhere a reader of the
plan would find it. It is the right call, and `aProducerClassTheCensusNeverReachedDrawsNoCoordinateVerdict`
pins it, so this is a body edit and not a code change: state the stand-aside under the population
edge, with its reason, beside the incumbent's own "owed an emitter rather than a verdict" edge.

Response: stated, as a second paragraph beside the incumbent's edge under Fact store, carrying the
two causes of the slot relation's absence, which of them is the author's, that the stand-aside is
one-directional, and the three false refusals that found it.

The spec-body precondition has two smaller drifts to take in the same revision. The Tests bullet for
the assignability view still promises a `recordImplements` interface "admitted through the census leg,
not only the direct-supertype leg", and the census leg is retired; the delivered test
(`aSlotTypedAboveARecordImplementsInterfaceResolvesOffTheCapturedClosure`) is the right one and the
sentence should describe it. And the Implementation section carries no "shipped at `ad09411`" note,
which `roadmap/workflow.adoc` asks of a shipped phase.

Response: both taken. The assignability bullet now describes the delivered test, a slot typed above a
`recordImplements` interface resolving off the captured closure, which is the one leg that relation
has; and Implementation opens with the shipped-at note, saying why the file-by-file list stays below
it.

#### Non-blocking

* The walk's new read-side refusal in `NodeIdLeafResolver` ("names a polymorphic container, and a
  polymorphic node id is decoded into a @service slot") has no test at any tier. The store's
  `CONTAINER_NOT_AT_A_SLOT` rows are pinned three ways, so the fact is covered; the wording that is
  supposed to agree with it is not. One pipeline case over a `@nodeId(typeName: "AddressOccupant")`
  lookup argument would close it, and it can ride in the same revision or in a Backlog item.

  Response: it rides here. `PolymorphicNodeIdSlotPipelineTest.aContainerAtAReadSideArgumentIsRefusedForTheCoordinateRatherThanForItsKind`
  pins that the refusal names the coordinate and the remedy and is not the `is not @table-annotated`
  message, which is the wording `CONTAINER_NOT_AT_A_SLOT` is supposed to agree with. Named in Tests
  beside the slot matrix, since it is the one case in that class that is not a slot's.
* `SLOT_NOT_SUPERTYPE_OF_MEMBER` and the `POLYMORPHIC_RECORD` admission are restricted to
  `site = 'ARGUMENT'`. That is the population edge the spec states, narrowed one step further than
  the prose ("a producer parameter, or a bean that is itself a jOOQ record"): a jOOQ-record bean
  carrying a container-naming field is refused outright by `buildRecordKeyDecode`, so the store has
  no admitted shape to type there and the view's comment discloses the edge. Consistent, and noted
  only so the next reader does not take the prose as a gap.
* `DerivedReadCostTest`'s three budgets move (120 to 124, 61 to 62, 147 to 149) with the arithmetic
  written on each; read and agreed, not a finding.
* Build: `mvn install -Plocal-db` on the rebased tree at `fb07d4f` passes: BUILD SUCCESS, every module, 13 min wall clock, no test failures.

### Round 5 (2026-09-10, In Review -> Done, reviewer session 01SeaMVavMcbNSuaxVj2hJDV)

Verdict: rework; status moves back to `Ready`. Round 4's two findings are answered and answered
cleanly. The build is green (`mvn install -Plocal-db` on the rebased tree at `ae45d74`: BUILD
SUCCESS, every module, 12:47 wall clock, no test failures), no delivered test asserts a code string
against a generated method body, the retirement sweep is clean, and the user-facing doc surfaces
carry no roadmap vocabulary. One blocking finding on question 1, of the same recording kind round 4
raised and with a consequence round 4's did not have: the delivery resolves a design fork the plan
never poses, and resolves it one way in the walk and the other way in the store and the editor.

The rest of question 1 passes, checked at the symbol rather than taken from round 4. The three
container refusals in `resolvePolymorphicRecordDecode` carry the precedence and wording the Design
gives them, nodehood is asked before kind with the answer-preserving reasoning on the comment,
`admitPolymorphicSlotType` answers assignability with `isAssignableFrom` against the catalog's live
record classes and forks its refusal on scalar-versus-record the way Slot typing says, and
`AdmittedSlotType` is minted at the admission and nowhere else. The three `resolved_type_kind =
'NODE_TYPE'` predicates sit at the endpoint, the argument-filter CTE and the input-field-filter CTE
with the reason on each one's own comment, `POLYMORPHIC_RECORD` is a `CASE` branch inside the slot
arm admitted by a `NOT EXISTS` over the members, and `s.candidates = 1` survives verbatim.

Question 2's own evidence is the strongest part of the delivery and it holds. The execution tier
demonstrates the stated goal end to end against PostgreSQL: a `Customer` id and a `Staff` id through
one `UpdatableRecord<?>` slot with the service reporting back the record class it received, a `Film`
id refused naming both candidates, a malformed id and a right-prefix-wrong-arity id both landing on
the read side's own "not a valid id" wording, the list shape decoding each element on its own prefix,
and the producer-parameter twin. That is the field report's shape, answered.

**Finding 1 (question 1, blocking): a container with exactly one `@table` member is a fourth
container refusal in the walk, and the store and the editor both admit that shape.** The Design
section names three container refusals and the store's verdict vocabulary is five. Neither covers a
container whose members are admissible and number one, which `union U = Customer` and a
one-implementation interface both are, and which is legal SDL rather than a shape a schema cannot
reach. `BuildContext.resolvePolymorphicRecordDecode` refuses it ("which has one `@table`
implementation ... so there is nothing to dispatch on. Name that type instead"), which is what keeps
`Polymorphic`'s two-candidate compact-constructor invariant from throwing `IllegalArgumentException`
out of the walk on a schema an author can write. The refusal is right to exist and is nowhere in the
plan.

Three surfaces then disagree about one schema, and the item's own design rationale is what makes that
a finding rather than a nit. `intent_node_container_member` reports the single member table-bound and
a node type, so `intent_node_id_candidate_node_type`'s member arm yields its one row, no member count
is asked anywhere along the chain, and `intent_node_id_decode` draws `POLYMORPHIC_RECORD` while
`intent_node_id_polymorphic_decode_defect` draws no verdict: the store says the decode is carried
out. `DiagnosticFacts.nodeTypeBindingArm` asks only that some member be table-bound and a node type,
so the editor completes the container at `typeName:` and puts no squiggle under it. So the author is
offered a completion for a value the build refuses, which is the mirror of the failure mode that
arm's own comment says the union arm exists to prevent, and the Fact store section's claim that "the
walk's refusals and the store's verdicts are the same facts under two names, within the two limits
just stated" has an undisclosed third limit. No tier pins any of it: the 19 pipeline cases cover
every other admission and refusal the walk has, and this one has none, so a later reading of the
compact constructor as covering the case would turn legal SDL into a stack trace with nothing red.

The fork is genuine and either answer is defensible, which is why it belongs in the body. Refusing is
what shipped, and a one-member container really is the single-type decode wearing a container's name.
Admitting is what the store and the editor already do, costs one relaxed invariant, and needs no new
verdict: the emitted `if` chain works unchanged with one arm and a foreign id still meets the
dispatch's message. Satisfied by: state the pick under Resolution with its reason, make the three
surfaces agree on it, and pin it at the pipeline tier. Refusing means a fourth container verdict (or
a stated reason the store declines to mirror this one), the keyset excluding a container with fewer
than two admissible members, and a pipeline case plus a fact-store case. Admitting means dropping the
walk's `candidates.size() < 2` arm and the record's two-candidate invariant, and a pipeline case over
a one-member container decoding.

Response: admitted, and the pick is stated under Resolution with its reason. The member set belongs to
the schema and grows, so refusing at one would make a slot's correct spelling a function of the
implementation count on the day it was written and send the author back through the single-type
spelling when a second implementation lands, which is the drift the item already declines to build in
under `@nodeId(anyOf: [...])`. `SINGLE_TABLE_CONTAINER` stays refused on the ground the count does not
share, that a table-binding container gains no record class from any member it ever adds. Two
invariants relaxed, not one: `CallSiteExtraction.NodeIdDecodePolymorphicRecord`'s compact constructor
and its twin on `BuildContext.NodeIdRecordDecode.Polymorphic`, both now refusing only the empty list,
which is the shape the walk refuses rather than resolves; the walk's `candidates.size() < 2` arm is
gone and its javadoc carries the reason. Two cases pin it rather than the one asked for, because the
finding is about three surfaces and not one:
`PolymorphicNodeIdSlotPipelineTest.aContainerWithOneAdmissibleMemberDecodesThroughTheSameChain` for
the walk and the emitted chain, and
`PolymorphicNodeIdDecodeTest.aContainerWithOneAdmissibleMemberDrawsItsOneRowAndNoVerdict` for the
store, the second turning "no relation counts members" from a reading of the SQL into an asserted
row set. The editor needs no change and no case of its own: `DiagnosticFacts.nodeTypeBindingArm` asks
only that some member be table-bound and a node type, so the completion it already offers now leads to
a schema the build accepts, which is what the finding said it did not.

#### Non-blocking

* `admitPolymorphicSlotType` carries a third slot-typing refusal beyond Slot typing's two, for a slot
  type no census could read. That one is disclosed where it belongs, enumerated on
  `intent_node_id_polymorphic_decode_defect`'s comment as a shape falling outside the partition, with
  the reason the polymorphic slot cannot stand aside onto an arity-only fallback. Read and agreed.
* Implementation opens "Shipped at `ad09411`" as round 4 asked, and then says the round-4 rework
  "adds no code" and "rides in the commit carrying this revision". It added three test cases, and the
  commit is `50492f1`, which the body does not name. Naming the sha beside the first one would make
  both landings readable from the body alone.

  Response: taken. Implementation now names `50492f1` beside `ad09411` and says what each landing
  carried, with the round-5 rework named as the commit carrying this revision in the same sentence
  shape, so a later round can pin it the same way.
