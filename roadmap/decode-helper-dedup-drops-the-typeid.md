---
id: R949
title: "Two @nodeId input fields over one table share a decoder that hardcodes one typeId"
status: Spec
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

## Implementation

The decode helper's identity is the thing to fix, and the fix restores a policy the rest of the node
machinery already holds rather than choosing between two keys. `FieldBuilder`'s by-table node index
is one-to-many, and every implicit "the node type for this table" resolution refuses when a table
backs more than one `@node` (`IdEncoderResolution.Ambiguous` for carrier data fields,
`ambiguousImplicitNodeError` for a bare-`ID` `@mutation` return); `ServiceCatalog.inferNodeTypeAtSlot`
applies the same rule to a `@nodeId` with no `typeName`. Only the explicit-decode dedup, where the
author *did* name the type, silently picked one. A decode body is a function of one node type: its
typeId, its key columns and its table all derive from the `@node` declaration, and the type name is
unique within a schema. The change makes the node type name the identity and the name of the
helper, so the pair above emits `decodePersonRecord` and `decodeOpptakPersonRecord`, each carrying
its own typeId. The name is an already-captured fact that reaches the emit side today
(`KeyProjection.nodeTypeName`), so threading it onto the decode leaf adds no walk-side discovery.

The key and the name are the same defect twice, and both halves move together or neither does: `InputBeanInstantiationEmitter.collectRecordDecoders` dedups on the record class, and the helper name is minted from the record class, so a fix that widens only the map key emits two methods named `decodePersonRecord` on one class, a compile error at the consumer rather than the mistyped decode. (This item absorbed R947, which stated that first; see `roadmap/changelog.md`.)

*The resolution and the leaf carry the type name.* `BuildContext.NodeIdRecordDecode.Resolved` gains
a `typeName` component beside `typeId`, as its polymorphic sibling `NodeIdRecordDecode.Candidate`
already has, so the one carrier in this family that dropped the name stops dropping it.
`CallSiteExtraction.NodeIdDecodeRecord` gains the same component, validated non-empty like the
others, mirroring `CallSiteExtraction.PolymorphicCandidate`, whose javadoc already says the
per-candidate helper is named from it. The three sites that mint the leaf then read the name off a
carried fact rather than re-supplying a local: `InputBeanResolver` and `ServiceCatalog` read
`resolved.typeName()`, and `TypeFetcherGenerator.collectProjectionDecoders` reads
`KeyProjection.nodeTypeName()`. Reading rather than re-threading is the point: three producers each
supplying a string the resolver had is three chances for the name and the typeId on one leaf to
disagree, which is this bug's own failure mode one level up.

*Collection keys on the type name.* `InputBeanInstantiationEmitter.collectRecordDecoders`,
`TypeFetcherGenerator.collectProjectionDecoders` and `TypeFetcherGenerator.collectParamRecordDecoders`
(through its `record0` helper) dedup into `Map<String, NodeIdDecodeRecord>` keyed by
`rec.typeName()`, in place of `Map<ClassName, NodeIdDecodeRecord>` keyed by
`CatalogRefs.recordClass(rec.table())`. Two leaves with the same type name are the same decode by
construction, since the node type determines the body; the `putIfAbsent` first-wins stays and is
now correct rather than lossy. The scalar and list maps keep their existing relationship: every type
lands in the scalar map, list-valued occurrences additionally land in the list map, and the list
helper delegates to the scalar one per element.

*Naming keys on the type name, and the namespace keeps an enforcer.* `FetchersHelperNames` replaces
`Map<ClassName, String> decodeStems` with the set of collected node type names;
`decodeSingular(String typeName)` returns `"decode" + typeName + "Record"`, `decodeList` appends
`List`, and `decodeContainerSingular(containerName)` returns `"decode" + containerName + "Record"`
directly. The `of(...)` factory takes `Collection<String> decodeTypeNames` in place of
`Collection<ClassName> decodeRecordClasses`. The package-segment `disambiguate` pass stays for the
`create*` namespace, where two schemas' same-simple-named record classes are a real collision, and is
no longer applied to `decode*`. The `containerStems` pass, which appended an ordinal while a
container's name was already claimed by a record class's stem, goes with it: a container is a GraphQL
type name and so is a node type, and one schema cannot declare both under one name.

What replaces those two passes is not an argument but a smaller check. `of(...)` builds one claimed
set over both arms' emitted names (each node type's and each container's singular and `List` forms)
and throws on a duplicate claim, in the same message shape as the existing routing-hole refusal, so
the invariant "every decode-family name on one class is distinct" has one home and fires the day a
producer feeds the namespace a name that is not a schema type name. The uncollected-name refusal
stays on *both* arms: the record arm's `required(...)` check and the container arm's
`containerStem` check both survive, since a silent fallback would name a helper nothing emitted. One
pre-existing gap is noted so the removal is not read as having closed it: `decodeContainerMember`
mints `decode<Container>Record<Member>`, a third claimant nothing checks against the other two in
either the old or the new scheme; it joins the claimed set here at no extra cost.

*Call sites pass the name.* The four sites that ask the resolver for a decode helper by record
class ask by type name instead: `InputBeanInstantiationEmitter` (the input-bean member expression),
`ArgCallEmitter` and `ServiceMethodCallEmitter` (the producer-parameter arms), and
`TypeFetcherEmissionContext.projectedKeyHost`, which passes `projection.nodeTypeName()` in place of
`CatalogRefs.recordClass(projection.nodeTable())`. `TypeFetcherGenerator`'s emission loop over the
two collected maps builds each helper from the leaf it holds, which already carries the typeId that
body needs; only the name it asks for changes.

Generated names are unchanged wherever a node type's name equals its table's record stem, which is
every type in the sakila fixtures and the common case in consumer schemas: `Film` over `film` spells
`decodeFilmRecord` under both keys. A type whose name differs from its table's record stem (a
`Movie` over `film`, today `decodeFilmRecord`) is renamed to `decodeMovieRecord`. The helpers are
private static members of the generated `<Type>Fetchers` class, so the rename is invisible at the
consumer's compile boundary; it shows only as a diff in emitted sources.

## Tests

Two tiers, layered the way the neighbouring `@nodeId`-record fixtures already are: the pipeline
tier pins the shape and the execution tier pins the behaviour. The refusal of a wrong-type id at the
*second* field is the regression pin, since the current generator passes any test that only checks a
valid id works.

*Pipeline tier (`graphitron`).* `NodeIdRecordInputBeanPipelineTest` already holds
`SIBLING_NODE_SDL`, two node types over `film_actor` with distinct typeIds, and
`siblingNodeTypesOverOneTable_readTheNamedOnesTypeId` proves the leaf reads the named sibling's
typeId. A new case extends that fixture with an input bean carrying one member per sibling and
asserts, on the generated `TypeSpec`, that the `<Type>Fetchers` class carries two decode helpers
named after the two types and no ordinal-suffixed name, and, on the classified model, that the two
leaves carry the two type names and typeIds. Existing tests that read a helper name by record class
are updated to ask by type name. In `FetchersHelperNamesTest`, `decodeNamespace_disambiguatesIndependently`
(two same-simple-named record classes from two packages) is retired without a unit-tier replacement:
after the change the resolver never sees a record class on the decode axis, and a "two names, two
helpers" case at that seam would restate `"decode" + name + "Record"` at itself; the claim with
content, two node types over one table yield two helpers on one class, is the pipeline case above.
What stays at the unit tier is what the pipeline tier cannot reach: the uncollected-name refusal,
extended to the container arm, and one case that a duplicate claim across the two arms throws.
`FetchersHelperNameCollisionPipelineTest` is re-read for any decode-side expectation and adjusted.

*Execution tier (`graphitron-sakila-example`).* The second node type must sit over a table that
backs no bare-`ID` `@mutation` return and no `typeName`-less `@nodeId`, because the one-node-per-table
refusals above turn each such coordinate into an `UnclassifiedField` the moment a second `@node`
lands on the table. `film` backs at least four of them (`deleteFilms`, `deleteFilmsByReleaseYear`,
`deleteFilmNested` with bare-`ID` returns and the `deleteFilmsIdCarrier` payload), so it is out. `language`
backs none: `Language` is a plain `@table` type with no `ID` field, `LanguageNode`'s `id: ID! @nodeId`
resolves through its containing type rather than by table, no `@mutation` names the table, and it
is seeded with three rows. The fixture is therefore:

- `type LanguageAlias implements Node @table(name: "language") @node(keyColumns: ["language_id"])`
  with `id: ID! @nodeId` and `name: String` and nothing more, beside `LanguageNode`;
- `input LanguagePairAssignmentInput { language: ID! @nodeId(typeName: "LanguageNode"), alias: ID!
  @nodeId(typeName: "LanguageAlias") }`;
- `record LanguagePairAssignment(LanguageRecord language, LanguageRecord alias)` in
  `graphitron-sakila-service` beside `FilmRecordAssignment`, and
  `FilmReviewService.assignLanguagePair` returning `"language:<id>,alias:<id>"` from the two records'
  key columns;
- `Mutation.assignLanguagePair(in: LanguagePairAssignmentInput!): String` as a `@service` field
  beside `assignFilmRecord`.

Three `GraphQLQueryTest` cases beside `assignFilmRecord_decodesNodeIdIntoJooqRecordMember`:

- `assignLanguagePair_eachFieldDecodesItsOwnType`: a `LanguageNode` id for language 1 and a
  `LanguageAlias` id for language 2 round-trip to `"language:1,alias:2"`, minted with
  `NodeIdEncoder.encode("LanguageNode", 1)` and `NodeIdEncoder.encode("LanguageAlias", 2)`.
- `assignLanguagePair_nodeIdAtAliasField_refuses`: a valid `LanguageNode` id at `language` and a
  second `LanguageNode` id at `alias`. The response carries an error and no data, the message being
  the redacted form the privacy contract prescribes and `assignFilmRecord_wrongTypeNodeId_throwsDecodeMismatch`
  already pins. This is the regression pin: on the current generator the request succeeds and
  returns `"language:1,alias:2"`, because the shared helper checks every id against `"LanguageNode"`,
  and the wrong-type id at `alias` is the half of the bug that is silent today.
- `assignLanguagePair_aliasIdAtLanguageField_refuses`: the mirror image, a `LanguageAlias` id at
  `language` with a valid `LanguageAlias` id at `alias`, refused the same way. The current generator
  refuses this one too, but for the accidental reason that `"LanguageNode"` won the shared body; the
  case pins that the refusal survives once each field checks its own type.

`GeneratedSourcesSmokeTest` gains no expectation: the fixture adds one node type whose fetchers
class the existing expectations do not enumerate, and the decode helpers land on an existing
`MutationFetchers` class. `GeneratorDeterminismTest` covers the rename by construction.

## Retired vocabulary

- `FetchersHelperNames.decodeSingular(ClassName)` / `decodeList(ClassName)`: the record-class-keyed
  decode naming. Replaced by the type-name-keyed overloads.
- `FetchersHelperNames.containerStems` and the ordinal-suffixed container stem
  (`decode<Container>Record1`): the container-versus-record-class collision arm, unreachable once
  both namespaces are GraphQL type names. The class javadoc's framing of the `decode*` namespace as
  "keyed on a Java class" and its bullet on container-versus-record-class stem resolution go with it.
- The `Map<ClassName, NodeIdDecodeRecord>` decode dedup maps in `TypeFetcherGenerator` and
  `InputBeanInstantiationEmitter`, and the prose "keyed by jOOQ record type" in their javadoc.

## Other solutions we've considered

*Key on (record class, typeId) and keep record-class naming with an ordinal on collision.* The
smallest diff: the maps take a composite key and `disambiguate` appends an ordinal when two typeIds
share a stem. Rejected because the second helper's name (`decodePersonRecord2`) says nothing about
which type it decodes, and which of the two draws the ordinal depends on field iteration order, so
an unrelated schema edit can swap the names in the emitted sources. Stated against the policy
paragraph above, it is also the wrong shape: the rest of the node machinery already keys on the node
type, and an ordinal would be a second way of telling two node types apart.

*Key on the leaf's structural value.* Identity as "whatever the body is a function of" is
principled, but naming still needs a stem and inherits the ordinal problem above. The type name is
that structural value's own name: the node type determines typeId, key columns and table, so keying
on the name is keying on the value without the ordinal.

*Name the node-type quadruple once and compose it.* After this change `(typeName, typeId,
keyColumns, table)` is spelled component-by-component in five records: `PolymorphicCandidate`,
`NodeIdRecordDecode.Candidate`, `KeyProjection`, `StoreNodeTables.NodeTable` in `graphitron-model`,
and `NodeIdDecodeRecord`. One named value composed into all five would make "same type, same decode"
a property of the value rather than a paragraph, and is the principled end state. Not taken here
because two of the five homes are in `graphitron-model`'s fact-derivation layer and a bug fix that
consolidates the model's node-type carrier across modules is a different change with its own review
questions; the type-name key this item lands is the one that value would carry, so the
consolidation composes on top of it later without redoing this work.

*Also open the decode-mismatch message to the client.* The issue notes that the refusal reaches the
client as "An error occurred. Reference: <UUID>". That is the documented privacy contract for any
exception the schema author has not opted into through an `@error` type (the user manual's
error-channel how-to, "Unmatched exceptions: redact and correlate"), and the lever to surface a
typed message is the consumer's `@error` declaration, which exists. Out of scope here and not a
graphitron gap.

## Reviewer findings

### Round 1 (2026-09-14, Spec -> Ready, reviewer session 01BqMRoYr82JwAqL5av8EdFy)

Verdict: revisions requested. One blocking finding on question two. Question one passes cleanly and
every code claim I checked holds as named; the verification narrative is in the review commit's
message.

**Finding 1 (question two): the plan repoints one of the two hosts that mint `decode<Record>` from
the `KeyProjectionRelation`, and does not say whether the other is in scope.**

`ProjectedKeyHost` has exactly two populated constructions in the tree, both handed the same
`KeyProjection` rows:

- `TypeFetcherEmissionContext.projectedKeyHost()`, which names the helper through
  `FetchersHelperNames.decodeSingular(CatalogRefs.recordClass(projection.nodeTable()))`. The
  call-sites paragraph repoints this one to `projection.nodeTypeName()`.
- `ConditionGlueRenderer.render`, which names it through `RecordDecodeHelperRegistry.register(
  NodeIdEncoderRef.of(outputPackage), projection.typeId(), projection.nodeTypeName(),
  projection.keyColumns(), projection.nodeTable())`. The plan does not mention this host, the
  registry, or the conditions class anywhere.

`RecordDecodeHelperRegistry.register` carries this item's defect in both of the halves the plan says
move together or neither does: `helpers.computeIfAbsent(CatalogRefs.recordClass(nodeTable), ...)` is
the record-class dedup key, and `helperName(recordType)` returns `"decode" + recordType.simpleName()`.
It already receives `nodeTypeName` and spends it only on the failure message. So two node types over
one table, reaching a conditions class through projected `@nodeId` condition parameters, collapse
onto one body with one hardcoded typeId, which is the Goal's bug at a second host. The policy
paragraph's own argument reaches it: the sibling registry in the same package,
`CompositeDecodeHelperRegistry`, already keys on `HelperRef.Decode.methodName()`, which is
`decode<TypeName>`, leaving `RecordDecodeHelperRegistry` the one record-family holdout on a
record-class key.

Two consequences the implementer would otherwise have to settle alone, either of which changes what
ships:

- Left as is, the Goal holds at a `@service` input bean and not at a `@nodeId` condition parameter
  over the same pair of types. The Goal does not scope itself that way.
- The rename the plan accepts (a `Movie` over `film` becoming `decodeMovieRecord`) applies only to
  the fetchers host, so one node type can afterwards be `decodeMovieRecord` on its `<Type>Fetchers`
  class and `decodeFilmRecord` on a conditions class for the same decode. `## Retired vocabulary`
  retires the record-class framing from `FetchersHelperNames` while
  `RecordDecodeHelperRegistry.helperName(ClassName)` keeps it verbatim.

What would satisfy it: a settled answer in `## Implementation`, either way.

- In scope: name `RecordDecodeHelperRegistry.register` / `helperName` and `ConditionGlueRenderer`'s
  host alongside the four call sites, say what the conditions-class dedup keys on afterwards, and
  give `## Tests` a pin. `ConditionGluePipelineTest` already exercises the projected-condition
  decode.
- Out of scope: say so, say why the divergent emitted names are acceptable in the interim, and file
  the follow-up as a Backlog item. `## Retired vocabulary` should then record that the record-class
  framing survives in `RecordDecodeHelperRegistry`, so the Done-gate retirement sweep does not read
  the surviving copy as a miss.

**Non-blocking.** The execution-tier fixture makes `language` the first table in
`graphitron-sakila-example/src/main/resources/graphql/schema.graphqls` to back two node types; today
no table there backs more than one. The comment above `LanguageNode` states the opposite as the
reason its `@referenceFor` fixture reads unambiguously ("the table backs exactly one node type and
typeName: resolves unambiguously"), and goes stale the moment `LanguageAlias` lands beside it.
Nothing breaks, since `LanguageStockFilter.languageId` names its type explicitly, but the sentence
is worth rewriting in the same edit rather than leaving it to contradict the fixture below it.
