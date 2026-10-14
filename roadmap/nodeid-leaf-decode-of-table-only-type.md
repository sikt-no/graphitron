---
id: R1032
title: "A @nodeId leaf on a DML or filter input decodes a @table-only type from its catalog node metadata"
status: Spec
bucket: bug
priority: 2
theme: classification-model
depends-on: []
created: 2026-10-14
last-updated: 2026-10-14
---

# A @nodeId leaf on a DML or filter input decodes a @table-only type from its catalog node metadata

## Goal

A `@nodeId(typeName: T)` field on a `@mutation` (DML) input, on a query filter input, or on a filter argument builds when `T` is a `@table` type without `implements Node` whose table carries catalog node metadata (the `__NODE_TYPE_ID` and `__NODE_KEY_COLUMNS` constants Sikt's jOOQ generator stamps on a table class). The id decodes against that metadata's type id and key columns, which is the answer a `@service` input with the same field already gets. Today these fields reject with "'T' is not a node type. Annotate 'T' with @node." That remedy is closed to a consumer whose `T` is a federation stub owned by another subgraph: `@node` requires `implements Node`, and the stub's owner publishes that, not this subgraph. Graphitron 9 decoded these ids from catalog metadata, and clients still send them, so this is a migration-parity gap.

The minimal pair, from sis. `Studieniva` is a code table that moved to another subgraph and is now a stub here:

```graphql
type Studieniva @table @key(fields: "kode") {
  kode: String @field(name: "STUDIENIVAKODE") @shareable
}

# Builds today: the input sits under a @service mutation.
input LeggTilBegrensningerForEmnevalgStudienivaIntervallInput {
  fraStudienivaId: ID @nodeId(typeName: "Studieniva") @reference(path: [{key: "<FRA STUDIENIVA FK>"}])
}

# Rejects today, builds after this item: the same field under @mutation(typeName: UPDATE).
input OppdaterBegrensningerForEmnevalgStudienivaIntervallInput {
  fraStudienivaId: ID @nodeId(typeName: "Studieniva") @reference(path: [{key: "<FRA STUDIENIVA FK>"}])
}
```

(The foreign-key name is elided. The report names the two keys only as the `FRA` and `TIL` `STUDIENIVA` foreign keys of `EMNEVALG_I_EMNEKOMBINASJON`.)

After this item, a `@nodeId(typeName: T)` naming a `@table` type is refused only when `T` is neither a node type nor backed by a table with catalog node metadata, which is the case where no type id exists to decode against.

## Evidence

Reported from sis on 2026-10-14 against graphitron trunk `b9616e463` (R1029 In Review), after R1029 removed the tenant-fold crash. 17 `@nodeId` fields still reject at `NodeIdLeafResolver` with "is not a node type", plus 12 roll-up errors for the inputs that contain them. The targets are `Studieniva` (5), `Vektingstype` (3), `Tidsenhet` (2), and one each of `Nusklassifikasjon`, `Studieklassifikasjon`, `Utdanningsomrade`, `Godkjenninginstans`, `Lovgodkjenning`, `Studieprogramjustering` and `Undervisningsorganisering`. The positions:

- `@mutation(typeName: UPDATE, table: "studieprogram")` inputs: `EndreStudieprogramInput`, `EndreStudieprogramGodkjenningOgRapporteringInput`, `EndreStudieprogramVektingInput`, `EndreStudieprogramVarighetInput`.
- `@mutation(typeName: UPDATE)` on `EMNEKOMBINASJON`: `OppdaterStudieoppbygningsdel{Varighet,Minimumsvekting,Maksimumsvekting}Input`, two of them through `@reference(path: [{key: "EMNEKOMBINASJON__KRAV/MAX__VEKTINGSTYPE__FK"}])`.
- `@mutation(typeName: UPDATE)` on `EMNEVALG_I_EMNEKOMBINASJON`: `OppdaterBegrensningerForEmnevalgStudienivaIntervallInput.fra/tilStudienivaId`, through `@reference` on the `FRA`/`TIL` `STUDIENIVA` foreign keys.
- Filter inputs: `StudieprogramV2FilterInput.studienivaer` and `StudieprogramV3FilterInput.studienivaer`, both `[ID!]`.

sis keeps temporary stub commits that bind these fields to plain foreign-key columns until this lands. After this item and R1027, sis should be left with only the `FeideBruker` rejection. R1027 is the tenant cascade work, which acts in the tenant fold; these rejections happen earlier, in classification, so the two items do not overlap.

## How the leaf decode is shaped today

A *leaf* here is a `@nodeId` input field or argument whose decoded key binds a table predicate or a DML column, as opposed to a `@service` parameter, where the decoded key reaches Java. `NodeIdLeafResolver.resolve` classifies every leaf, for both callers (`BuildContext.classifyInputField` for input fields, `FieldBuilder.classifyArgument` for arguments), into `SameTable`, `FkTarget.DirectFk`, `FkTarget.TranslatedFk` or `AuthorOwnedPredicate`. Each arm carries a `HelperRef.Decode`, and the callers wrap it in a `CallSiteExtraction.NodeIdDecodeKeys` (`ThrowOnMismatch`, or `PruneOnMismatch` on a multitable polymorphic root). A `HelperRef.Decode` names a generated static method `NodeIdEncoder.decode<T>(String)`, which returns the typed key record or `null`. Every emitter that reads a leaf carrier treats it as exactly that: a call to `encoderClass.methodName(wire)` returning `returnType()` (`TypeFetcherGenerator`, `CompositeDecodeHelperRegistry`, `LookupRows`, `ConditionGlueRenderer`, `ArgCallEmitter`, `ServiceMethodCallEmitter`, the UPDATE walker). None of them asks whether a node type stands behind it.

Two facts about the reference are tied to node types today, and both are the whole of the gap:

- **Minting.** `BuildContext.resolveDecodeHelperForType(refTypeName)` answers only from `NodeIndex.forName`, which returns `NodeType.decodeMethod()`. The resolver has already read the target's key columns through `BuildContext.resolveTargetKeys` by then, and that method's second arm falls back to the table's catalog metadata, so for `Studieniva` the key is resolved and only the reference is missing.
- **Emission.** `NodeIdEncoderClassGenerator.generate(GraphitronSchema)` emits `decode<T>` for each classified `NodeType` and nothing else.

`TypeBuilder.buildTableType` keeps a `@table` type over a metadata table a `TableType` unless it declares `implements Node`, so that a projection over a node table does not become a second node. This item keeps that rule. `Studieniva` stays a `TableType`; what changes is that a leaf naming it gets a decode helper.

The record path is not the precedent for the mechanism, only for the answer. `BuildContext.resolveNodeIdRecordDecode` resolves the same type id and key columns through `resolveTargetKeys`, but its carriers (`RecordKeyDecode`, `NodeIdDecodeRecord`) never name a `NodeIdEncoder` method: the emitter inlines `decodeValues(typeId, id)` into per-host `decode<T>Record` helpers. The leaf carriers have one shape, `HelperRef.Decode`, and too many readers to give them a second.

## Implementation

**`BuildContext.TargetKeys` becomes sealed over its source.** Today it is a record with a nullable `error`, and callers cannot tell which of `resolveTargetKeys`' three arms answered. The leaf needs exactly that answer, so the resolution records it, and nothing reads the metadata a second time:

- `OfNode(NodeType node)`: the name-first arm. `typeId()` and `keyColumns()` delegate to the node.
- `OfCatalog(String typeId, List<ColumnRef> keyColumns)`: no `NodeType` under the name, and the target table carries catalog node metadata.
- `OfDeclaredNode(String typeId, List<ColumnRef> keyColumns)`: the `@node`-on-SDL arm, for a type that carries `@node` but did not classify as a `NodeType`, over a table without metadata.
- `Rejected(String message)`: the existing error text, unchanged.

The three resolved arms share a `Resolved` sub-interface exposing `typeId()` and `keyColumns()`, and that is all the record path reads (`resolveOneRecordDecode`), so its answers do not change. `resolveTargetKeys` keeps its arm order.

**`HelperRef.Decode` carries the table it decodes into.** It gains a `TableRef table` component. The generated `decode<T>` body reads `Tables.<table>.<column>`, which today comes from `NodeType.table()` beside the reference. With the table on the reference, the reference alone is enough to emit the helper and to answer which table a decode reaches, and that is what lets the fold drop its node-type lookup (below). Two production sites construct one: `TypeBuilder.buildNodeType`, which holds the `TableRef`, and `InputBeanInstantiationEmitter.buildRecordDecodeHelper`, which passes `rec.table()`. The ten or so test constructions pass a fixture table.

**`NodeIdLeafResolver.resolve`** switches on the `TargetKeys` it already reads, in place of calling `resolveDecodeHelperForType`:

- `OfNode`: `node.decodeMethod()`, unchanged.
- `OfCatalog`: `ctx.catalogNodeDecode(refTypeName, targetTable, keys)`, which mints `new HelperRef.Decode(encoderClass, "decode" + refTypeName, keys.keyColumns(), keys.typeId(), targetTable)` and registers it (below). The encoder class is the one `TypeBuilder.buildNodeType` and `resolveOneRecordDecode` already build, `<outputPackage>.util.NodeIdEncoder`. The target `TableRef` is resolved where the FK arms resolve it today (`ctx.catalog.findTable(targetTableName)`), moved ahead of the same-table short-circuit so every arm has it.
- `OfDeclaredNode`: rejects, saying `'T'` carries `@node` but did not classify as a node type, so the leaf has no decode to install, and pointing at the error reported on `T` itself. Today this case says "Annotate 'T' with @node.", which `T` already is.
- `Rejected`: rejects with its message, as today. This is now the only rejection a `@table`-only target without metadata meets, and its existing text already names both remedies (annotate with `@node`, or publish the metadata).

The comment above the old call ("a name carrying no NodeType is now simply not a node: the table-keyed fallback that used to answer here belonged to the synthesis shims") is replaced by one stating the arms. That fallback mapped a *table* to a decode helper, and it went with the shims because a table does not say which type it means. `OfCatalog` is keyed on the type name the author wrote and reads only that type's table, so it does not bring the fallback back. The method name is the authored type name, as for node types. Two `@table` types over one metadata table get two helpers with the same body. A `@table` type cannot collide with a node type, because GraphQL type names are unique.

Nothing else in the resolver changes: the 22-column cap, route selection and all four arms read the reference opaquely.

**The registry.** `BuildContext` keeps a name-keyed map of the `HelperRef.Decode`s minted by `catalogNodeDecode`, and writes it with `putIfAbsent`. A mint is a pure function of the type name and the catalog, so a repeat is a no-op and the content does not depend on walk order. Registering at the one site that mints the reference makes the emission invariant hold by construction: every `HelperRef.Decode` a carrier holds came from a `NodeType` or from this map, and both are emitted. A leaf that mints and then rejects further on (a refused route) leaves an entry behind. That entry is never emitted, because a rejected leaf fails the build. `GraphitronSchemaBuilder` passes the map's values, sorted by type name, to `GraphitronSchema` as a new component `catalogNodeDecodes` (the convenience constructors default it to empty).

The registry is the set of decodes the walk minted, not a reading of the classified types, because the target need not be classified. A `@table` type named only in a `@nodeId(typeName:)` argument is not reached by the walk. Reachability seeds node types, not `@table`-only types, and sis's stubs are reached only because they are federation entities.

**`BuildContext.resolveDecodeHelperForType` stays node-only.** After this change its one caller is `ServiceCatalog`'s named-parameter key slot, a `@service` parameter that takes a decoded key rather than a record. That slot keeps its "names no @node type" refusal for a `@table`-only target, on purpose: the leaf classifies itself entirely in the walk, while the named-parameter slot leaves the check of its Java parameter type against the key to the fact store (`intent_node_id_decode_defect`, which joins `intent_resolved_node_key_shape`). Those views read `graphitron_node` only, so a widened slot would pass a mistyped parameter to the Java compiler unchecked. The method's javadoc paragraph about "the orphan case of a `@table`-only type" is rewritten to say this. The slot widens with the census follow-up below.

**`NodeIdEncoderClassGenerator`.** `buildPerTypeDecode(NodeType, ClassName)` becomes `buildPerTypeDecode(HelperRef.Decode)`, reading the method name, output shape, type id and table off the reference (`nt.typeId()` equals `nt.decodeMethod().typeId()` for every node type). `generate(GraphitronSchema)` emits the node types' decodes first, then each `catalogNodeDecodes()` entry. No `encode<T>` is emitted for a catalog decode, because nothing encodes a `@table`-only type's id: its output side has no node id. The class javadoc's "for each `@node` type" becomes "for each node type, and a decode for each `@table` type a `@nodeId` leaf decodes from catalog metadata". `PlanCompileGraph` already reaches `NODE_ID_ENCODER` through precise and leaf-derived edges from the carriers rather than a per-node blanket, so a new decode needs no new edge.

**`TenantBindingIndex.Fold`.** The `NodeIdDecodeKeys` arm of `collectFromServiceLeaf` looks the node type up by `nodeTypeOf(keys.decodeMethod())` on its slot-miss branch, the branch that reports tenant evidence, and throws on a miss. It reads `keys.decodeMethod().table()` instead, and `nodeTypeOf` goes. The evidence names the decode's target type, `decode.nodeTypeName()` (renamed `targetTypeName()`, below), where it named `node.name()`; for a node type that is the same string.

When the tenant column is in a catalog decode's key, the leaf **routes** and does not decline. `accessOf` already mints `DecodedKeySlot(nid.decodeMethod(), slot)` for every `NodeIdDecodeKeys` leaf, and the tenant routing helper `CompositeDecodeHelperRegistry` registers calls `decode<T>`, which now exists. `DecodedKeySlot`'s promise, one generated helper and one failure message shared with the carrier's own predicate, holds as it does for a node type, so routing costs no code.

**R1029's record-carrier decline is reworded, not moved.** `decodedSlotAccess` declines a record-shaped decode (`RecordKeyDecode`, `NodeIdDecodeRecord`) of a `@table`-only target whose key carries the tenant column. Its detail gives the reason as "there is no node id decode helper to route on". After this item that can be false: `decode<T>` exists whenever some leaf names `T`. The decline is a scope decision: the record carriers hold no `HelperRef.Decode`, and no consumer has the shape. The text says so: "...but 'T' is not a node type, and a record decode of a `@table`-only type does not route the statement on its tenant. Make 'T' a node type, or bind the tenant through a field mapping to tenant column 'c'." The two assertions in `aJooqRecordParameterDecodingATableOnlyTypeWithTheTenantInItsKeyDeclines` anchor on the prefix and the remedy, and both survive.

**Names and javadoc premises** corrected in the same change, each to say "the `@nodeId(typeName:)` target: a node type, or a `@table` type over a table with catalog node metadata":

- `HelperRef.Decode`: "Per-Node decoder helper", and `nodeTypeName()` ("The GraphQL node type this decoder answers for"), renamed `targetTypeName()`. It has about fifteen readers, and they are message builders and helper-name derivations that read it as "the type the id belongs to".
- `NodeIdLeafResolver.Resolved` arm parameters: "`decode<TypeName>` helper resolved on the target NodeType", five places.
- `GraphitronType.NodeType`'s javadoc on `decodeMethod`, which stays true but is no longer the only source; say so.
- `NodeIdEncoderClassGenerator` class javadoc, as above.

**Not changed:** `TypeBuilder` classification (`@table` without `implements Node` stays a `TableType`), the record carriers and their emitters, the store, and every emitter that reads `HelperRef.Decode` apart from `NodeIdEncoderClassGenerator`.

**Outside this item**, filed as their own Backlog items because both predate this one and the record path already has both:

- **Decode coverage (R1033).** The fact store has no rows for a `@table`-only target. `graphitron_node_id_instruction_rule` joins `graphitron_node_type`, so `NodeIdDecodeCoverage` (the rule that fails a build when an authored `@nodeId` is neither decoded nor refused) never censuses these coordinates. No store relation rejects them either, so nothing conflicts with the walk. This item does not weaken coverage: every coordinate it opens was a walk rejection before and is a walk install after, and the ledger records both. Widening the census means a third `resolved_type_kind` for catalog-metadata targets, keyed on `graphitron_tabletype` joined to `sql_node_metadata`, which pulls the endpoint, hop and landing views along. Once it exists, the registry above can be re-sourced from that relation, and the named-parameter slot can widen.
- **LSP (R1034).** `Diagnostics.judgeNodeTypeName` in `graphitron-lsp` flags as "Unknown @node type" any `@nodeId(typeName:)` target missing from `graphitron_node_entry` (authored `@node`). That already includes inferred node types and the record path's `@table`-only targets, and it will include these.

## Tests

**Classification tier.** In `TenantBindingClassificationTest`, R1029's `aScalarArgumentOrFilterFieldNamingATableOnlyTypeRejectsInTheClassifier` flips. It becomes `aScalarArgumentOrFilterFieldNamingATableOnlyTypeDecodesFromCatalogMetadata`: both `Query.baz` and `Query.bazes` classify, and neither binds a tenant slot (`baz` is global). Two new cases sit beside it:

- **Tenant in the key routes.** `type FilmActorCode @table(name: "film_actor")` (metadata key `ACTOR_ID, FILM_ID`) named by a filter input field under the `film_id` tenant column. Asserts `DecodedKeySlot` at slot 1 whose `decode` is `decodeFilmActorCode`. `nodeIdFilterInputFieldDivinesTheDecodedSlot` is its node-type twin and keeps passing unchanged.
- **The record decline's new reason.** `aJooqRecordParameterDecodingATableOnlyTypeWithTheTenantInItsKeyDeclines` keeps its two assertions and gains one on the reworded reason.
- **Slot miss reports evidence without throwing.** A `NodeIdDecodeKeys` leaf in a `@service` argument (`collectFromServiceLeaf`) naming `Baz`, which has no tenant column. The fold completes and reads the target table off `decode.table()`. Today this case throws in `nodeTypeOf`.

In `NodeIdLeafResolverTest`, one case per arm with a `@table`-only target, asserting the arm and that its `decodeMethod()` is `decode<T>` with the metadata's type id and key columns: `SameTable`, `DirectFk`, `TranslatedFk` (`nodeidfixture.parent_node` / `child_ref`) and `AuthorOwnedPredicate`. Plus the two remaining rejections. A `@table`-only type over `nodeidfixture.qux`, which has no metadata, rejects with `resolveTargetKeys`' existing "not a @node type (no NodeId catalog metadata, no @node directive)" text. A type carrying `@node` without `implements Node`, over `qux`, rejects with the `OfDeclaredNode` text and no longer advises adding `@node`. A `BuildContext`-level case pins `TargetKeys`' arm per source: node, catalog and declared node.

**Pipeline tier.** A new `TableOnlyNodeIdLeafPipelineTest` over the `idreffixture` tables, which are the sis shape: `studieprogram` carries metadata (type id `Studieprogram`, key `STUDIEPROGRAM_ID`), and `studierett` has two non-key foreign keys to it (`studierett_studieprogram_id_fkey` and `studierett_registrar_studieprogram_fkey`), as `EMNEVALG_I_EMNEKOMBINASJON` does to `STUDIENIVA`. The target is `type Studieprogram @table(name: "studieprogram") { kode: String @field(name: "studieprogram_id") }`, with no node type anywhere in the SDL. One case per position:

- A DML `UPDATE` input on `studierett` with two fields, each through `@reference(path: [{key: ...}])` naming one of the two foreign keys. The generated `UPDATE` sets both foreign-key columns from `NodeIdEncoder.decodeStudieprogram(...)`, and the decode lands in the SET partition, not the key partition.
- An `UPDATE` through an auto-discovered foreign key. `studierett`'s two foreign keys make discovery ambiguous, so this case uses `nodeidfixture.reordered_fk_child` against a `@table`-only type over `reordered_pk_parent`, which has one foreign key. It also covers a composite key whose foreign key is declared in a permuted column order.
- A filter input field, scalar `ID`, and a filter input field, `[ID!]`, on a query over `studierett`.
- A top-level filter argument, `[ID!] @nodeId(typeName: "Studieprogram")`.

Each asserts the field classifies and that the generated `NodeIdEncoder` declares `decodeStudieprogram` with no `encodeStudieprogram`. One `NodeIdEncoder`-level assertion that a `@table` type named by no leaf gets no helper pins the referenced-only rule. The compilation tier compiles the generated sources, which proves the helper's body.

**Execution tier.** sakila-example's execution schema reads the `public` schema, which has no metadata table without a node type. Add a pair beside the `xlat_*` fixture in `init.sql`, with a `NodeIdFixtureGenerator.METADATA` entry: a code table (say `study_level`, key `code`) and a child (say `level_interval`, with two non-key foreign keys `from_level` and `to_level` to it), seeded. In `schema.graphqls`, `StudyLevel @table` without `implements Node`, an `UPDATE` mutation on `level_interval` taking `fromLevelId` / `toLevelId` through `@reference`, and a filter query over `level_interval` by `[ID!]` level ids. The execution test encodes ids with `NodeIdEncoder.encode("StudyLevel", code)`, runs the update and asserts the row's columns, runs the filter and asserts the rows, and asserts a wrong-type id fails with the generated client error, not as a silent no-match.

## Retired vocabulary

- `nodeTypeOf` (the `TenantBindingIndex.Fold` method), whose read is now `decode.table()`.
- `HelperRef.Decode.nodeTypeName()`, renamed `targetTypeName()`.
- `TargetKeys(typeId, keyColumns, error)`, the record with a nullable error, now sealed over its source.
- "Every `HelperRef.Decode` is minted from a `NodeType`" and its paraphrases: the fold's javadoc, `HelperRef` ("Per-Node decoder helper"), `NodeIdLeafResolver.Resolved` ("helper resolved on the target NodeType"), `NodeIdEncoderClassGenerator` ("for each `@node` type").
- "the table-keyed fallback that used to answer here belonged to the synthesis shims" (the `NodeIdLeafResolver` comment) and "the orphan case of a `@table`-only type over a metadata-carrying table" (`resolveDecodeHelperForType` javadoc).
- "there is no node id decode helper to route on" (the R1029 decline's reason).
- "'T' is not a node type. Annotate 'T' with @node." (the leaf rejection; no arm produces it any more).

## Other solutions we've considered

**Make the target a `NodeType`.** Classify a `@table` type over a metadata table as a node whenever a `@nodeId` names it. Then minting, emission and the fold would need no change. But nodehood is published schema: a `NodeType` joins the `Node` interface's resolution, the entity fetcher dispatch, the reachability seeds and the projected-key rail. `TypeBuilder.buildTableType` refuses exactly this promotion so that a projection over a node table does not become a second node. Here the cost is concrete: a federation stub would become a node in a subgraph that does not own it.

**Emit `decode<T>` for every `@table` type over a metadata table**, instead of registering the ones a leaf names. This needs no registry and is simpler to state. But in sis, where the jOOQ generator stamps metadata on nearly every table, it would emit a helper per projection type, nearly all unused. A table whose metadata key exceeds 22 columns would also emit a `Record23` that does not compile. The registry emits what a carrier calls, which is the rule the node types' helpers follow in practice.

**A store relation for the decode targets**, derived from `graphitron_tabletype`, `sql_node_metadata` and the authored `@nodeId(typeName:)` references, read by the encoder generator. That is the census follow-up, R1033, and once it exists the registry can be re-sourced from it. Doing it here would mean adding a relation whose only consumer is a generator that still reads the model (`renderGlobal`'s "generators still take the model; they migrate family by family"). It would also leave the minting site, which is in the walk, reading a second source for a fact it already resolves. The registry keeps mint and emit on one source until the census exists.

**Give the leaf carriers the record path's shape**, an inline `decodeValues(typeId, id)` with no `NodeIdEncoder` method. Every leaf emitter reads `HelperRef.Decode` opaquely, at about thirty sites, so a second decode shape would fork each of them for a target that differs from a node only in where its type id came from.

**Decline the tenant-in-key case on the leaf, mirroring R1029.** That would keep the leaf and record carriers symmetric for one target type, but only by adding a check that refuses a shape the leaf can already route through the existing `DecodedKeySlot` path. Lifting R1029's record-carrier decline is a separate, additive change: the record carriers would mint their decode through `catalogNodeDecode` and route on it. No consumer has the shape today (every sis target is a code table without the tenant column), so it waits for one.

**A separate registry record beside the reference**, `CatalogNodeDecode(typeName, TableRef, HelperRef.Decode)`, leaving `HelperRef.Decode` as it is. The generator and the fold would then each need a second, name-keyed source to answer which table a `decode<T>` reads, and the fold would consult a `NodeType` or the registry to answer one question. With the table on the reference, every reader asks the carrier.

**Collect the catalog decodes from the classified carriers after the walk**, instead of registering at the mint. `NodeIdDecodeKeys` is held by input fields, arguments, lookup mappings and condition parameters, so the collector would be a walk over all of them that must keep up with every new holder. The mint is one site, and an entry it leaves behind for a leaf that later rejects is never emitted.

## Reviewer findings

### Round 1 (2026-10-14, Spec -> Ready, reviewer session 01975EwYBfH2AidWiqEK9uuo)

Verdict: withhold. One finding, on question one, and cheap to resolve. The goal reads on its own:
a `@nodeId(typeName: T)` on a DML input, a filter input or a filter argument stops rejecting when
`T` is a `@table`-only type over a table with catalog node metadata, and decodes the way the record
path already does. The plan extends the shape in the tree (one `HelperRef.Decode` per target,
emitted by `NodeIdEncoderClassGenerator`) rather than standing a second decode beside it, and I
would hand the minting, registry, emission and naming sections to an implementer as written.

**Finding 1 (question one: a claim about the tree does not hold). The "slot miss reports evidence
without throwing" test cannot be built from SDL, and "today this case throws in `nodeTypeOf`" is
not what happens today.**

`TenantBindingIndex.Fold.collectFromServiceLeaf` is reached only through `collectFromValueShape`
from a root `@service` call's structured arguments. Every `NodeIdDecodeKeys` there comes from
`ServiceCatalog.nodeIdSlotExtraction`, whose non-record arm ends at
`BuildContext.resolveDecodeHelperForType`; an input-bean member that is not a jOOQ record is
refused by `InputBeanResolver.singleValuedMemberDeferral`. The spec keeps
`resolveDecodeHelperForType` node-only on purpose (its "stays node-only" section). So a `@service`
argument naming `Baz` with a non-record Java parameter is refused in `ServiceCatalog` with "names no
@node type, so no decode helper exists", today and after this item alike; it never reaches the fold.
No carrier this item opens reaches the `nodeTypeOf` branch: the leaves it opens enter the fold
through `accessOf`, which does not call `nodeTypeOf`. R1029's own test comment in
`aScalarArgumentOrFilterFieldNamingATableOnlyTypeRejectsInTheClassifier` says the same.

That leaves the fold section's motivation and its test out of step with the code. The change itself
(the fold reads `decode.table()`, `nodeTypeOf` goes) is sound and follows from the reference
carrying its table; what is missing is a true statement of why it is in this item and a test that
can exist. What would satisfy it, the author's choice:

- Keep the change, say it is preparation for the named-parameter slot widening under R1033 (no
  carrier in this item reaches the branch), and either pin it with a fold-level test that constructs
  the `NodeIdDecodeKeys` carrier directly, or move the test to R1033, where SDL first reaches it.
- Or leave `nodeTypeOf` in place, drop the test, and record in R1033 that widening the slot must
  retire it.

Either way, correct "Today this case throws in `nodeTypeOf`" and the matching `Retired vocabulary`
entry if the method stays.
