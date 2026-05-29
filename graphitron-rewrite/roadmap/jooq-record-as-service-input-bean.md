---
id: R195
title: Decode @nodeId into jOOQ-record-typed @service input-bean fields
status: Spec
bucket: feature
priority: 5
theme: model-cleanup
depends-on: []
created: 2026-05-20
last-updated: 2026-05-29
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

Two deliverables, both required:

1. **Decode happy path.** Read `@nodeId(typeName:)` on a jOOQ-record-typed bean
   field, emit a NodeId-decode-and-materialize instead of a cast.
2. **Loud rejection (load-bearing half).** Any jOOQ-`Record`-typed bean member
   without a *handled* decode strategy is rejected at generation time, never
   falls through to `Direct`. After this item, a jOOQ-record member has exactly
   two outcomes: decode-record leaf, or typed `Result.Failed`. `Direct` never
   sees one again.

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
        throw new GraphqlErrorException("Decoded NodeId did not match the expected type for this argument");
    }
    SakRecord record = new SakRecord();
    record.from(key);
    return record;
}
```

For a non-null SDL field (`ID!`) the helper throws on a `null` decode; for a
nullable field (`ID`) it returns `null`. Whether a *null wire value on an `ID!`
field* should throw vs. defer to Jakarta non-null validation is settled in
implementation against the existing non-null-arg behaviour; the decode-mismatch
throw above is unconditional.

`record.from(key)` is the crux that means **no encoder-generator change is
needed**: `NodeIdEncoder.decodeSak(...)` builds its `RecordN` from the literal
`Tables.SAK.<pkCol>` fields (`NodeIdEncoderClassGenerator`, `newRecord(Tables.SAK.COL, ...)`),
so the decoded tuple's field names are identical to `SakRecord`'s key fields and
jOOQ's `Record.from(Record)` copies them across by name. The decode returns a
key tuple (`Record1<Long>` / `RecordN`), *not* a `TableRecord`; `from` is what
turns it into one.

Java-17 note: the helper matches on `wire instanceof String nodeId` (legal in
17). It does **not** pattern-match the parameterised `RecordN` (that would
require Java 21+); it null-checks the decode result and calls `from`. This avoids
the `(Object) ... instanceof Record1` dance the existing inline emitter needs.

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
`TableRecord`** via `.from()`. Same decode helper, opposite downstream
projection. Reusing the same arm would force two consumers to fork differently on
the same model field (the "two consumers, same predicate" smell in
"Generation-thinking"); a distinct leaf makes each consumer fork on *identity*.
The `recordType` is also available on `FieldBinding.javaElementTypeName`, but
carrying it on the leaf (rather than re-reading the string in the emitter) is
what pins the "this becomes a record, not columns" certainty in the type.

`NodeIdDecodeRecord` reuses `HelperRef.Decode` unchanged; `nonNull` drives
throw-vs-null and is read off SDL nullability at classify time.

`nonNull` is **not** the `NodeIdDecodeKeys` Skip-vs-Throw axis re-encoded as a
boolean. The decode-*mismatch* throw is unconditional here (a wrong-type id at a
record-materialize slot is always a contract violation; see the helper body
above), so this leaf never carries the Skip arm. `nonNull` governs the *separate*
null-wire-value branch: on an `ID!` field a `null`/absent wire value throws, on an
`ID` field it yields a `null` member. The javadoc on the permit must name this
distinction so a reviewer does not read `nonNull` as a redundant restatement of
the sibling sealed taxonomy.

Because `CallSiteExtraction` is a top-level sealed, adding a permit makes every
**exhaustive, `default`-free** switch over it a compile error until handled ; that
is the intended forcing function. The one legal producer is `InputBeanResolver`.
But the leaf has **two** live consumers of `FieldBinding.leaf()`, not one, and the
forcing function only catches the `default`-free switches: a `default ->` or an
`instanceof`-ladder fallback swallows the new permit silently. The full consumer
inventory (architect finding, R195 review) and the required disposition per site:

- **`InputBeanInstantiationEmitter.perFieldValueExpr`** (`:129-138`) ; `switch`
  with `default -> throw`. Disposition: real `NodeIdDecodeRecord` arm (emits the
  helper call + per-type `decode<Type>Record` helper). The happy-path consumer.
- **`ServiceMethodCallWalker.fieldBindingShape`** (`:190-208`) →
  **`ServiceMethodCallEmitter.scalarLeaf`** (`:153-163`) ; `isLeaf`
  `instanceof`-ladder, then `else -> new Direct()`, and the `Direct` then hits
  `scalarLeaf`'s `default -> ($T) cast`. Disposition: **load-bearing.** This is the
  R238 `@service` emission path and it walks `InputBean` fields too
  (`inputBeanToValueShape :178-188`). A `NodeIdDecodeRecord` leaf reaching here is
  silently rewritten to `Direct` and cast to `(SakRecord) raw` ; the exact
  `ClassCastException` this item exists to kill, on a second path. The compiler
  will *not* flag it. Either teach this path the leaf (real arm) or reject it ;
  whichever, the handling must be pinned by a test, not asserted.
- **`FieldBuilder.javaTypeFor`** (`:1279-1287`) ; exhaustive, `default`-free.
  Disposition: compile-breaks, add an explicit arm. Column-bound `javaType`
  resolution; `NodeIdDecodeRecord` is input-bean-only, so an unreachable/throw arm
  naming that fact is correct.
- **`ArgCallEmitter.buildArgExtraction`** (`:272-298`) ; exhaustive over
  `CallSiteExtraction`. Disposition: explicit unreachable arm
  ("`NodeIdDecodeRecord` is an input-bean field leaf only").

`LookupValuesJoinEmitter`'s decode switch (`:328-334`) is intentionally *excluded*:
it switches over `DecodeBinding.extraction`, which is statically typed
`NodeIdDecodeKeys` (`:101`), not the top-level `CallSiteExtraction`, so the new
permit cannot reach it and it needs no arm. The implementer should re-confirm this
static-type fact rather than trusting the exclusion blindly, but it is not a site
to touch.

Per-site, the disposition is reject-or-pin: even where the conclusion is
"unreachable by construction," the design principle "documentation names only
live tests/code" wants the unreachability backed by a test or a structural
guarantee, not an inline assertion. The `ServiceMethodCallWalker` silent-`Direct`
rewrite is the one that turns this from belt-and-suspenders into the *only* real
guard on the `@service` path, so its coverage is not optional.

### Decode-helper resolution: reuse, don't duplicate

Do **not** route through `NodeIdLeafResolver` (architect finding 2): its whole
purpose is the same-table-vs-FK fork against a *containing table*, which the
input-bean field has none of. Equally, do not hand-roll a fresh
typeName→keyColumns→decodeHelper walk ; that is the second site that drifts from
`NodeIdLeafResolver.resolveTargetKeys` when the `@node(keyColumns:)` fallback
changes.

Instead: resolve `@nodeId(typeName:)` → the backing SDL object type → its
`@table` name (in scope at the resolver), then call the **existing**
`BuildContext.resolveDecodeHelperForTable(tableName, typeId, keyColumns)`
(`BuildContext.java:2111-2136`). Key-column resolution stays in one place. If the
key columns are needed before that call, extract
`NodeIdLeafResolver.resolveTargetKeys` into a shared `BuildContext` method both
sites call, rather than copying it.

**Correctness subtlety to honour (architect finding 2):** calling
`resolveDecodeHelperForTable(tableName, typeName, keyColumns)` *as-is reproduces
the bug.* In its primary branch (`BuildContext.java:2118-2124`) it ignores the
passed `typeName` and recomputes the suffix from `findGraphQLTypeForTable(table)`
(singular); the passed name is only consulted in the no-GraphQL-type-backing
fallback (`:2133-2135`). `findGraphQLTypeForTable` returns the single backing type
or *empty* on ambiguity, so when several object types back one table the suffix
either disagrees with the author's explicit `@nodeId(typeName: "Sak")` or
collapses to the fallback. The decode helper name must be `decode<Sak>` (the
author-given typeName), not `decode<someTypeForTable>`. The fix is a
typeName-anchored suffix, which the existing method does **not** do in its primary
branch: either add a typeName-first overload/path that uses the table only for key
columns, or resolve the suffix from the typeName side before the call. Do not
assume the existing entry point already honours this.

### Loud rejection

In the `buildInputBeanBody` leaf ladder, before `else -> new Direct()`: if the
member's loaded Java type is assignable to `org.jooq.Record`, branch:

- `@nodeId` present and target + single-column key resolve → `NodeIdDecodeRecord`.
- otherwise (no `@nodeId`; unresolvable target; composite/multi-column key we
  punt on in v1; list-of-record member) → `Built.Fail` / `Result.Failed` with a
  message naming the field, the record type, and the remedy ("add
  `@nodeId(typeName:)`", or "composite-key record members are not yet
  supported"). This is the same typed-rejection contract the resolver already
  uses (`InputBeanResolver.java:65-68, 141-176`); it fails the build.

### Emitter exhaustiveness

`InputBeanInstantiationEmitter.perFieldValueExpr` (`:129-138`) currently switches
`Direct | EnumValueOf | InputBean | default -> throw IllegalStateException`. The
`default -> throw` is precisely the runtime-failure-masquerading-as-coverage the
"Validator mirrors classifier invariants" principle bans. Replace it with an
explicit arm per leaf, including the new `NodeIdDecodeRecord` arm that emits the
helper call + the per-type `decode<Type>Record` helper. A classifier may produce
the new leaf *iff* every consumer that walks `FieldBinding.leaf()` has a real arm
or a pinned rejection for it ; see the consumer inventory table in "Model: a
dedicated leaf variant" for the full per-site disposition. The crucial second
consumer is `ServiceMethodCallWalker.fieldBindingShape`, whose silent
`else -> new Direct()` fallback is *not* compiler-flagged and would otherwise
reintroduce the cast on the `@service` path.

## Tests

- **Pipeline tier (primary).** SDL with a `@record`/`@service` input bean whose
  fields are `ID! @nodeId` / `ID @nodeId` targeting `@table` types with
  record-typed bean members → assert the generated `TypeSpec` carries the decode
  helper and the bean-field assignment goes through it, with no raw
  `(SomeRecord) raw.get(...)` cast. (TypeSpec-shape / behaviour assertions; no
  code-string body assertions, per the test-tier ban.)
- **Rejection test.** A jOOQ-record bean member with no `@nodeId` fails the build
  via a named `Rejection`. Cover the composite-key punt explicitly as its own
  rejection case (it is a distinct ladder branch, not a fall-through).
- **`@service`-path no-cast guard (load-bearing).** The decode happy-path test
  must exercise the R238 `@service` emission path (`ServiceMethodCallWalker` /
  `ServiceMethodCallEmitter`), not only `InputBeanInstantiationEmitter`, and
  assert no raw `(SomeRecord) ...` cast survives there. This is the path the
  sealed-permit forcing function does *not* protect; without a test, the silent
  `Direct` rewrite is unguarded. If the chosen disposition for any consumer is
  "unreachable by construction," that unreachability gets a test too.
- **Compilation tier.** A fixture in `graphitron-sakila-example` exercising a
  single-key `@nodeId` record member compiles against the real jOOQ catalog
  (catches `from`/type mismatch). Sakila has single-column-PK tables suitable for
  a `@nodeId` round-trip; pick one for the fixture.
- **Execution tier (if a fixture is reachable).** A mutation that decodes a
  NodeId into a record member and round-trips against PostgreSQL.

## Affected code

- `InputBeanResolver.java` ; read `@nodeId`, the jOOQ-record branch, the
  `NodeIdDecodeRecord` leaf, the loud rejections.
- `model/CallSiteExtraction.java` ; the `NodeIdDecodeRecord` permit.
- `generators/InputBeanInstantiationEmitter.java` ; the new leaf arm + per-type
  `decode<Type>Record` helper; remove the `default -> throw`.
- `walker/ServiceMethodCallWalker.java` + `generators/ServiceMethodCallEmitter.java`
  ; the load-bearing second consumer. Teach the leaf or reject it; the current
  `else -> new Direct()` (`ServiceMethodCallWalker:200-201`) and `scalarLeaf`'s
  `default -> ($T) cast` (`ServiceMethodCallEmitter:161-162`) silently miscompile
  the new leaf. Not compiler-flagged ; must be handled by hand + test.
- `FieldBuilder.java` (`javaTypeFor :1279-1287`) ; exhaustive `default`-free
  switch, will compile-break ; add an explicit (unreachable, input-bean-only) arm.
- `generators/ArgCallEmitter.java` (`buildArgExtraction :272-298`) ; explicit
  unreachable arm.
- `BuildContext.java` ; reuse `resolveDecodeHelperForTable`, but note its primary
  branch derives the suffix from the table not the typeName (see finding 2) ; a
  typeName-anchored suffix path is required. Possibly host a shared
  `resolveTargetKeys` lifted from `NodeIdLeafResolver:386-412`.

## Deferred / out of scope

- **Top-level `@service` parameter that *is* a jOOQ record** (the original R195
  framing). Reject loudly for now; the direction (grow an `@service` jOOQ-record
  arm vs. route to the DML `@mutation` path) is tangled with R97
  ("Deprecate `@table` on input") and should not be cemented here.
- **`@field(name:)` / `@table`-on-input translation.** Not needed for the
  reported case (the record member's key columns come from the target table's PK,
  no name inversion). Folds into R97's consumer-derivation direction.
- **Composite-key (`RecordN`, N > 1) record members.** Reject in v1; the `from`
  shape generalises but wants its own test matrix.
- **List-of-jOOQ-record members.** Reject in v1.
- **Readability of the *existing* inline NodeId emitter.** Tracked separately as
  R260; this item's new helper must emit the readable statement form from day
  one, but it does not refactor `ArgCallEmitter.buildNodeIdDecodeExtraction`.
- Non-jOOQ ORM record types (Hibernate, MyBatis) ; `@service` keeps accepting
  POJOs/records of any origin via the existing path, unchanged.
