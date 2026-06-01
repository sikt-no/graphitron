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
a separate feature here: the `record.fromMap(key.intoMap())` materialization is
arity-agnostic (the decoded `RecordN`'s field names match the target record's key
columns whatever N is), and the decode helper (`decode<Type>` returning
`Record1`/`RecordN`) already exists per NodeType. Punting composite to a future
item would ship a half-feature that throws a build error on a legitimate,
mechanically-supported shape; that is not acceptable.

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
    Record1<Long> key = NodeIdEncoder.decodeSak(nodeId);
    if (key == null) {
        throw GraphqlErrorException.newErrorException()
            .message("Decoded NodeId did not match the expected type for this argument").build();
    }
    SakRecord record = new SakRecord();
    record.fromMap(key.intoMap());
    return record;
}
```

For a non-null SDL field (`ID!`) the helper throws on a `null` decode; for a
nullable field (`ID`) it returns `null`. Whether a *null wire value on an `ID!`
field* should throw vs. defer to Jakarta non-null validation is settled in
implementation against the existing non-null-arg behaviour; the decode-mismatch
throw above is unconditional.

`record.fromMap(key.intoMap())` is the crux that means **no encoder-generator
change is needed** *and* that this generalises to composite keys with no extra
machinery: `NodeIdEncoder.decodeSak(...)` builds its `RecordN` from the literal
`Tables.SAK.<pkCol>` fields (`NodeIdEncoderClassGenerator`,
`newRecord(Tables.SAK.COL, ...)`), so the decoded tuple's field names are
identical to the target record's key fields and the by-name copy lands each key
column in the right slot. The decode returns a key tuple (`Record1<Long>` /
`RecordN`), *not* a `TableRecord`; `fromMap(intoMap())` is what turns it into one.

For a composite-PK NodeType the only thing that changes is the decode return
arity: `NodeIdEncoder.decodeFilmActor(...)` returns `Record2<Integer, Integer>`
whose fields are `Tables.FILM_ACTOR.ACTOR_ID` / `.FILM_ID`, and
`filmActor.fromMap(key.intoMap())` copies both columns by name. The helper body,
the `instanceof String` guard, the throw-vs-null logic, and the call site are all
identical; only the `Record1<Long>` local type in the example above widens to the
NodeType's `RecordN`. Because the materialization is name-keyed it is insensitive
to key-column order, so no positional reasoning is needed.

Java-17 note: the helper matches on `wire instanceof String nodeId` (legal in
17). It does **not** pattern-match the parameterised `RecordN` (that would
require Java 21+); it null-checks the decode result and calls `fromMap`. This
avoids the `(Object) ... instanceof Record1` dance the existing inline emitter
needs.

> **Implementation corrections (landed).** Two claims above were wrong against the
> real jOOQ / graphql-java 25 APIs and were corrected during implementation:
> - **There is no `Record.from(Record)` by-name overload.** jOOQ's `from(Object)`
>   POJO-reflects its source; a `RecordN` exposes no `getSakId()` bean property, so
>   `record.from(key)` copies nothing and leaves the key column `null`. The
>   working by-name copy is `record.fromMap(key.intoMap())` (`intoMap()` keys by
>   field name; `fromMap` matches target fields by name). Verified at the
>   execution tier (`GraphQLQueryTest#assignFilmRecord_decodesNodeIdIntoJooqRecordMember`).
> - **`new GraphqlErrorException(String)` does not compile** (only a protected
>   builder constructor exists); the throw uses
>   `GraphqlErrorException.newErrorException().message(..).build()`. The existing
>   inline `ThrowOnMismatch` arms in `ArgCallEmitter` /
>   `CompositeDecodeHelperRegistry` emit the same broken `new GraphqlErrorException($S)`
>   but no compilation-tier fixture reaches them, so the defect is latent there
>   (filed as a separate Backlog item).
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
This new consumer does the opposite ; it keeps the tuple whole and **rebuilds a
`TableRecord`** via `fromMap(intoMap())`. Same decode helper, opposite downstream
projection. Reusing the same arm would force two consumers to fork differently on
the same model field (the "two consumers, same predicate" smell in
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
  cast; the decode helper returns the record type, takes `Object`, decodes via
  `decodeFilm` and rebuilds with `fromMap(key.intoMap())`, throwing on a
  type-mismatch. TypeSpec-shape / helper-presence assertions.
- **Compilation tier.** `graphitron-sakila-example`:
  `FilmRecordAssignmentInput { film: ID! @nodeId(typeName: "Film") }` +
  `Mutation.assignFilmRecord` → `FilmReviewService.assignFilmRecord(FilmRecordAssignment)`.
  Generated `MutationFetchers.decodeFilmRecord` compiles against the real jOOQ
  catalog (catches the `from`/`fromMap`/type mismatch).
- **Execution tier.**
  `GraphQLQueryTest#assignFilmRecord_decodesNodeIdIntoJooqRecordMember`: a
  mutation decodes a `Film` NodeId into a `FilmRecord` member and reads the
  populated `film_id` back, round-tripping against PostgreSQL.

### Required by the rescope (composite key) — not yet landed

- **Convert the composite-rejection test.** The current
  `NodeIdRecordInputBeanPipelineTest` rejection case asserting a composite-key
  member (`FilmActorRecord`, PK `(actor_id, film_id)`) fails with the
  composite-key-punt message must be **removed** and replaced with a *success*
  case: the composite member emits a `decodeFilmActorRecord` helper that decodes
  via `decodeFilmActor` (returning `Record2<Integer, Integer>`) and rebuilds with
  `fromMap(key.intoMap())`, routed through `createTestNodeIdRecordBean` with no
  cast. The only surviving rejection case is the no-`@nodeId` one.
- **Compilation tier (composite).** Add a composite-PK fixture to
  `graphitron-sakila-example`: an `@service` input bean with a member typed as a
  composite-PK `*Record` (`FilmActorRecord`) carrying
  `ID! @nodeId(typeName: "FilmActor")`. The generated `decodeFilmActorRecord`
  must compile against the real jOOQ catalog ; this is the primary guard that the
  two-column `fromMap(intoMap())` lands both key fields with correct types.
- **Execution tier (composite).** A mutation decodes a `FilmActor` NodeId into a
  composite `FilmActorRecord` member and reads both `actor_id` and `film_id` back
  populated, round-tripping against PostgreSQL ; proves name-keyed materialization
  fills every key column, not just the first.

## Affected code

- `InputBeanResolver.java` ; read `@nodeId`, the jOOQ-record branch, the
  `NodeIdDecodeRecord` leaf, the loud rejections.
- `model/CallSiteExtraction.java` ; the `NodeIdDecodeRecord` permit.
- `generators/InputBeanInstantiationEmitter.java` ; the new leaf arm + per-type
  `decode<Type>Record` helper; remove the `default -> throw`.
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
