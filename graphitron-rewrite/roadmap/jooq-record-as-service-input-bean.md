---
id: R195
title: Decode @nodeId into jOOQ-record-typed @service input-bean fields
status: Ready
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

**All record-member shapes are in scope: single-column and composite key, scalar
and list-valued.** The driving case (`Sak`, `Bruker`) happens to be a single-PK
scalar member, but neither dimension is a separate feature:

- *Key arity.* A `@nodeId` member backed by a composite-PK NodeType
  (`FilmActorRecord`, PK `(actor_id, film_id)`) materializes exactly like a
  single-key one ; the materialization is arity-agnostic (one typed `set` per key
  column) and `NodeIdEncoder.decodeValues` already returns all key values.
- *List-ness.* A list-valued member (`List<SakRecord>` from `[ID!] @nodeId`)
  wraps the same per-element materialization in a stream/collect, mirroring
  R260's `decodeFilmKeys`/`decodeFilmActorRows` list helpers one level up.

Both compose orthogonally (the `{scalar,list} × {single,composite}` matrix R260
already emits for keys), so the reusable emitter handles them with one per-column
`set` loop and one optional stream wrapper. Punting either to a future item would
ship a half-feature that throws a build error on a legitimate,
mechanically-supported shape; that is not acceptable.

Two deliverables, both required:

1. **Decode happy path.** Read `@nodeId(typeName:)` on a jOOQ-record-typed bean
   field, emit a NodeId-decode-and-materialize instead of a cast. Works for any
   key arity and for scalar or list-valued members.
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
> 1. **Composite keys + list members in.** Drop both shape gates in
>    `InputBeanResolver.buildJooqRecordLeaf`: the `keyColumns.size() == 1` check
>    (`:382-387`) and the `listShape` check (`:360-364`); remove both "not yet
>    supported" messages; update that method's javadoc (do not leave it claiming
>    composite or list is deferred). The remaining rejections are malformed-directive
>    only. See "Loud rejection".
> 2. **Materialization: copy decoded values into the target record.** Rework the
>    helper body off `decode<Type>` + `fromMap(intoMap())` to `decodeValues(typeId,
>    nodeId)` + typed per-column `set(field, convert(values[i]))`, no throwaway
>    `RecordN`. Update the contradicting helper javadoc. See "Materialization".
> 3. **Leaf component set.** Change `NodeIdDecodeRecord` to carry
>    `(encoderClass, typeId, keyColumns, recordType, nonNull)` (drop the
>    `HelperRef.Decode` whose `decode<Type>` method name the new body never calls).
>    See "Model".
> 4. **Expose `decodeValues`** on the generated `NodeIdEncoder` (public, or a public
>    `unpack<Type>` wrapper). See "Materialization".
> 5. **Keep per-type concrete `decode<Type>Record` helpers** (deduped by record
>    type), emitted by **one reusable emitter** generalized to all arities, rather
>    than a generic generated helper. The same emitter emits a `decode<Type>RecordList`
>    variant for list members. See "Output: per-type concrete helpers".
> 6. **Tests:** convert the composite-rejection *and* list-rejection pipeline tests
>    to success tests; add composite and list-of-record compile + execute coverage
>    (including the `List<FilmActorRecord>` corner that exercises both dimensions).
>    See "Tests".

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
    SakRecord decoded = new SakRecord();
    decoded.set(Tables.SAK.SAK_ID, Tables.SAK.SAK_ID.getDataType().convert(values[0]));
    return decoded;
}
```

(Concrete, per-type, emitted by one reusable emitter; see "Output" below. For a
composite key the body is one typed `set` per key column. The local is named
`decoded`, not `record` — `record` is a context-sensitive keyword and reads poorly
in the exact code a consumer breakpoints.)

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
directly and copies into the target record with **typed per-column `set`**:

```java
decoded.set(Tables.SAK.SAK_ID, Tables.SAK.SAK_ID.getDataType().convert(values[i]));
```

one statement per key column. `Record.set(Field<T>, T)` is **compile-checked** (the
field's `T` must match `convert`'s return), so a column/value type mismatch is a
`graphitron-sakila-example` compile failure (the "Compilation against real jOOQ is
a test tier" backstop) rather than a DSL-runtime error — the safer expression of
the contract under "Classifier guarantees shape emitter assumptions". It is exactly
what `NodeIdEncoder.decode<Type>` already emits to populate its `RecordN`, applied
to the target record instead of a throwaway one. For a composite key it is N
explicit typed assignments, which read like hand-written code. No throwaway
`RecordN`.

> **Rejected alternative: `record.from(values, keyFields...)`.** jOOQ's positional
> `from(Object source, Field<?>... fields)` (what legacy `NodeIdStrategy.setFields`
> emits) is one line regardless of arity, but **runtime-loose**: `from(Object,
> Field<?>...)` is not compile-checked against the value array, so it forfeits the
> compile-tier backstop above. The pipeline tests pin the typed `set` form; this
> footnote records why `from` was not chosen.

The output is a concrete per-type helper from one reusable emitter (see "Output:
per-type concrete helpers"), not a generic generated helper.

**List-valued members** (`List<SakRecord>` from `[ID!] @nodeId(typeName: "Sak")`)
wrap the same per-element materialization in a stream, mirroring R260's
`decodeFilmKeys`/`decodeFilmActorRows` list helpers one level up:

```java
private static List<SakRecord> decodeSakRecordList(Object wire) {
    if (!(wire instanceof List<?> nodeIds)) {
        return null;
    }
    List<SakRecord> records = new ArrayList<>(nodeIds.size());
    for (Object element : nodeIds) {
        records.add(decodeSakRecord(element));   // same per-element helper; throws on a wrong-type element
    }
    return records;
}
```

A present-but-wrong-type element is a wrong input (the authored contract), so it
throws (the singular helper already throws on mismatch) rather than silently
dropping ; this differs from the *filter* path's `SkipMismatchedElement`, because
an input-bean member is materialized input, not a query predicate. The reusable
emitter emits the scalar helper always and the `…List` variant when
`FieldBinding.listShape` ; both share the per-element body. List-ness is carried on
`FieldBinding`, not duplicated on the leaf.

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
> + typed per-column `set` and **never calls `decode<Type>`**, so the `decode<Type>`
> method name was a component the emitter had to carry and ignore (against "narrow
> component types"). The leaf now carries the flat five components above —
> `encoderClass` (to call `decodeValues`), `typeId` (the `$S` first arg to
> `decodeValues`), `keyColumns` (the per-column `set` loop), `recordType`, and
> `nonNull`. **Decision: the flat form, not a `HelperRef.DecodeValues` sub-record** —
> these components have no other consumer to share a grouping with (the leaf is the
> sole reader), so a new `HelperRef` sibling would be a type for one call site. The
> only invariant that matters is no `decode<Type>` method name on the leaf.

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
member's (element) Java type is assignable to `org.jooq.Record`, branch:

- `@nodeId` present and target + key columns resolve → `NodeIdDecodeRecord`. This
  holds for **every shape**: single-key or composite-key, scalar member or
  list-valued member. Arity is the key-column count; list-ness is carried on the
  enclosing `FieldBinding.listShape` and drives the scalar-vs-list emitter variant.
- otherwise → `Built.Fail` / `Result.Failed`. After this item the **only**
  jOOQ-record-member rejections are *malformed directive* cases, not *unsupported
  shape* cases:
  - no `@nodeId` on the record member (remedy: add `@nodeId(typeName:)`);
  - `@nodeId` without `typeName:` (the record type alone does not name the NodeType);
  - `typeName:` resolves to no known NodeType.

  This is the same typed-rejection contract the resolver already uses
  (`InputBeanResolver.java:65-68, 141-176`); it fails the build.

**No shape gates.** Both v1 shape gates are removed:

- the `keyColumns.size() == 1` check that rejected composite-PK members
  (`InputBeanResolver.java:382-387`), and
- the `listShape` check that rejected list-valued members
  (`InputBeanResolver.java:360-364`).

Neither "composite-key record members are not yet supported" nor
"list-of-jOOQ-record members are not yet supported" may appear in the codebase
after this item. A composite member resolves its full key-column list; a list
member produces the same leaf and emits through the list variant of the helper
(stream the wire `List<String>`, materialize one record per element, collect).

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
  helper, as v1's `collectRecordDecoders` already does), plus a
  `decode<Type>RecordList(Object) -> List<<Type>Record>` variant when a member of
  that type is list-valued. The scalar and list variants dedup independently by
  `(recordType, list)`, so a type used both scalar- and list-valued emits both
  helpers, each once. **Not** a single generic `<R extends TableRecord<R>> R
  decodeNodeIdRecord(Object, String typeId, R fresh, TableField<R,?>... keyFields)`.
- *Generator code:* a single reusable emitter method (the existing
  `InputBeanInstantiationEmitter` record-decode-helper builder, generalized to all
  arities) parameterized by `(recordType, typeId, keyColumns, nonNull)`, emitting
  the concrete scalar helper and, for list members, the `…List` stream variant that
  delegates to it. One generator-side definition, no duplicated emitter logic.

Why concrete output rather than a generic generated helper, even though the body
shape is now uniform across types:

- **DRY belongs in the emitter, not the output.** The usual argument for a generic
  helper, "centralize so copies can't drift", does not transfer to generated code:
  one emitter is the single source, so every emitted `decode<Type>Record` is
  identical by construction and there is no drift to prevent (the N identical copies
  across types, and the same helper re-emitted in two `*Fetchers` classes, are
  duplication the principles explicitly accept for generated output, not drift).
  Reusing the *emitter* already captures the no-duplication win; pushing it into the
  output
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

### Required by the rescope (composite key + list members) — not yet landed

- **Convert the shape-rejection tests to success tests.** The current
  `NodeIdRecordInputBeanPipelineTest` rejection cases — a composite-key member
  (`FilmActorRecord`, PK `(actor_id, film_id)`) and a list-valued member
  (`List<FilmRecord>`) failing with "not yet supported" — must be **removed** and
  replaced with *success* cases: the composite member emits a `decodeFilmActorRecord`
  helper (one typed `record.set(...)` per key column); the list member emits a
  `decodeFilmRecordList` helper (stream/collect over `decodeFilmRecord`); both route
  through `createTestNodeIdRecordBean` with no cast. The only surviving rejections
  are the malformed-directive cases (no `@nodeId`, missing `typeName`, unresolvable
  NodeType).
- **Compilation tier (composite + list).** Add fixtures to
  `graphitron-sakila-example`: a composite-PK member (`FilmActorRecord` /
  `ID! @nodeId(typeName: "FilmActor")`), a list member (`List<FilmRecord>` /
  `[ID!] @nodeId(typeName: "Film")`), and the corner that exercises both
  (`List<FilmActorRecord>` / `[ID!] @nodeId(typeName: "FilmActor")`). Each generated
  helper compiles against the real jOOQ catalog — the primary guard that the typed
  `set` assignments and the `List<…>` return type line up.
- **Execution tier (composite + list).** A mutation decodes a `FilmActor` NodeId
  into a composite member and reads both `actor_id` and `film_id` back; another
  decodes a list of `Film` NodeIds into a `List<FilmRecord>` and reads each
  `film_id` back, round-tripping against PostgreSQL. Proves per-column `set` fills
  every key column and the list variant materializes one record per element.

## Affected code

This section lists the **delta** the rework touches; symbols not listed (the leaf
permit's existence, the exhaustive switch, the `ArgCallEmitter` unreachable arm,
the `ServiceMethodCallWalker` carry-through, `resolveTargetKeys`) are landed.

- `InputBeanResolver.java` (`buildJooqRecordLeaf`) ; drop **both** shape gates —
  the `keyColumns.size() == 1` gate (`:382-387`) and the `listShape` gate
  (`:360-364`) — and their "not yet supported" messages; populate the reshaped
  `NodeIdDecodeRecord` leaf (`encoderClass, typeId, keyColumns, recordType,
  nonNull`) for every shape; update the method javadoc that currently says
  composite and list are deferred.
- `model/CallSiteExtraction.java` ; reshape the `NodeIdDecodeRecord` permit's
  components (drop `HelperRef.Decode`) per "Model".
- `generators/InputBeanInstantiationEmitter.java` ; this *is* the one reusable
  emitter — generalize the existing per-type record-decode-helper builder to all
  arities and rework its emitted body from `decode<Type>` + `fromMap(intoMap())` to
  `decodeValues(typeId, nodeId)` + typed per-column `record.set(field,
  convert(values[i]))` (no throwaway `RecordN`). Emit the `decode<Type>RecordList`
  stream variant when the `FieldBinding` is list-shaped. Output stays per-type
  concrete helpers, deduped by record type. Update the helper javadoc that claims
  "no encoder-generator change is needed". The switch is already exhaustive (no
  `default -> throw` to remove).
- The `NodeIdEncoder` class generator (`util/NodeIdEncoderClassGenerator.java`) ;
  expose the raw unpack the helper calls — make `decodeValues` `public`, or emit a
  public `unpack<Type>(String): String[]` wrapper. (Replaces v1's reliance on the
  typed `decode<Type>` + `intoMap`.)
- `BuildContext.java` ; resolve `typeId` + key columns from the already-shared
  `resolveTargetKeys` (which returns `TargetKeys(typeId, keyColumns, error)`).
  **Retire `resolveNodeIdRecordDecode`** (or reshape its `NodeIdRecordDecode.Resolved`
  result): today it returns `Resolved(HelperRef.Decode decode, List<ColumnRef>
  keyColumns)` and synthesizes a `decode<typeName>` method name (`:2228-2236`) that,
  once the leaf drops `HelperRef.Decode`, **nobody consumes** — the same
  carry-a-dead-component smell, pushed one layer up into the resolver result. Either
  call `resolveTargetKeys` directly from `buildJooqRecordLeaf` (preferred: single
  resolution site, matches "Key-column / typeId resolution"), or reshape
  `NodeIdRecordDecode.Resolved` to carry `(typeId, keyColumns)` and stop building the
  dead `HelperRef.Decode`. There is **no** `decode<Type>` suffix to resolve and no
  call to `resolveDecodeHelperForTable` from this path.

## Deferred / out of scope

- **Top-level `@service` parameter that *is* a jOOQ record** (the original R195
  framing). Reject loudly for now; the direction (grow an `@service` jOOQ-record
  arm vs. route to the DML `@mutation` path) is tangled with R97
  ("Deprecate `@table` on input") and should not be cemented here.
- **`@field(name:)` / `@table`-on-input translation.** Not needed for the
  reported case (the record member's key columns come from the target table's PK,
  no name inversion). Folds into R97's consumer-derivation direction.
- **Readability of the *existing* inline NodeId emitter.** Tracked separately as
  R260; this item's new helper must emit the readable statement form from day
  one, but it does not refactor `ArgCallEmitter.buildNodeIdDecodeExtraction`.
- Non-jOOQ ORM record types (Hibernate, MyBatis) ; `@service` keeps accepting
  POJOs/records of any origin via the existing path, unchanged.
