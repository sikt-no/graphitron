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

The decode helper's identity is the thing to fix. A decode body is a function of one node type:
its typeId, its key columns and its table all derive from the `@node` declaration, and the type
name is unique within a schema. Today the generator identifies the helper by the jOOQ record class
alone, which is a projection of the node type that loses exactly the distinction two types over one
table need. The change makes the node type name the identity and the name of the helper, so the
pair above emits `decodePersonRecord` and `decodeOpptakPersonRecord`, each carrying its own typeId.

*The leaf carries the type name.* `CallSiteExtraction.NodeIdDecodeRecord` gains a `typeName`
component next to `typeId`, validated non-empty like the others, mirroring
`CallSiteExtraction.PolymorphicCandidate`, which already carries `typeName` and `typeId` side by
side with the javadoc stating that the per-candidate helper is named from the former. The three
sites that mint the leaf have the name in hand and pass it through: `InputBeanResolver` (the
`typeName` it resolved the `@nodeId` against), `ServiceCatalog` (its `nodeTypeName` local at the
producer-parameter arm), and `TypeFetcherGenerator.collectProjectionDecoders` (the
`KeyProjection.nodeTypeName` component of each row). `BuildContext.NodeIdRecordDecode.Resolved` is
unchanged: every caller that receives it also holds the name it asked with.

*Collection keys on the type name.* `InputBeanInstantiationEmitter.collectRecordDecoders`,
`TypeFetcherGenerator.collectProjectionDecoders` and `TypeFetcherGenerator.collectParamRecordDecoders`
(through its `record0` helper) dedup into `Map<String, NodeIdDecodeRecord>` keyed by
`rec.typeName()`, in place of `Map<ClassName, NodeIdDecodeRecord>` keyed by
`CatalogRefs.recordClass(rec.table())`. Two leaves with the same type name are the same decode
by construction, since the node type determines the body; the `putIfAbsent` first-wins stays and
is now correct rather than lossy. The scalar and list maps keep their existing relationship: every
type lands in the scalar map, list-valued occurrences additionally land in the list map, and the
list helper delegates to the scalar one per element.

*Naming keys on the type name.* `FetchersHelperNames` replaces its `Map<ClassName, String>
decodeStems` with a set of collected type names; `decodeSingular(String typeName)` returns
`"decode" + typeName + "Record"` and `decodeList` appends `List`, and both keep the existing
refusal for a name that was never collected, since a silent fallback would name a helper nothing
emitted. The `of(...)` factory takes `Collection<String> decodeTypeNames` in place of
`Collection<ClassName> decodeRecordClasses`. The package-segment `disambiguate` pass stays for the
`create*` namespace, where two schemas' same-simple-named record classes are a real collision, and
stops being applied to `decode*`, where the key is now unique by the schema's own rules. The
polymorphic `containerStems` pass, which appended an ordinal while a container's `decode<Container>
Record` name was already claimed by a record class's stem, becomes unreachable for the same reason:
a container is a GraphQL type name and so is a node type, and one schema cannot declare both under
one name. It is removed, and `decodeContainerSingular` returns `"decode" + containerName +
"Record"` directly, so the two single-namespace arms read the same way. `decodeContainerMember`
is unchanged.

*Call sites pass the name.* The four sites that ask the resolver for a decode helper by record
class ask by type name instead: `InputBeanInstantiationEmitter` (the input-bean member expression),
`ArgCallEmitter` and `ServiceMethodCallEmitter` (the producer-parameter arms), and
`TypeFetcherEmissionContext.projectedKeyHost`, which passes `projection.nodeTypeName()` in place of
`CatalogRefs.recordClass(projection.nodeTable())`. `TypeFetcherGenerator`'s emission loop over the
two collected maps builds each helper from the leaf it holds, which already carries the typeId that
body needs; only the name it asks for changes.

Generated names are unchanged wherever a node type's name equals its table's record stem, which is
every type in the sakila fixtures and the common case in consumer schemas: `Film` over `film`
spells `decodeFilmRecord` under both keys. A type whose name differs from its table's record stem
(a `Movie` over `film`, today `decodeFilmRecord`) is renamed to `decodeMovieRecord`. The helpers
are private static members of the generated `<Type>Fetchers` class, so the rename is invisible at
the consumer's compile boundary; it shows only as a diff in emitted sources.

## Tests

Two tiers, layered the way the neighbouring `@nodeId`-record fixtures already are: the pipeline
tier pins the shape and the execution tier pins the behaviour. The refusal at both fields is the
regression pin, since the current generator passes any test that only checks a valid id works.

*Pipeline tier (`graphitron`).* `NodeIdRecordInputBeanPipelineTest` already holds
`SIBLING_NODE_SDL`, two node types over `film_actor` with distinct typeIds, and
`siblingNodeTypesOverOneTable_readTheNamedOnesTypeId` proves the leaf reads the named sibling's
typeId. A new case extends that fixture with an input bean carrying one member per sibling and
asserts, on the generated `TypeSpec`, that the `<Type>Fetchers` class carries two decode helpers
named after the two types and no ordinal-suffixed name, and, on the classified model, that the two
leaves carry the two type names and typeIds. The existing tests that read the helper name by
record class are updated to ask by type name. `FetchersHelperNamesTest` replaces
`decodeNamespace_disambiguatesIndependently` (two same-simple-named record classes from two
packages, which the type-name key makes unreachable) with a case that two type names over one
record class name two helpers, and a case that the populated resolver still refuses a type name it
never collected.

*Execution tier (`graphitron-sakila-example`).* The example schema gains a second node type over
`film`, `FilmAlias implements Node @table(name: "film") @node(keyColumns: ["film_id"])`, with the
same field surface as the neighbouring record-input fixtures need and nothing more; an input
`FilmPairAssignmentInput { film: ID! @nodeId(typeName: "Film"), alias: ID! @nodeId(typeName:
"FilmAlias") }`; a `FilmPairAssignment(FilmRecord film, FilmRecord alias)` record in
`graphitron-sakila-service` beside `FilmRecordAssignment`; and a `FilmReviewService.assignFilmPair`
method returning `"film:<id>,alias:<id>"` from the two records' key columns, wired as a
`Mutation.assignFilmPair(in: FilmPairAssignmentInput!): String` `@service` field beside
`assignFilmRecord`. Three `GraphQLQueryTest` cases beside `assignFilmRecord_decodesNodeIdIntoJooqRecordMember`:

- `assignFilmPair_eachFieldDecodesItsOwnType`: a `Film` id for film 3 and a `FilmAlias` id for film
  5 round-trip to `"film:3,alias:5"`, minted with `NodeIdEncoder.encode("Film", 3)` and
  `NodeIdEncoder.encode("FilmAlias", 5)`.
- `assignFilmPair_filmIdAtAliasField_refuses`: a valid `Film` id at `film` and a second `Film`
  id at `alias`. The response carries an error and no data, the error message being the redacted
  form the privacy contract prescribes and `assignFilmRecord_wrongTypeNodeId_throwsDecodeMismatch`
  already pins. This is the regression pin: on the current generator the request succeeds and
  returns `"film:3,alias:5"`, because the shared helper checks every id against `"Film"`, and the
  wrong-type id at `alias` is the half of the bug that is silent today.
- `assignFilmPair_aliasIdAtFilmField_refuses`: the mirror image, a `FilmAlias` id at `film` with a
  valid `FilmAlias` id at `alias`, refused the same way. The current generator refuses this one too,
  but for the accidental reason that `"Film"` happened to win the shared body; the case pins that
  the refusal survives once each field checks its own type.

`GeneratedSourcesSmokeTest` is unaffected: the fixture adds no generated class, only helpers on an
existing `MutationFetchers` class. `GeneratorDeterminismTest` covers the rename by construction.

## Retired vocabulary

- `FetchersHelperNames.decodeSingular(ClassName)` / `decodeList(ClassName)`: the record-class-keyed
  decode naming. Replaced by the type-name-keyed overloads.
- `FetchersHelperNames.containerStems` and the ordinal-suffixed container stem
  (`decode<Container>Record1`): the container-versus-record-class collision arm, unreachable once
  both namespaces are GraphQL type names.
- The `Map<ClassName, NodeIdDecodeRecord>` decode dedup maps in `TypeFetcherGenerator` and
  `InputBeanInstantiationEmitter`, and the prose "keyed by jOOQ record type" in their javadoc.

## Other solutions we've considered

*Key on (record class, typeId) and keep record-class naming with an ordinal on collision.* The
smallest diff: the maps take a composite key and `disambiguate` appends an ordinal when two typeIds
share a stem. Rejected because the second helper's name (`decodePersonRecord2`) says nothing about
which type it decodes, and which of the two draws the ordinal depends on field iteration order, so
an unrelated schema edit can swap the names in the emitted sources.

*Key on the leaf's structural value.* Identity as "whatever the body is a function of" is
principled, but naming still needs a stem and inherits the ordinal problem above. The type name is
that structural value's own name: the node type determines typeId, key columns and table, so keying
on the name is keying on the value without the ordinal.

*Also open the decode-mismatch message to the client.* The issue notes that the refusal reaches the
client as "An error occurred. Reference: <UUID>". That is the documented privacy contract for any
exception the schema author has not opted into through an `@error` type (the user manual's
error-channel how-to, "Unmatched exceptions: redact and correlate"), and the lever to surface a
typed message is the consumer's `@error` declaration, which exists. Out of scope here and not a
graphitron gap.
