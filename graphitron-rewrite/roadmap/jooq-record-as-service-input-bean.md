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
a separate feature here: the materialization (see "Materialization" below) is
arity-agnostic (one typed `set` per key column, single or composite), and the raw
decode unpack (`NodeIdEncoder.decodeValues`) already returns all key values per
NodeType.
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

> **Rescope (In Review → Spec, R260-adjacent review).** v1 (single-key,
> `fromMap(intoMap())`) reached In Review, then was reopened on two findings: it
> punted composite keys, and it materialized records the long way. v1's classifier
> plumbing, the `NodeIdDecodeRecord` leaf, the exhaustive emitter switch, the
> shared `resolveTargetKeys`, and the `ServiceMethodCallWalker` leaf carry-through
> are **landed and stay**. The next `Spec → Ready` sign-off must be a session other
> than the one that landed this rescope.
>
> **Delta from landed v1 (the work this revision adds):**
> 1. **Composite keys in.** Drop the `keyColumns.size() == 1` gate in
>    `InputBeanResolver.buildJooqRecordLeaf`; remove the "composite-key... not yet
>    supported" message; update that method's javadoc (do not leave it claiming
>    composite is deferred). See "Loud rejection".
> 2. **Materialization: copy decoded values into the target record.** Rework the
>    helper body off `decode<Type>` + `fromMap(intoMap())` to `decodeValues(typeId,
>    nodeId)` + typed per-column `record.set(field, convert(values[i]))` (or
>    `record.from(values, keyFields)`), no throwaway `RecordN`. Update the
>    contradicting helper javadoc. See "Materialization".
> 3. **Leaf component set.** Change `NodeIdDecodeRecord` to carry
>    `(encoderClass, typeId, keyColumns, recordType, nonNull)` (drop the
>    `HelperRef.Decode` whose `decode<Type>` method name the new body never calls).
>    See "Model".
> 4. **Expose `decodeValues`** on the generated `NodeIdEncoder` (public, or a public
>    `unpack<Type>` wrapper). See "Materialization".
> 5. **Keep per-type concrete `decode<Type>Record` helpers** (deduped by record
>    type), emitted by **one reusable emitter** generalized to all arities, rather
>    than a generic generated helper. See "Output: per-type concrete helpers".
> 6. **Tests:** convert the composite-rejection pipeline test to a composite-success
>    test; add composite compile + execute coverage. See "Tests".

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
    record.set(Tables.SAK.SAK_ID, Tables.SAK.SAK_ID.getDataType().convert(values[0]));
    return record;
}
```

(Concrete, per-type, emitted by one reusable emitter; see "Output" below. For a
composite key the body is one typed `set` per key column.)

For a non-null SDL field (`ID!`) the helper throws on a `null` decode; for a
nullable field (`ID`) it returns `null`. Whether a *null wire value on an `ID!`
field* should throw vs. defer to Jakarta non-null validation is settled in
implementation against the existing non-null-arg behaviour; the decode-mismatch
throw above is unconditional.

### Materialization: copy decoded values straight into the target record

**This supersedes v1's `record.fromMap(key.intoMap())`.** Researching how the
legacy generator solved the same problem (`graphitron-common`
`NodeIdStrategy.setFields` / `nodeIdToTableRecord`) surfaced the core insight:
decode the wire id to raw values and copy them **straight into the target
record's key columns**, with no intermediate typed `RecordN`.

v1 instead went `base64 → NodeIdEncoder.decodeSak(...)` (a *throwaway* typed
`RecordN`, built via `newRecord(cols)` + per-column `set(convert(...))`) →
`key.intoMap()` (a name-keyed `Map`) → `target.fromMap(map)` (re-match by name).
Three intermediate representations and a whole throwaway record materialized only
to be copied out again, the "build a record to build a record" smell.

The generated `NodeIdEncoder` already exposes the raw unpack every `decode<Type>`
calls first (`static String[] decodeValues(String expectedTypeId, String base64Id)`,
type-id-checked, returns `null` on mismatch). The decode-record helper calls it
directly and copies into the target record. Two body forms, both arity-agnostic
and both avoiding the throwaway `RecordN`:

- **Typed per-column `set` (recommended).**
  `record.set(Tables.SAK.SAK_ID, Tables.SAK.SAK_ID.getDataType().convert(values[i]))`,
  one statement per key column. `Record.set(Field<T>, T)` is **compile-checked**
  (the field's `T` must match `convert`'s return), so this leverages the type
  system, and it is exactly what `NodeIdEncoder.decode<Type>` already emits to
  populate its `RecordN`, applied to the target record instead of a throwaway one.
  For a composite key it is N explicit typed assignments, which read like
  hand-written code.
- **`record.from(values, keyFields...)` (concise alternative).** jOOQ's
  positional `from(Object source, Field<?>... fields)` copies the `String[]` into
  the named fields in one call, converting each via `DataType`. This is what
  legacy emits (`NodeIdStrategy.setFields`). One line regardless of arity, but
  **runtime-loose**: `from(Object, Field<?>...)` is not compile-checked against the
  value array. Prefer the typed `set` form unless the one-liner is judged worth
  the lost checking.

Either way the output is a concrete per-type helper from one reusable emitter (see
"Output: per-type concrete helpers"), not a generic generated helper.

- **Encoder access.** `decodeValues` is package-private and the helper lands in a
  different package (`…generated.fetchers` vs `…generated.util`). Expose it:
  make `decodeValues` `public`, or generate a public `unpack<Type>(String): String[]`
  wrapper. (Implementation choice; prefer the smaller surface.)
- **No encoder-generator change for the decode itself.** The encoder already emits
  `decodeValues`; only its visibility (or a thin public wrapper) changes.

Java-17 note: the helper matches on `wire instanceof String nodeId` (legal in
17). It does **not** pattern-match a parameterised `RecordN` (Java 21+); it
null-checks the `String[]` and sets the target record's columns. This sidesteps
both the `(Object) ... instanceof Record1` dance the inline emitter needs *and*
the throwaway-`RecordN` allocation.

> **v1 implementation note (superseded).** v1 landed `record.fromMap(key.intoMap())`
> after discovering that `record.from(key)` (POJO reflection) copies nothing from a
> `RecordN` (no `getSakId()` bean property). `fromMap(intoMap())` works but is the
> wasteful three-hop path above; the copy-into-target mechanism this section
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

Add a new `CallSiteExtraction` permit carried at the `FieldBinding` leaf slot,
carrying exactly what the `from(values, fields)` materialization needs and nothing
more:

```java
record NodeIdDecodeRecord(ClassName encoderClass, String typeId,
                          List<ColumnRef> keyColumns, ClassName recordType,
                          boolean nonNull)
    implements CallSiteExtraction {}
```

> **Component-set note (was `HelperRef.Decode`).** v1 carried
> `NodeIdDecodeRecord(HelperRef.Decode decode, ClassName recordType, boolean nonNull)`.
> `HelperRef.Decode` bundles `(encoderClass, decode<Type> methodName, keyColumns)`.
> The adopted materialization calls `NodeIdEncoder.decodeValues(typeId, nodeId)`
> + `record.from(values, keyFields)` and **never calls `decode<Type>`**, so the
> `decode<Type>` method name was a component the emitter had to carry and ignore
> (against "narrow component types"). The leaf now carries `encoderClass` (to call
> `decodeValues`), `typeId` (the `$S` first arg to `decodeValues`), `keyColumns`
> (the `from(...)` field varargs), `recordType`, and `nonNull`. If a small
> purpose-built `HelperRef.DecodeValues(encoderClass, typeId, keyColumns)` sibling
> reads better than three loose components, use that; the point is no `decode<Type>`
> method name on the leaf.

Rationale for a distinct permit (principles-architect): the fork is on
**downstream emission identity**, not on the decode itself. `NodeIdDecodeKeys`
consumers (`ArgCallEmitter.buildNodeIdDecodeExtraction`, R260's
`CompositeDecodeHelperRegistry`) emit a *predicate / SET body* and need the typed
`RecordN` projection (`.value1()` / `::valuesRow`). This consumer emits a
*materialized `TableRecord`* and needs only `(typeId, keyColumns, recordType)` ;
it does not touch the typed `RecordN` at all. Same NodeId wire decode at the root,
two structurally different emissions. Reusing one arm would force two consumers to
fork on the same model field (the "two consumers, same predicate" smell in
"Generation-thinking"); a distinct leaf makes each consumer fork on *identity*.
Carrying `recordType` on the leaf (rather than re-reading
`FieldBinding.javaElementTypeName` in the emitter) pins the "this becomes a
record, not columns" certainty in the type.

`nonNull` drives throw-vs-null and is read off SDL nullability at classify time.

Because `CallSiteExtraction` is a top-level sealed, adding a permit makes every
exhaustive switch over it a compile error until handled ; that is the intended
forcing function. The only legal producer is `InputBeanResolver`; the only legal
consumer is `InputBeanInstantiationEmitter`. Other `CallSiteExtraction` switches
(notably `ArgCallEmitter.buildArgExtraction`) get an explicit unreachable arm
("`NodeIdDecodeRecord` is an input-bean field leaf only") rather than a silent
default. (This arm is landed.)

### Key-column / typeId resolution: one shared site

The leaf needs `typeId` + `keyColumns`, both resolved from the author's
`@nodeId(typeName:)`:

- Resolve `@nodeId(typeName:)` → the backing SDL object type → its `@table` name
  (in scope at the resolver) → the key columns. Use the shared
  `BuildContext.resolveTargetKeys` (already lifted from `NodeIdLeafResolver` and
  shared with it) rather than copying its `@node(keyColumns:)` fallback ; that
  keeps key-column derivation a single site.
- `typeId` is the NodeType's configured type id for `typeName` (the same value
  `decodeValues`'s first arg checks against). `encoderClass` is the generated
  `NodeIdEncoder`.

Do **not** route through `NodeIdLeafResolver` itself (its same-table-vs-FK fork is
against a *containing table* the input-bean field has none of), and do **not**
hand-roll a parallel `typeName → keyColumns` walk.

> **No `decode<Type>` suffix to resolve.** v1 spent a subsection resolving the
> `decode<Sak>` *method-name suffix* from the author's typeName (and warning off
> `BuildContext.resolveDecodeHelperForTable`, which derives the suffix from the
> table and silently disagrees when several object types back one table). The
> adopted mechanism calls `decodeValues(typeId, ...)`, not `decode<Type>`, so there
> is **no suffix to resolve** and the `resolveDecodeHelperForTable` trap does not
> apply here. The suffix-resolution concern is dropped from this item. (Hardening
> `resolveDecodeHelperForTable`'s typeName-vs-table direction remains tracked as
> R263, independent of this item.)

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

### Emitter exhaustiveness (landed)

`InputBeanInstantiationEmitter.perFieldValueExpr` (`:129`) switches over every
`CallSiteExtraction` permit with explicit arms and no `default -> throw` (the
runtime-failure-masquerading-as-coverage the "Validator mirrors classifier
invariants" principle bans). The `NodeIdDecodeRecord` arm emits the helper call;
a new permit fails *that* switch to compile. This is landed; the rework only
changes the **body** the `NodeIdDecodeRecord` arm emits (see "Materialization").

### Output: per-type concrete helpers, from one reusable emitter

**Generated output is per-type and concrete; the *emitter* is single and
reusable.** These are two different axes and the right answer differs on each:

- *Generated output:* one concrete `decode<Type>Record(Object) -> <Type>Record`
  per record type (deduped by record type, so N fields of the same type share one
  helper, as v1 already does). **Not** a single generic
  `<R extends TableRecord<R>> R decodeNodeIdRecord(Object, String typeId, R fresh,
  TableField<R,?>... keyFields)`.
- *Generator code:* a single reusable emitter method (the existing
  `InputBeanInstantiationEmitter` record-decode-helper builder, generalized to all
  arities) parameterized by `(recordType, typeId, keyColumns, nonNull)`, emitting
  the concrete helper. One generator-side definition, no duplicated emitter logic.

Why concrete output rather than a generic generated helper, even though the body
shape is now uniform across types:

- **DRY belongs in the emitter, not the output.** The usual argument for a generic
  helper, "centralize so copies can't drift", does not transfer to generated code:
  the generator *is* the single definition, so every emitted `decode<Type>Record`
  is identical by construction and there is no drift to prevent. Reusing the
  *emitter* already captures the no-duplication win; pushing it into the output
  buys nothing and costs the points below.
- **Concrete types, not generics + varargs.** The concrete method has
  `-> SakRecord` and `record.set(Tables.SAK.SAK_ID, …)` baked in. The generic form
  needs `<R extends TableRecord<R>>` + `TableField<R,?>...` varargs (looser typing,
  per-call array allocation) to express what the generator already knows
  concretely, and threads `typeId` / fields / record-type through as *runtime*
  arguments rather than emitting them as literals.
- **Readability and stack frames.** A frame reading `decodeSakRecord` beats
  `decodeNodeIdRecord(…, "Sak", …)` (per "Generated code is read and debugged"),
  and it matches the codebase's own conventions: `NodeIdEncoder` emits per-type
  `decodeFilm` / `decodeFilmActor`, and R260's `CompositeDecodeHelperRegistry`
  emits per-type `decodeFilmKeys` / `decodeFilmActorRow`. A generic helper here
  would be the lone exception.
- **Compiler-leverage in the body.** A concrete method lets the body be
  compile-checked via typed `record.set(Field<T>, T)` (see "Materialization"); the
  generic helper's `from(values, fields)` is runtime-loose.

Legacy used a single *runtime* helper (`NodeIdStrategy.nodeIdToTableRecord`)
because it had a runtime library; the rewrite generates statics, so the
equivalent consolidation lives in the emitter, and the output stays concrete.

## Tests

> The pipeline assertions stay per-type (`decodeFilmRecord` etc.) ; the output is
> per-type concrete helpers (see "Output: per-type concrete helpers"). Only the
> helper *body* the assertions inspect changes: from the v1
> `decodeFilm(...)` + `fromMap(intoMap())` shape to `decodeValues("Film", …)` +
> typed per-column `set` (no throwaway `RecordN`). The no-cast routing and
> round-trip behaviour they pin are unchanged.

### Landed (single-key, v1)

- **Pipeline tier (primary).**
  `generators/NodeIdRecordInputBeanPipelineTest`: an `@service` input
  bean (`TestServiceStub.assignFilm(TestNodeIdRecordBean)`) whose `ID! @nodeId`
  field maps to a `FilmRecord` member emits a `decodeFilmRecord` helper plus a
  `createTestNodeIdRecordBean` helper on `QueryFetchers`; the bean field routes
  through `decodeFilmRecord(raw.get("film"))` with no `(FilmRecord) raw.get(...)`
  cast; the decode helper returns the record type, takes `Object`, and
  materializes via `NodeIdEncoder.decodeValues("Film", nodeId)` + typed
  `record.set(Tables.FILM.FILM_ID, …convert(values[0]))`, throwing on a
  type-mismatch (`values == null`). TypeSpec-shape / helper-presence assertions.
  **Landed against the v1 `decode<Type>` + `fromMap(intoMap())` shape; reworked to
  the copy-into-target body per "Materialization".**
- **Compilation tier.** `graphitron-sakila-example`:
  `FilmRecordAssignmentInput { film: ID! @nodeId(typeName: "Film") }` +
  `Mutation.assignFilmRecord` → `FilmReviewService.assignFilmRecord(FilmRecordAssignment)`.
  Generated `MutationFetchers.decodeFilmRecord` compiles against the real jOOQ
  catalog (catches a value/column type mismatch).
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
  materializes via `NodeIdEncoder.decodeValues("FilmActor", nodeId)` + one typed
  `record.set(...)` per key column (`ACTOR_ID`, `FILM_ID`), routed through
  `createTestNodeIdRecordBean` with no cast. The only surviving rejection case is
  the no-`@nodeId` one.
- **Compilation tier (composite).** Add a composite-PK fixture to
  `graphitron-sakila-example`: an `@service` input bean with a member typed as a
  composite-PK `*Record` (`FilmActorRecord`) carrying
  `ID! @nodeId(typeName: "FilmActor")`. The generated `decodeFilmActorRecord`
  must compile against the real jOOQ catalog ; this is the primary guard that both
  typed `set` assignments match their key columns.
- **Execution tier (composite).** A mutation decodes a `FilmActor` NodeId into a
  composite `FilmActorRecord` member and reads both `actor_id` and `film_id` back
  populated, round-tripping against PostgreSQL ; proves the per-column `set`
  materialization fills every key column, not just the first.

## Affected code

This section lists the **delta** the rework touches; symbols not listed (the leaf
permit's existence, the exhaustive switch, the `ArgCallEmitter` unreachable arm,
the `ServiceMethodCallWalker` carry-through, `resolveTargetKeys`) are landed.

- `InputBeanResolver.java` (`buildJooqRecordLeaf`) ; drop the
  `keyColumns.size() == 1` gate and the composite-punt message; populate the
  reshaped `NodeIdDecodeRecord` leaf (`encoderClass, typeId, keyColumns,
  recordType, nonNull`); update the method javadoc that currently says composite
  is deferred.
- `model/CallSiteExtraction.java` ; reshape the `NodeIdDecodeRecord` permit's
  components (drop `HelperRef.Decode`) per "Model".
- `generators/InputBeanInstantiationEmitter.java` ; this *is* the one reusable
  emitter — generalize the existing per-type record-decode-helper builder to all
  arities and rework its emitted body from `decode<Type>` + `fromMap(intoMap())` to
  `decodeValues(typeId, nodeId)` + typed per-column `record.set(field,
  convert(values[i]))` (no throwaway `RecordN`). Output stays per-type concrete
  `decode<Type>Record` helpers, deduped by record type. Update the helper javadoc
  that claims "no encoder-generator change is needed". The switch is already
  exhaustive (no `default -> throw` to remove).
- The `NodeIdEncoder` class generator (`util/NodeIdEncoderClassGenerator.java`) ;
  expose the raw unpack the helper calls — make `decodeValues` `public`, or emit a
  public `unpack<Type>(String): String[]` wrapper. (Replaces v1's reliance on the
  typed `decode<Type>` + `intoMap`.)
- `BuildContext.java` ; reuse the already-shared `resolveTargetKeys` for
  `typeId` + key columns. There is **no** `decode<Type>` suffix to resolve and no
  call to `resolveDecodeHelperForTable` from this path (see "Key-column / typeId
  resolution").

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
