---
id: R195
title: Decode @nodeId into jOOQ-record-typed @service input-bean fields
status: Spec
bucket: feature
priority: 5
theme: model-cleanup
depends-on: []
created: 2026-05-20
last-updated: 2026-06-01
---

# Decode @nodeId into jOOQ-record-typed @service input-bean fields

## Problem

A `@service` mutation whose input bean has a field typed as a jOOQ generated
`*Record` miscompiles into a runtime `ClassCastException`. Reported shape:

```graphql
input TilordneSaksbehandlerInputV3Input
    @record(record: { className: "no.sikt.fs.opptak.saksbehandling.records.TilordneSaksbehandlerInputV3" }) {
    sakId: ID! @nodeId(typeName: "Sak")
    nyBrukerId: ID! @nodeId(typeName: "Bruker")
    eksisterendeBrukerId: ID @nodeId(typeName: "Bruker")
}
```

The consumer bean `TilordneSaksbehandlerInputV3` is a JavaBean whose setters take
jOOQ records: `setSakId(SakRecord)`, `setNyBrukerId(BrukerRecord)`,
`setEksisterendeBrukerId(BrukerRecord)`.

`InputBeanResolver.buildInputBeanBody` (`InputBeanResolver.java:277-301`)
classifies each SDL input field by GraphQL *type* alone: input-object → recurse,
enum → `EnumValueOf`, everything else → `CallSiteExtraction.Direct`. `ID!` is a
scalar, so it lands on `Direct`, and the `@nodeId(typeName:)` directive is never
read (the resolver consults zero directives). `InputBeanInstantiationEmitter`'s
`directExpr` (`InputBeanInstantiationEmitter.java:140-148`) then emits:

```java
SakRecord sakId = (SakRecord) raw.get("sakId");
```

`raw` is the graphql-java argument map; the value for an `ID` slot is a wire
`String`, so the cast throws `ClassCastException` at the first request that
reaches the mutation. The build passed; the failure is in production.

This is the exact runtime-`ClassCastException` family the R150 design comment
already warns against (`InputBeanResolver.java:389-397`: "Silent fallback to
`Direct` would re-introduce the runtime `ClassCastException` R150 exists to
eliminate"). The guard that comment describes only runs on the *top-level*
parameter via `looksLikeBeanCandidate`; a jOOQ-record-typed *member field* of an
otherwise-valid bean slips straight through to `Direct`.

## Scope of this item

This item is the concrete, reported case: an `@service` input bean (resolved
from the method parameter's Java type; `@record` is binding-irrelevant here, it
is warning-only under R96) whose **member field** is a jOOQ `*Record` and whose
SDL field carries `@nodeId(typeName:)`. The original R195 framing ("the
parameter *itself* is a jOOQ record", `@table` + `@field(name:)` translation) is
explicitly **deferred** ; see "Deferred / out of scope" below.

**Key arity: all arities are in scope (single-column and composite).** The
driving case (`Sak`, `Bruker`) happens to be single-PK, but a `@nodeId` member
backed by a composite-PK NodeType (`FilmActorRecord`, PK `(actor_id, film_id)`)
must decode and materialize just like a single-key one. Composite keys are *not*
a separate feature here: the `record.from(values, keyFields)` materialization
(see "Materialization" below) is arity-agnostic (the `Field...` varargs is the
full key-column list, single or composite), and the raw decode unpack
(`NodeIdEncoder.decodeValues`) already returns all key values per NodeType.
Punting composite to a future item would ship a half-feature that throws a build
error on a legitimate, mechanically-supported shape; that is not acceptable.

Two deliverables, both required:

1. **Decode happy path.** Read `@nodeId(typeName:)` on a jOOQ-record-typed bean
   field, emit a NodeId-decode-and-materialize instead of a cast. Works for any
   key arity.
2. **Loud rejection (load-bearing half).** Any jOOQ-`Record`-typed bean member
   without a *handled* decode strategy is rejected at generation time, never
   falls through to `Direct`. After this item, a jOOQ-record member has exactly
   two outcomes: decode-record leaf, or typed `Result.Failed`. `Direct` never
   sees one again.

> **Rescope (In Review → Spec, R260-adjacent review).** v1 landed single-column
> keys only and rejected composite-PK members with a "not yet supported" build
> error. That deferral was rejected on review: a composite-PK `@nodeId` record
> member is a legitimate shape the materialization already supports, so the punt
> was a gratuitous half-feature. This spec now scopes composite keys *in*. What
> already landed (single-key happy path + rejection plumbing) stays; the
> remaining delta is dropping the single-column-key gate, converting the
> composite-rejection test into a composite-success test, and adding composite
> coverage across the pipeline / compile / execute tiers. The next `Spec → Ready`
> sign-off must be a session other than the one that landed this rescope.

## Design (resolved for Spec)

### Generated shape

Emitted as a named private helper on the `*Fetchers` class (the Helper-locality
convention), so the bean-field line stays a one-liner and the helper body is
readable statement form (explicit types, named locals, no `var`, no
underscore-prefixed names ; per the new "Generated code is read and debugged"
principle in `rewrite-design-principles.adoc`):

```java
SakRecord sakId = decodeSakRecord(raw.get("sakId"));
...
private static SakRecord decodeSakRecord(Object wire) {
    if (!(wire instanceof String nodeId)) {
        return null;                          // nullable ID, or absent key → null member
    }
    String[] values = NodeIdEncoder.decodeValues("Sak", nodeId);
    if (values == null) {
        throw GraphqlErrorException.newErrorException()
            .message("Decoded NodeId did not match the expected type for this argument").build();
    }
    SakRecord record = new SakRecord();
    record.from(values, Tables.SAK.SAK_ID);   // positional array → fields; jOOQ converts per DataType
    return record;
}
```

For a non-null SDL field (`ID!`) the helper throws on a `null` decode; for a
nullable field (`ID`) it returns `null`. Whether a *null wire value on an `ID!`
field* should throw vs. defer to Jakarta non-null validation is settled in
implementation against the existing non-null-arg behaviour; the decode-mismatch
throw above is unconditional.

### Materialization: `record.from(values, fields)`, not `fromMap(intoMap())`

**This supersedes v1's `record.fromMap(key.intoMap())`.** Researching how the
legacy generator solved the same problem (`graphitron-common`
`NodeIdStrategy.setFields` / `nodeIdToTableRecord`) surfaced a materially leaner
mechanism that this item adopts:

```java
// legacy NodeIdStrategy.setFields:
String[] values = unpackIdValues(typeId, id, keyColumnFields);  // base64 → raw String[]
record.from(values, keyColumnFields);                           // positional; jOOQ converts each via DataType
```

`org.jooq.Record.from(Object source, Field<?>... fields)` copies a raw `String[]`
positionally into exactly the named fields, running each value through that
field's `DataType` converter. One hop: `base64 → String[] → target record`.

v1 instead went `base64 → NodeIdEncoder.decodeSak(...)` (a *throwaway* typed
`RecordN`, built via `newRecord(cols)` + per-column `set(convert(...))`) →
`key.intoMap()` (a name-keyed `Map`) → `target.fromMap(map)` (re-match by name).
Three intermediate representations and a whole throwaway record materialized only
to be copied out again. That is the "build a record to build a record" smell.

The generated `NodeIdEncoder` already exposes the raw unpack every `decode<Type>`
calls first (`static String[] decodeValues(String expectedTypeId, String base64Id)`,
type-id-checked, returns `null` on mismatch). The decode-record helper bypasses
the typed `RecordN` and calls it directly, then `record.from(values, <keyFields>)`.

- **Encoder access.** `decodeValues` is package-private and the helper lands in a
  different package (`…generated.fetchers` vs `…generated.util`). Expose it:
  make `decodeValues` `public`, or generate a public `unpack<Type>(String): String[]`
  wrapper. (Implementation choice; prefer the smaller surface.)
- **Arity-agnostic by construction.** `record.from(values, field1, field2, …)` takes
  the full key-`Field` list as varargs, so single- and composite-PK are the same
  call; the `Field[]` is `tableFieldsBlock(<keyColumns>)`, the columns resolved per
  "Decode-helper resolution" below. Positional: `values[i]` ↔ `fields[i]`, and the
  encoder packs values in key-column order, so order is consistent end to end.
- **No encoder-generator change for the decode itself.** The encoder already emits
  `decodeValues`; only its visibility (or a thin public wrapper) changes.

> **Legacy lineage / further option.** Legacy wraps this in a single *runtime*
> helper `nodeIdToTableRecord(id, typeId, List<TableField>)` (decode → `newRecord` →
> `from` → return), so it generates *no* per-type materialization code at all. The
> rewrite has deliberately moved off the runtime `NodeIdStrategy` onto generated
> `NodeIdEncoder` statics, so a pure runtime helper does not fit; but the same
> consolidation is available as *one* generated helper
> `decodeRecord(String wire, String typeId, TableField<?,?>... fields)` instead of
> N per-type `decode<Type>Record` methods. Whether to collapse to one generic
> helper or keep per-type helpers is a reviewer call; either way the body is the
> `from(values, fields)` shape above, not `fromMap(intoMap())`.

Java-17 note: the helper matches on `wire instanceof String nodeId` (legal in
17). It does **not** pattern-match a parameterised `RecordN` (Java 21+); it
null-checks the `String[]` and calls `record.from`. This sidesteps both the
`(Object) ... instanceof Record1` dance the inline emitter needs *and* the
throwaway-`RecordN` allocation.

> **v1 implementation note (superseded).** v1 landed `record.fromMap(key.intoMap())`
> after discovering that `record.from(key)` (POJO reflection) copies nothing from a
> `RecordN` (no `getSakId()` bean property). `fromMap(intoMap())` works but is the
> wasteful three-hop path above; the `from(values, fields)` mechanism this section
> now specifies avoids both the reflection trap and the throwaway record. The
> landed single-key code must be reworked to it (the change is local to the
> generated helper body + exposing `decodeValues`).
> - **`new GraphqlErrorException(String)` does not compile** (only a protected
>   builder constructor exists); the throw uses
>   `GraphqlErrorException.newErrorException().message(..).build()`. The existing
>   inline `ThrowOnMismatch` arms in `ArgCallEmitter` /
>   `CompositeDecodeHelperRegistry` emit the same broken `new GraphqlErrorException($S)`
>   but no compilation-tier fixture reaches them, so the defect is latent there
>   (filed as R265).
> - **A third consumer needed teaching.** The spec said the only consumer is
>   `InputBeanInstantiationEmitter`, but the R238 `@service` `ValueShape` path
>   (`ServiceMethodCallWalker.fieldBindingShape`) re-projects each `InputBean`
>   `FieldBinding` leaf and silently downgraded the unknown `NodeIdDecodeRecord`
>   to `Direct`. It now carries the leaf through unchanged so the `create<Bean>`
>   helper still routes through `decode<Record>`.

### Model: a dedicated leaf variant, not a reuse of `NodeIdDecodeKeys`

Add a new `CallSiteExtraction` permit carried at the `FieldBinding` leaf slot:

```java
record NodeIdDecodeRecord(HelperRef.Decode decode, ClassName recordType, boolean nonNull)
    implements CallSiteExtraction {}
```

Rationale (principles-architect, finding 1): the existing
`NodeIdDecodeKeys.{ThrowOnMismatch | SkipMismatchedElement}` arms are consumed by
`ArgCallEmitter.buildNodeIdDecodeExtraction` to **decompose** the decoded tuple
into scalar column values (`.value1()` / `::valuesRow`) for a predicate/SET body.
This new consumer does the opposite ; it **rebuilds a `TableRecord`** by copying
the decoded key values straight into the target record's key fields
(`record.from(values, keyFields)`, see "Materialization"). Same NodeType decode,
opposite downstream shape. Reusing the same arm would force two consumers to fork
differently on the same model field (the "two consumers, same predicate" smell in
"Generation-thinking"); a distinct leaf makes each consumer fork on *identity*.
The `recordType` is also available on `FieldBinding.javaElementTypeName`, but
carrying it on the leaf (rather than re-reading the string in the emitter) is
what pins the "this becomes a record, not columns" certainty in the type.

`NodeIdDecodeRecord` reuses `HelperRef.Decode` unchanged; `nonNull` drives
throw-vs-null and is read off SDL nullability at classify time.

Because `CallSiteExtraction` is a top-level sealed, adding a permit makes every
exhaustive switch over it a compile error until handled ; that is the intended
forcing function. The only legal producer is `InputBeanResolver`; the only legal
consumer is `InputBeanInstantiationEmitter`. Other `CallSiteExtraction` switches
(notably `ArgCallEmitter.buildArgExtraction`) get an explicit unreachable arm
("`NodeIdDecodeRecord` is an input-bean field leaf only") rather than a silent
default.

### Decode-helper resolution: reuse, don't duplicate

Do **not** route through `NodeIdLeafResolver` (architect finding 2): its whole
purpose is the same-table-vs-FK fork against a *containing table*, which the
input-bean field has none of. Equally, do not hand-roll a fresh
typeName→keyColumns→decodeHelper walk ; that is the second site that drifts from
`NodeIdLeafResolver.resolveTargetKeys` when the `@node(keyColumns:)` fallback
changes.

Two pieces resolve from two different sides, and they must not be conflated:

- **Key columns: from the table, in one place.** Resolve `@nodeId(typeName:)` →
  the backing SDL object type → its `@table` name (in scope at the resolver), and
  derive the key columns from that table. To keep this a single site, extract
  `NodeIdLeafResolver.resolveTargetKeys` into a shared `BuildContext` method both
  callers use, rather than copying its `@node(keyColumns:)` fallback logic.
- **Decode-helper suffix: from the author's typeName, never the table.** The
  helper name must be `decode<Sak>` (the author-given `@nodeId(typeName: "Sak")`),
  resolved by reading `GraphitronType.NodeType.decodeMethod()` for the type the
  typeName names (the NodeType-preferred branch, `BuildContext.java:2121-2122`);
  that `HelperRef.Decode` already carries the correct name and key columns. When the `types` map is not yet
  populated at this resolver's pass, fall back to constructing
  `HelperRef.Decode(encoderClass, "decode" + typeName, keyColumns)` from the
  typeName, mirroring the two-branch logic inside `resolveDecodeHelperForTable`
  (`BuildContext.java:2111-2136`) but keyed on the typeName.

**Do not call `resolveDecodeHelperForTable` for this (architect finding 2).** That
method derives the suffix from `findGraphQLTypeForTable(sqlTableName)` (singular,
`BuildContext.java:1992`, `:2118`) and consults its `fallbackTypeNameOrTypeId`
argument *only* on the empty branch; when several object types back one table it
yields `decode<firstTypeForTable>`, silently disagreeing with the author's explicit
typeName. The argument name reads as if it drives the suffix but does not, so
passing the typeName there is a trap. Resolve the suffix from the typeName side as
above. (Hardening that helper's typeName-vs-table direction is tracked as R263; it
is not a prerequisite here, and this item must not lean on it.)

### Loud rejection

In the `buildInputBeanBody` leaf ladder, before `else -> new Direct()`: if the
member's loaded Java type is assignable to `org.jooq.Record`, branch:

- `@nodeId` present and target + key columns resolve (**any arity**, single or
  composite) → `NodeIdDecodeRecord`.
- otherwise (no `@nodeId`; unresolvable target; list-of-record member) →
  `Built.Fail` / `Result.Failed` with a message naming the field, the record
  type, and the remedy ("add `@nodeId(typeName:)`"). This is the same
  typed-rejection contract the resolver already uses
  (`InputBeanResolver.java:65-68, 141-176`); it fails the build.

There is **no composite-key gate.** The v1 single-column-key check
(`keyColumns.size() == 1`) that fell composite members into the rejection arm is
removed; a composite-PK `@nodeId` member resolves its full key-column list and
produces `NodeIdDecodeRecord` exactly as a single-key one does. A "composite-key
record members are not yet supported" message must no longer appear in the
codebase.

### Emitter exhaustiveness

`InputBeanInstantiationEmitter.perFieldValueExpr` (`:129-138`) currently switches
`Direct | EnumValueOf | InputBean | default -> throw IllegalStateException`. The
`default -> throw` is precisely the runtime-failure-masquerading-as-coverage the
"Validator mirrors classifier invariants" principle bans. Replace it with an
explicit arm per leaf, including the new `NodeIdDecodeRecord` arm that emits the
helper call + the per-type `decode<Type>Record` helper. A classifier may produce
the new leaf *iff* the emitter has a real arm for it.

## Tests

### Landed (single-key, v1)

- **Pipeline tier (primary).**
  `generators/NodeIdRecordInputBeanPipelineTest`: an `@service` input
  bean (`TestServiceStub.assignFilm(TestNodeIdRecordBean)`) whose `ID! @nodeId`
  field maps to a `FilmRecord` member emits a `decodeFilmRecord` helper plus a
  `createTestNodeIdRecordBean` helper on `QueryFetchers`; the bean field routes
  through `decodeFilmRecord(raw.get("film"))` with no `(FilmRecord) raw.get(...)`
  cast; the decode helper returns the record type, takes `Object`, and
  materializes via `record.from(NodeIdEncoder.decodeValues("Film", nodeId),
  Tables.FILM.FILM_ID)`, throwing on a type-mismatch (`values == null`).
  TypeSpec-shape / helper-presence assertions. **Landed against the v1
  `fromMap(intoMap())` shape; reworked to `from(values, fields)` per
  "Materialization".**
- **Compilation tier.** `graphitron-sakila-example`:
  `FilmRecordAssignmentInput { film: ID! @nodeId(typeName: "Film") }` +
  `Mutation.assignFilmRecord` → `FilmReviewService.assignFilmRecord(FilmRecordAssignment)`.
  Generated `MutationFetchers.decodeFilmRecord` compiles against the real jOOQ
  catalog (catches the `from(values, fields)`/type mismatch).
- **Execution tier.**
  `GraphQLQueryTest#assignFilmRecord_decodesNodeIdIntoJooqRecordMember`: a
  mutation decodes a `Film` NodeId into a `FilmRecord` member and reads the
  populated `film_id` back, round-tripping against PostgreSQL.

### Required by the rescope (composite key) — not yet landed

- **Convert the composite-rejection test.** The current
  `NodeIdRecordInputBeanPipelineTest` rejection case asserting a composite-key
  member (`FilmActorRecord`, PK `(actor_id, film_id)`) fails with the
  composite-key-punt message must be **removed** and replaced with a *success*
  case: the composite member emits a `decodeFilmActorRecord` helper that
  materializes via `record.from(NodeIdEncoder.decodeValues("FilmActor", nodeId),
  Tables.FILM_ACTOR.ACTOR_ID, Tables.FILM_ACTOR.FILM_ID)`, routed through
  `createTestNodeIdRecordBean` with no cast. The only surviving rejection case is
  the no-`@nodeId` one.
- **Compilation tier (composite).** Add a composite-PK fixture to
  `graphitron-sakila-example`: an `@service` input bean with a member typed as a
  composite-PK `*Record` (`FilmActorRecord`) carrying
  `ID! @nodeId(typeName: "FilmActor")`. The generated `decodeFilmActorRecord`
  must compile against the real jOOQ catalog ; this is the primary guard that the
  two-element `from(values, field1, field2)` lands both key fields with correct
  types.
- **Execution tier (composite).** A mutation decodes a `FilmActor` NodeId into a
  composite `FilmActorRecord` member and reads both `actor_id` and `film_id` back
  populated, round-tripping against PostgreSQL ; proves the positional
  `from(values, fields)` materialization fills every key column, not just the first.

## Affected code

- `InputBeanResolver.java` ; read `@nodeId`, the jOOQ-record branch, the
  `NodeIdDecodeRecord` leaf, the loud rejections.
- `model/CallSiteExtraction.java` ; the `NodeIdDecodeRecord` permit.
- `generators/InputBeanInstantiationEmitter.java` ; the new leaf arm + the
  `decode<Type>Record` helper whose body is the `record.from(decodeValues(...),
  <keyFields>)` shape (see "Materialization"); remove the `default -> throw`.
- The `NodeIdEncoder` class generator (`…rewrite` encoder generator) ; expose the
  raw unpack the helper calls — make `decodeValues` `public`, or emit a public
  `unpack<Type>(String): String[]` wrapper. (This replaces v1's reliance on the
  typed `decode<Type>` + `intoMap`.)
- `generators/ArgCallEmitter.java` (and any other exhaustive
  `CallSiteExtraction` switch the compiler flags) ; explicit unreachable arm.
- `BuildContext.java` ; host a shared `resolveTargetKeys` lifted from
  `NodeIdLeafResolver` (key columns, resolved table-side), and resolve the
  decode-helper suffix from the author's `typeName` per "Decode-helper
  resolution" above. Do **not** call `resolveDecodeHelperForTable` here ; it
  derives the suffix from the table, not the typeName (the trap documented in
  that section).

## Deferred / out of scope

- **Top-level `@service` parameter that *is* a jOOQ record** (the original R195
  framing). Reject loudly for now; the direction (grow an `@service` jOOQ-record
  arm vs. route to the DML `@mutation` path) is tangled with R97
  ("Deprecate `@table` on input") and should not be cemented here.
- **`@field(name:)` / `@table`-on-input translation.** Not needed for the
  reported case (the record member's key columns come from the target table's PK,
  no name inversion). Folds into R97's consumer-derivation direction.
- **List-of-jOOQ-record members.** Reject in v1.
- **Readability of the *existing* inline NodeId emitter.** Tracked separately as
  R260; this item's new helper must emit the readable statement form from day
  one, but it does not refactor `ArgCallEmitter.buildNodeIdDecodeExtraction`.
- Non-jOOQ ORM record types (Hibernate, MyBatis) ; `@service` keeps accepting
  POJOs/records of any origin via the existing path, unchanged.
