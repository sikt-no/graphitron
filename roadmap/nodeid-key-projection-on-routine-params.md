---
id: R668
title: "Decode @nodeId leaves bound to @routine parameters via argMapping key-column projection"
status: Ready
bucket: feature
priority: 3
theme: routine
depends-on: []
created: 2026-08-14
last-updated: 2026-08-20
---

# Decode @nodeId leaves bound to @routine parameters via argMapping key-column projection

## Done-gate review, 2026-08-20: rework requested, one finding

The delivery is one finding away from approval, and the finding is confined to a single test file.
No production change is requested.

**The finding.** `ArgmappingKeyProjectionEmissionPipelineTest` asserts raw generated-method-body
strings in six of its seven cases: `fetcherBody()` returns `m.code().toString()` and the cases run
`contains("InventoryRecord keyInputInventoryId = decodeInventoryRecord(...")`,
`containsPattern("Routines\\.rentFilm\\(...")`, `body.indexOf(...)` ordering comparisons against
`"try {"`, regex occurrence counts over the body, and the same `contains`/`containsPattern` shape
over the condition glue's body string. That is the pattern
`docs/architecture/explanation/development-principles.adoc` bans at every tier ("Code-string
assertions on generated method bodies are banned at every tier... the compile and execution tiers
replace them"), and the ban is review-enforced at test-review time, which makes this gate the
enforcement point. `TypeSpecAssertions` is the codebase's own named remedy: its javadoc cites the
ban and confines body-scan fragility to typed helpers so one file changes when the emitter's output
shape changes. The rework is to re-express those assertions through typed structural helpers there
(or siblings beside it), and where the claim is behavioral, to pin it at the tier that owns
behavior: "the decode precedes the write transaction" is observable at the execution tier as a
malformed node id surfacing as a client error instead of routing through the write's error channel.
The two cases already structural (`theDescentToAProjectedLeafIsUntyped` over
`MethodSpec.returnType()`/`parameters()`, and `aProjectionNoEmitterOwnsStopsThePlan` over the thrown
message) are fine as they stand.

**Verified clean, so the next pass need not redo it.** `mvn install -Plocal-db` passes on the
delivery rebased onto trunk at 57f0683. The reviewer rule holds (implementer session
`01CpytxdXY3ZXMmjms8ke8VZ`, this reviewer distinct). The user-facing-doc check passes: the
`nodeId.adoc` and `routine.adoc` additions read as reference prose with no roadmap-internal
markers. The retirement sweep is skipped (no retired vocabulary declared, none found undeclared).
The claims table below spot-verified accurately: the ratchet pin at 139 with its narrative, the
24-case defect test and 15-case projection test, the defect view's six arms disjoint by
construction over `node_id_declared`/`trailing_segments`/one existence test, the type gate standing
aside on either unknown operand (including the `candidates > 1` edge, which stays an unchecked
projection rather than a silent gap), `tables_class_fqn` resolved off the codegen classpath with
the two-schema agreement case that would catch a concatenating capture, the array-safe
`ColumnRef.decodeBindingType`, and the PostgreSQL execution test asserting the decoded integer key
in the committed row. All of that stands; only the emission test's assertion idiom needs the pass.

A `@nodeId` field carries a base64-encoded node identity on the wire, not the primary key it
encodes. Graphitron already knows how to turn that wire form back into typed key values: the
decode side ships for lookups and filters, where an argument or input field annotated
`@nodeId(typeName: T)` is decoded into `T`'s key columns and fed to an `IN` / `VALUES` join.
That decode is not wired into the `@routine` parameter binding. `argMapping` hands a routine
IN parameter the *raw* value at the path it names, so a `@nodeId`-carrying input field delivers
the base64 string.

The concrete case, an access-control mutation whose routine takes the organisation's integer
key:

```graphql
input OpprettFeideApplikasjonInput {
  navn: String!
  organisasjonId: ID! @nodeId(typeName: "Organisasjon")   # encodes organisasjon.organisasjonskode (INTEGER)
  serviceId: String!
  beskrivelse: String
}

type Mutation {
  opprettFeideApplikasjon(input: OpprettFeideApplikasjonInput!): OpprettFeideApplikasjonPayload
    @routine(
      name:       "opprett_feide_applikasjon"   # (p_navn TEXT, p_organisasjonskode INTEGER, p_service_id TEXT, p_beskrivelse TEXT)
      argMapping: "pNavn: input.navn, pOrganisasjonskode: input.organisasjonId, pServiceId: input.serviceId, pBeskrivelse: input.beskrivelse"
    )
}
```

This item makes the node type's key columns nameable as a trailing path segment, so the binding
reads:

```
argMapping: "..., pOrganisasjonskode: input.organisasjonId.organisasjonskode, ..."
```

`organisasjonskode` is not a field of any SDL type. It is a *key column of the node type the
`@nodeId` names*, and the segment means "decode this node id and project that column out of the
decoded key tuple".

## What an author gets

One rule, and it is the rule the dot already followed: **a dot opens the thing at that position, and
what it opens into depends on what the thing is.** An input object opens into its fields. An `ID`
carrying `@nodeId(typeName: T)` opens into `T`'s key columns. So a path may name a key column as its
last segment, and the generated fetcher decodes the wire id once and reads that column off the
decoded record before the write transaction opens.

Everything else the author can do with that spelling is a build error naming the entry, in a closed
vocabulary of six:

| The author wrote | The build says |
|---|---|
| `input.organisasjonId` with a `@nodeId` on it | `BARE_NODE_ID`: the encoded id would reach the database verbatim; here are the key columns you could have named |
| `input.navn.noe`, on a `String` | `UNDECLARED_NODE_ID`: a `String` has nothing to open |
| `input.organisasjonId.noe`, with no `@nodeId` on the `ID` | `UNDECLARED_NODE_ID`: annotate it `@nodeId(typeName:)` to open it |
| `input.organisasjonId.organisasjonskode.noe` | `TRAILING_SEGMENTS_BEYOND_ONE`: a node id opens into exactly one key column |
| a `@nodeId` with no `typeName:`, opened | `MISSING_TYPE_NAME`: there is no containing table here to infer the node type from |
| a column the node type does not resolve | `UNKNOWN_KEY_COLUMN`, with the candidate list and a near-miss hint |
| a column whose Java type the parameter cannot take | `KEY_COLUMN_TYPE_MISMATCH`, naming both types |

And two things are *deferred* rather than rejected, because they are coherent requests this generator
does not build yet: a list-shaped node id (which names the list of that key column across the decoded
ids), and a projection at a site whose emitter does not read one.

The distinction matters to an author. A rejection says "you wrote something wrong". A deferral says
"you wrote something sensible and we have not built it", and the message points at the follow-up.

**The silence this closes.** Before this, `pOrganisasjonskode: input.organisasjonId` compiled, and
the base64 string went to an `INTEGER` routine parameter. No error, no warning, a runtime failure far
from its cause. Every one of the six rejections above is a spelling that used to build clean.

## Where every question is answered

The whole resolution is a chain of relations in the fact store, and the design claim worth reviewing
is that **it is a chain and not a scattering**: each link answers one question, the next link reads
the previous one, and no consumer re-derives an upstream answer.

```
graphitron_*_arg_mapping_pair (7 relations)
  -> intent_argmapping_pair              one shape, eight site values
  -> intent_argmapping_segment_binding   what each path segment bound
  -> intent_argmapping_binding_leaf      the last thing it bound, + does it declare a @nodeId
  -> intent_argmapping_key_column_candidate   the trailing segment names a key column
  -> intent_resolved_node_key_projection      ...and the parameter can take its type
```

Two relations feed that chain from the side, and both are reductions a second reader can take:

* `intent_resolved_node_key_column` resolves a node type's ordered key columns over three tiers
  (`@node(keyColumns:)`, the bound table's node metadata, its primary key), first tier wins whole.
* `intent_resolved_node_type_id` resolves its wire type id over three tiers (`@node(typeId:)`, the
  bound table's well-formed metadata, the type's own name), and is total over node types.
* `intent_argmapping_bound_parameter_type` answers what Java type a pair's left side takes. This is
  the one that did not exist and had to, because the two populations answering it are unrelated: a
  `@routine` parameter's type is a catalog fact, every other site's is a classpath fact. A reader had
  to switch on `site` to know which to ask, so nobody asked, and the type gate could not exist.

`intent_argmapping_projection_defect` reads the same chain and states the six rejections. Absence
from the projection relation and presence in the defect relation are two readings of one resolution,
which is why no arm needs a precedence rule: the arms are disjoint over `node_id_declared`,
`trailing_segments`, and one existence test.

**The type gate is a join predicate, not a check.** An emitter reads the projection relation, so a
pair whose types disagree is not a projection an emitter can see. There is no order of operations in
which one is emitted and then rejected. It fires only where both operands are known, and stands aside
where either is not, which is deliberate: requiring a match would turn a parameter the census cannot
name into a pair that is neither a projection nor a defect, and that is the silence the item exists to
close.

## The walk carries and judges nothing

`ArgBindingMap.of` resolves an `argMapping` path while the schema is being built, which is *before*
capture runs. It therefore has no store to consult, and this item's shape depends on it not pretending
otherwise: on reaching a segment it cannot resolve against SDL, it carries every remaining segment as
an ordinary path step and decides nothing about them.

That is one deletion from what was there before, and the property it buys is what a reviewer should
check hardest. A rule spelled in the walk would be an earlier, unfalsifiable second copy of a view's
answer, and it would win by rejecting first. Two of the six rejections above are exactly the ones a
reader expects the walk to own, `UNDECLARED_NODE_ID` and `TRAILING_SEGMENTS_BEYOND_ONE`, and both are
in the view for that reason.

One rejection stays in the walk and it is not a judgment: a head naming no slot in scope. That is a
question about the SDL surface the walk is holding, not about captured facts, so it has no store
counterpart to duplicate.

## What renders

The plan tier reads facts; the render tier reads command rows. `KeyProjection` carries the
coordinate, the written path, the node type, its wire id, its `TableRef`, its ordered key columns, and
the one projected column. Every component is a captured fact.

Three consequences a reviewer can check by reading the row's declaration:

* **No generated method name rides on it.** The emitted decode is
  `NodeIdEncoder.decodeValues(typeId, wire)`, whose only inputs are the wire value and the type id, so
  a per-type `decode<TypeName>` reference was never needed.
* **No `ClassName` for the encoder rides on it.** That class is `<outputPackage>.util.NodeIdEncoder`,
  a function of generator configuration rather than a captured fact, so `render/NodeIdEncoderRef`
  mints it from the configuration render already holds.
* **The key list rides on it whole.** The decode's `fromArray` load is positional, so a row naming
  only the projected column would leave its emitter unable to write the load. The projected column is
  named rather than indexed, which is what makes a transposed composite projection unconstructable.

`plan/KeyProjectionCommands` is consequently a shape transform with no schema parameter, no lookup and
no failure mode. One decode body derivation serves both hosts that need it
(`render/RecordDecodeFragments`), and one nested-wire-descent emission serves both call sites that
need it (`render/WireMapChain`).

## Reviewing it

Each row is a claim the item makes, with where to look and what already holds it. Nothing here needs
the store dumped or the generator run by hand.

| Claim | Where it lives | What holds it |
|---|---|---|
| The motivating spelling emits and executes | `graphitron-sakila-example` schema, `Mutation.rentFilmPayloadProjected` | `GraphQLQueryTest.rentFilmPayloadProjected_nodeIdArgMappingReachesTheRoutineAsAKey` runs it against PostgreSQL and reads the row back |
| Six rejections, disjoint, over one chain | `intent_argmapping_projection_defect` | `ArgmappingProjectionDefectTest` (24 cases, seeded row by row in the module whose DDL declares the view) |
| Each rejection reaches the *build's* verdict | `GraphQLRewriteGenerator.validate()` | `ArgmappingProjectionRejectionPipelineTest` (10 cases, one per site plus the type mismatch and the undeclared-id case) |
| The messages an author actually meets | `rewrite/derive/ArgmappingProjectionDefects` | `ArgmappingProjectionDefectsTest` pins the prose, the `Rejection` arm and the source location |
| The projection resolves, with its tiers | `intent_resolved_node_key_projection` | `ResolvedNodeKeyProjectionTest` (15 cases, including both stand-aside cases of the type gate) |
| A store-sourced `TableRef` is assemblable at all | `rewrite/derive/StoreNodeTables` | `StoreNodeTablesTest` builds one from a real captured store: record class, constants class, field name, pinned key order, type id per tier |
| The command row carries facts and nothing else | `command/KeyProjection` | `KeyProjectionRelationTest` (unit tier; the row's own invariant plus the relation's key) |
| The emitted read is what we think it is | `render/ProjectedKeyReads`, `render/RecordDecodeFragments` | `ArgmappingKeyProjectionEmissionPipelineTest` (7 cases over the emitted source) |
| The plan tier no longer reads the walk | `plan/KeyProjectionCommands` | `CommandSeamRatchetTest` plan-side pin, lowered 140 to 139; the producer takes no `GraphitronSchema` |
| Output did not change while being re-sourced | whole generator | the example regenerates and compiles under `-Werror`; `GeneratorDeterminismTest` |
| Every new relation is registered and commented | `graphitron-model.sql` | `FactCaptureAgreementTest.everyRelationIsRegistered`, `FactSchemaGateTest.commentCoverageIsTotal` |

Two things worth a reviewer's specific attention, because they are where I would look for a mistake:

1. **`sql_schema.tables_class_fqn` is a new captured fact.** It is the per-schema generated `Tables`
   class, and it exists because a store-sourced `TableRef` cannot otherwise name a column constant.
   Its sibling `keys_class_fqn` already established the rule it follows: resolve it by loading the
   class off the codegen classpath, never by concatenating a configured package, because the two
   diverge under multi-schema layouts. `FactCaptureAgreementTest` has a case over the two-schema
   fixture asserting each schema reports its own; a concatenating capture would satisfy every
   single-schema assertion and fail that one.
2. **`ColumnRef.decodeBindingType` is new and array-safe.** A store-sourced column has a name and no
   `Class`, and an array column's captured name is a JVM descriptor that the pre-existing scalar
   decode crashes on. `ColumnTypeConstructorArityGuardTest` forbids the scalar path in production and
   now recognises this third one; `ArrayColumnTypeDecodeTest` pins that it agrees with the reflection
   boundary on a real array column.

## Scope boundary

Deliberately not covered, so a reviewer does not read these as gaps. Each one *defers* with a message
rather than emitting nothing or emitting base64, which is the property that makes the boundary safe.

* **The `@service` site.** Its argument list is composed as an expression fragment handed to the root
  launcher, so the decode's local declaration has nowhere to land yet. `EMITTING_SITES` excludes it
  and a projection there fails the build saying so.
* **The input-field `@condition`.** Its pair rows are keyed by the input type and input field while
  the condition row rendering it is keyed by the consuming output field, so the projection lookup
  misses by construction rather than by omission. Wiring it needs a keying change, not an emitter.
* **The three path-step `@condition` sites.** They resolve against an empty slot map and bind no leaf
  at all, so they can only ever defer.
* **A list-shaped node id.** Coherent and unbuilt, as above.
* **Composite keys** are expressible and resolve (two parameters bound from one id are two rows naming
  two columns), but no fixture exercises one end to end against a database. The relation tier covers
  the shape.

## Follow-ups filed

* `roadmap/projected-key-column-across-a-node-id-list.md` (R735): emit the list-shaped projection, and
  give `ArgBindingMap.Result` a deferral arm so a "not built yet" answer stops travelling as a
  rejection.
* `roadmap/argmapping-completion-after-a-dot.md` was absorbed into this item: the editor's
  after-a-dot answer needs no new capture, `graphitron_argument_node_id` and `graphitron_field_node_id`
  already carrying `node_type_ref` at both coordinates. What is missing is the consumer,
  `ArgMappingCompletions.rightCandidates`, which returns nothing once a dot appears.
* One note for the emitter migration rather than an item: whether the node-id encoder family should
  have a single naming authority. `NodeIdEncoder.decode<TypeName>` is minted once during schema
  building and read by two consumers; under a queryparts model the emitted method name is a command's
  output key, so the authority belongs there rather than in a render-side registry.

## Relationship to other items

Only the relationships that bear on reviewing this one.

* **R682** (`planners-read-facts-emitters-read-commands`) owns the programme this item's plan tier now
  complies with. The plan-side ratchet moving down is this item's contribution to it, and the encoder
  naming question above is deliberately left to its emitter half rather than solved here.
* **R710** (node metadata as stated facts, shipped) captured `sql_node_metadata` with its reader
  deliberately deferred. This item is that reader, twice: the key-column tier and the type-id tier
  both read it through `intent_node_metadata_defect` rather than off the raw relation.
* **R704** (routine composition surface, shipped) landed `sql_routine_parameter` and
  `intent_resolved_type_binding`. The type gate's routine arm and every table binding here read them.
* **R626** (`lsp-argmapping-routine-coordinate`) records "offer nothing rather than a misleading flat
  list" for dot-path completion. That note should be narrowed to the input-object arm: the node-id arm
  is answerable from `intent_resolved_node_key_column` today.
* **R249** (`nested-argmapping-syntax`) varies the same grammar from the other end and composes with
  the openability rule. Shared owner is `ArgBindingMap.parseArgMapping`.
