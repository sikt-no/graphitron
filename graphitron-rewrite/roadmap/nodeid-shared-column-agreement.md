---
id: R322
title: "Runtime value-agreement check for multiple @nodeId decodes onto shared columns"
status: Ready
bucket: feature
priority: 4
theme: nodeid
depends-on: []
created: 2026-06-17
last-updated: 2026-06-19
---

# Runtime value-agreement check for multiple @nodeId decodes onto shared columns

When Graphitron decodes more than one `@nodeId` onto the columns of a single row, two writers can
land on the **same** column: two `@nodeId(typeName: "Film")` fields both targeting `film_id`; a
same-table identity plus an FK reference whose child column is part of the PK; two composite FKs that
overlap on a shared child column; or a plain `@field` and a `@nodeId` decode that resolve to one
column. Whether that is a problem is **data-dependent**: if every present writer agrees on the
column's value it is harmless, if they disagree one caller-supplied value would be silently
overwritten. That silent overwrite is exactly the "no silent drops" failure the generator exists to
avoid, and the disagreement is only observable at runtime (the values arrive off the wire), so it
cannot be a build-time rejection.

Two materialization paths decode `@nodeId`s onto a row's columns, and they fail differently today.
This item makes both safe with one shared agreement predicate plus, on the mutation path, the
structural dedup that predicate presupposes. The build-time-decidable half (two plain `@field`s on
one column) becomes a build-time rejection on both paths.

## Current behavior (as found)

### The `@service` jOOQ-record path: silent last-write-wins

`JooqRecordInstantiationEmitter` (R311, generalized by R315) emits a `create<Record>` helper that
builds a jOOQ `TableRecord` from the input `Map` with one guarded load per binding: `emitColumnBinding`
(`:104`) for a plain `@field`, `emitKeyDecode` (`:135`) for each `@nodeId` decode. The loads are
sequential `rec.set(...)` / `rec.fromArray(...)` calls, so two writers on one column simply
**last-write-wins, silently**. R336 added a plain-`@field`-vs-plain-`@field` collision reject in
`InputBeanResolver.buildJooqRecord` (`:324-334`) but explicitly left decode-vs-decode and
decode-vs-column to this item (last-write-wins in the interim). R315 ships several `@nodeId` fields
per record and reconciles overlapping target columns to node-key order, so the overlap is reachable;
R315's motivating consumer just happened to use disjoint columns.

### The `@mutation` `TableInputType` DML path: loud Postgres crash

`TypeFetcherGenerator.buildInsertColumnList` (`:2026`) and `buildPerCellValueList` (`:1929`) walk the
`SetField` carriers through `flattenInsertLeaves` and emit one column ref + one `DSL.val(...)` cell per
carrier slot, with **no dedup**. Two writers on one column put the column in the INSERT list twice, so
Postgres raises `column specified more than once`, a loud crash rather than a silent drop.
`MutationInputResolver.admitMutationInputFields` admits multiple `ColumnReferenceField` /
`CompositeColumnReferenceField` (R189) and plain `ColumnField`s with no overlap guard, so the crash is
reachable today via two composite FKs sharing a child column, or a plain `@field` plus an FK reference
on one column. The self-FK shape that most naturally produces the overlap is R328, still Backlog and
currently rejected at classification (`NodeIdLeafResolver` short-circuits `SameTable` before
`resolveFkJoinPath`).

## Overlap taxonomy

Keyed on the kinds of writer that collide on a single column, after the R336/R186 nested-group
flattening:

- **Two or more plain `@field` writers**: build-time reject. The collision is a pure schema fact
  (both names resolve to one `ColumnRef` with no runtime input), and it is avoidable (drop the
  redundant field), so it is an author error caught at build time. R336 already does this on the
  `@service` path; this item extends it to the mutation path (D2).
- **Any overlap involving at least one decode** (decode-vs-decode, or one plain `@field` plus one or
  more decodes): runtime agreement check (D3). FK topology can legitimately force a column to be
  written by two references, and a decode's value is only knowable at runtime, so this cannot be a
  build-time rejection without rejecting valid schemas.
- **A single writer**: no overlap; emission is unchanged.

## Design

### D1: One build-time overlap analysis, consumed three ways

Resolve, per backing table, a map from column to its ordered list of contributing writers, where each
writer carries its carrier, the **slot index** of this column within that carrier's column tuple, and
its wire access path / presence test. Compute it once and consume it three ways: the field-vs-field
reject (D2), the mutation dedup (D5), and the agreement check on both paths (D3/D4/D5).

Computing the overlap once is load-bearing on the mutation path: the two INSERT walks
(`buildInsertColumnList`, `buildPerCellValueList`) stay positionally parallel "by construction (both
walk `flattenInsertLeaves`)" today, and each independently re-deriving "is this column already owned
by an earlier leaf?" would risk silently desyncing the column and value lists, a worse failure than the
crash it replaces. One analysis owning the fork is the Generation-thinking move R315's `resolveFkSlots`
made for FK orientation.

The analysis is per-path because the carrier models differ but yields the same column-to-writers
shape. On the `@service` side writers come from `CallSiteExtraction.JooqRecord` (`columnBindings` plus
`keyDecodes`, each `RecordKeyDecode` carrying `targetColumns`); on the mutation side from the
`InputField` `SetField` carriers (`ColumnField` / `CompositeColumnField` over `column()` / `columns()`,
`ColumnReferenceField` / `CompositeColumnReferenceField` over `liftedSourceColumns()`, already permuted
to node-key order by `NodeIdLeafResolver`).

### D2: Build-time reject for two or more plain `@field`s on one column (both paths)

R336's `@service`-path reject stays. Add the mirror on the mutation path as a **validate-time
rejection** (a structural `Rejection` surfacing as `UnclassifiedField`, or a `GraphitronSchemaValidator`
rule over the resolved `SetField` columns), reading the D1 analysis, so the validator mirrors the
classifier instead of deferring to the Postgres SQL error. The message mirrors R336's "two fields
cannot populate one column".

### D3: Runtime agreement check for any overlap involving a decode

For a column with at least two present writers where at most one is a plain `@field` (so D2 did not
fire), emit a check that the present writers agree, throwing `GraphqlErrorException` on disagreement.
Agreement is defined by the **destination column's coercion**: coerce each writer's value through the
column's jOOQ `DataType` and compare with `equals`. That is exactly the coercion the real write
applies, so the check faithfully predicts a conflicting store. The check is a shared helper on the
generated `NodeIdEncoder`:

```java
public static void requireColumnAgreement(String conflictLabel, DataType<?> type, Object a, Object b) {
    if (!java.util.Objects.equals(type.convert(a), type.convert(b))) {
        throw GraphqlErrorException.newErrorException().message(/* conflictLabel-based */).build();
    }
}
```

`NodeIdEncoder` is the natural home: both paths already call into it (`decodeValues` from `@service`,
`decode<Type>` from the mutation path, the latter built on the former), and it already carries
`@SuppressWarnings({"deprecation","removal"})` for `DataType.convert` (`:146`), so no new suppression
appears. `convert` accepts all three representations the call sites hold: the raw decode `String` slot
(`@service`, `decodeValues`), the already-typed `value<N>()` (mutation, `decode<Type>`), and the raw
wire `Object` of a plain field, normalizing each to the column's Java type. For a column with more than
two writers the site emits pairwise calls against the first writer (`equals` is transitive). The check
is **presence-guarded**: it fires only when at least two writers are actually present at runtime, so an
omitted nullable writer is not a writer and cannot conflict. `conflictLabel` names the conflicting
GraphQL input fields, not the SQL column, consistent with the existing decode-mismatch messages
("... for input field 'X'") and not leaking the `@field(name:)` mapping.

### D4: `@service` emission (add the check, no structural dedup)

The sequential `rec.set` / `rec.fromArray` loads already tolerate a column being written more than once
(natural last-write-wins), so no dedup is needed; agreeing writers produce the same record regardless
of order. For an overlapping column, read the contributing writers' slots into named locals (today the
plain-field value and the decode `String[]` slot are read in two separate loops, `emitColumnBinding`
then `emitKeyDecode`, so the D1 structure makes them co-available), emit the `requireColumnAgreement`
call(s), then the existing loads. Disjoint-column records emit byte-identical output to today
(pay-for-what-you-use).

### D5: mutation emission (structural dedup + the check)

Drive both the INSERT column list and the per-cell value list off the D1 slot structure so a shared
column contributes exactly one column-list entry and one VALUES cell. The single cell coalesces over
the present writers: lift it into a **named private helper with an `if` / `else` body** (statement
form, breakpointable, Java-17-valid), not a nested ternary at the call site, returning the first
present writer's coerced value or `DSL.defaultValue(...)` when none is present. The
`requireColumnAgreement` call(s) guard the multi-present case before the cell is built. Disjoint
columns keep the existing one-leaf-one-cell shape.

This dedup is the piece R328 presupposes. Once R328 admits the self-FK reference as a
`CompositeColumnReferenceField`, that carrier's `liftedSourceColumns` overlap the row's own PK and flow
through this same slot structure with no further emit work, so **R328 depends on R322** and shrinks to
its self-FK classifier admission, inheriting detect-dedup-agree.

## Why coerced comparison, not string comparison

The stub's "pre-coercion string comparison ... is sufficient" was revised during Spec. A `String.valueOf`
compare false-disagrees on the decode-vs-field case: the decode side is the NodeId's canonical
`toString` form (e.g. `"1"`), but the plain `@field` side is the raw, uncoerced wire value, which
graphql-java may hand over as `"01"`, `1.0`, or a `BigInteger`. Those `String.valueOf`-differ from `"1"`
yet coerce to the same key, so the check would throw a spurious `GraphqlErrorException` on data that
would have inserted identically. Coercing both sides through the destination column's `DataType`
(`X.getDataType().convert(...)`, the same call `decode<Type>` already makes at
`NodeIdEncoderClassGenerator:227`) collapses `convert("01")`, `convert(1.0)`, `convert(BigInteger 1)`
and the decoded `Integer 1` to one value, so they agree. For a genuinely `varchar` column, `"01"` and
`"1"` still correctly disagree, because there they are different stored values. A present-`null` writer
coerces to `null` and disagrees with a present non-null (a write conflict); two present-`null`s agree.
decode-vs-decode is safe either way (both sides went through the same `encode<Type>` `toString`); it is
decode-vs-field that requires the coercion.

## Rejections (build-time)

- Two or more plain `@field` leaves (at any nesting depth) resolving to one column, on either path
  (D2). On the `@service` path this is the existing R336 reject; on the mutation path it is new and
  moves the failure from a runtime Postgres error to a validate-time `UnclassifiedField`.
- Everything else with an overlap is admitted and reconciled at runtime by D3.

## Coverage

Per the testing tiers, the agreement behavior is an **execution-tier** contract on both paths
(round-trip against Postgres):

- disagreeing writers throw `GraphqlErrorException`;
- agreeing writers insert the agreed value;
- an omitted nullable writer leaves a single present writer and does not throw even when its value
  would "disagree" with the absent one (the presence guard);
- a format-variant wire value (`"01"` or `1.0` against a numeric column) agrees rather than
  false-throwing, pinning the coerced-comparison choice.

The structural dedup is observable at the execution tier (the INSERT/UPDATE succeeds where it would
have raised `column specified more than once`), or at the pipeline tier as an assertion on the D1 slot
structure; it must not be asserted by matching the emitted coalesce/helper text (code-string assertions
on generated bodies are banned at every tier). The mutation-path field-vs-field reject is a
pipeline-tier rejection-message assertion, mirroring the existing `@service` R336 reject test. Reuse the
`nodeidfixture` composite-key / FK shapes and Sakila for the round-trips; a neutral
two-FKs-sharing-a-child-column or plain-field-plus-reference fixture exercises the mutation overlap
without waiting on R328's self-FK admission.

## Relationship to other items

- **R315** (Done): shipped the `@service` FK-reference path with last-write-wins on the overlap edge,
  deferring value-agreement here.
- **R336** (Done): added the `@service` plain-field-vs-plain-field reject and explicitly left
  decode-vs-decode / decode-vs-column to this item.
- **R328** (Backlog, will depend on R322): self-FK `@nodeId` on mutation inputs; its structural dedup
  (its design point 3) and value-agreement (its design point 4) are both subsumed here, so it shrinks
  to the self-FK classifier admission and inherits detect-dedup-agree.
- **R130** (INSERT carve-out), **R189** (FK-target reference carriers on INSERT/UPDATE/DELETE): the
  carriers whose overlap this item reconciles.
- **R273** (nodeid mismatch semantics): a sibling decision on a different failure mode (wrong-type /
  malformed decode in filter position), independent of value-agreement on the write path.

## Out of scope

- WHERE-side / lookup-key overlaps: two lookup keys on one column produce a redundant but harmless
  predicate, not a conflicting write. This item is about columns written by the record / SET / INSERT.
- The same-table-identity self-FK admission itself (R328): this item provides the machinery R328
  consumes but does not change `NodeIdLeafResolver`'s same-table short-circuit.
- The R150 / R195 InputBean member-axis path (each record member is a separate Java object, so there
  are no shared columns to reconcile), unchanged.
- The NodeId wire format and the decode helpers' existing null / arity mismatch throw, unchanged; the
  agreement check runs after a successful decode.

## Open questions (resolved during Spec)

- **Scope**: both materialization paths, not `@service`-only.
- **Inline vs runtime helper**: a shared helper on `NodeIdEncoder`, so the message and semantics have
  one home and the two paths cannot drift.
- **Comparison**: coerce through the destination column's `DataType` and compare typed, not
  `String.valueOf` (avoids false disagreements on format-variant wire values; faithfully models the
  destination store).
- **decode-vs-column**: a runtime agreement check, not a build-time reject; only
  plain-field-vs-plain-field rejects at build time.

## Note

`principles-architect` consulted during Spec. Three findings folded in: the coerced (not
`String.valueOf`) comparison (D3 / "Why coerced comparison"); one build-time overlap analysis feeding
both INSERT walks plus a named statement-form coalesce helper rather than a nested ternary (D1 / D5);
and the mutation-path field-vs-field reject landing at validate time, mirroring R336, rather than as
emitter dedup alone (D2). The factoring (absorb dedup into R322, invert the dependency so R328 depends
on R322, share only the agreement predicate and not a materialization sub-step) was endorsed.
