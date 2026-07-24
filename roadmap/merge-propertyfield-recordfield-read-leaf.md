---
id: R51
title: "Merge PropertyField and RecordField into one record-read leaf carrying a sealed locator"
status: Spec
theme: classification-model
bucket: cleanup
priority: 5
depends-on: []
last-updated: 2026-07-24
---

# Merge PropertyField and RecordField into one record-read leaf carrying a sealed locator

`ChildField.PropertyField` and `ChildField.RecordField` are one leaf wearing two names. Both classify
"read a value off a record-backed parent's in-memory object": same `source` fact, same empty operation
set, same read mechanism, and every consumer treats them as a pair. The evidence in the emit layer is
conclusive: both `bind` arms dispatch to the shared `propertyOrRecordBinding` helper
(`FetcherEmitter`), whose javadoc literally says "Binding for a `PropertyField` / `RecordField`"; the
same file carries four more two-arm `instanceof` chains extracting the same slots from either leaf
(`bindDualShape`, `isEnvDependentAccessorRead`, `inlineSuccessRead`, `isInlineArmSwitchedDataField`);
both `TypeFetcherGenerator` dispatch arms are identical no-ops; and `validatePropertyField` is an
empty method pointing at `validateRecordField`. A repeated two-arm `instanceof` chain at every consumer
is the signature of a missing single type.

What actually differs is carried facts, not identity:

- **The classification trigger**: scalar/enum return vs object return, i.e. the SDL return type.
- **The target fact**: `PropertyField` hard-codes `target() = Single(Field)` and carries no
  `ReturnTypeRef`; `RecordField` carries one and answers `listOrSingle(Field)`. `PropertyField` can
  name the column's Java type in `domainReturnType()` where `RecordField` answers `Object`.

In R333 vocabulary the two leaves differ in exactly one fact, the target; encoding that as permit
identity is the cross-product disease R222 dissolves, and the shape R432 already merged away for the
batched leaves (two leaves differing in one stored fact become one leaf carrying the fact).

## Target shape

One merged record-read leaf (fresh name chosen at pickup; fresh, not reused, per R432's rationale:
every switch arm and `instanceof` must be compiler-forced through the rename) carrying:

- **The target facts**: a `ReturnTypeRef` covering both the scalar and object cases, wrapper included,
  so `target()` and `domainReturnType()` derive instead of forking per leaf. The scalar case must keep
  answering the column's Java type where it does today.
- **One non-null sealed locator** replacing the `columnName` / `column` / `accessor` nullable triple.
  This is R333's field-level accessor fact ("The accessor is field-level"): arm identity instead of
  null checks, gated by the parent's source-object shape. Arms per the current populations: typed jOOQ
  column (`ColumnRef`), resolved Java accessor (record component / getter / public field,
  today's `AccessorResolution.Resolved`), and by-name jOOQ field (the string fallback the emitters
  reach when both slots are null). No transform axis; encodings stay entailed by the column facts the
  locator points at.

## Scope includes consumer cleanup

The merge is not done while the two-leaf vocabulary survives in consumers. In scope:

- Collapse every paired `instanceof` chain and switch-arm pair into single arms
  (`FetcherEmitter`, `TypeFetcherGenerator`, `GraphitronSchemaValidator`, and whatever else the
  rename flushes out).
- Rename `propertyOrRecordBinding` and rewrite its javadoc and the surrounding comments that narrate
  the two-leaf split (the `ChildField` javadoc's "distinguishing this leaf from RecordField" prose,
  the "See RecordField's analogous slot" cross-references, the paired no-op dispatch comments).
- Fold the `validatePropertyField` / `validateRecordField` pair into one (or delete, if the merged
  leaf leaves the site nothing to do, as both say today).
- Update the dispatch-status partition (`GeneratorCoverageTest`), the `@classified` corpus rows, and
  the LSP `FieldClassification` projection for the merged leaf.

## Execution pattern

Follow the landed template: R432's leaf merge (stored fact gates the fork; fresh names; compact-ctor
invariants pinning what each retired leaf guaranteed structurally) and R314's additive-cutover-retire
sequencing. Acceptance bar: byte-identical generated output across the sakila corpus, since the change
is representational.

## Out of scope

- `NestingField` also rides `isInlineArmSwitchedDataField` and is the passthrough locator arm in
  R333's read-side model, but it carries genuinely different facts (parent-table inheritance); folding
  it in is a possible follow-up, not this item.
- The type-level source-object fact (R333's cast-target side of the read); this item consumes the
  parent classification as-is.

## Lineage

Surfaced during R50's `columnName` cleanup on the column-backed carriers (since merged by R508 into
`ChildField.ColumnBackedField` / `ColumnBackedReferenceField`), where the table-backed-only invariant
let those carriers retire `columnName` outright. Two earlier framings of this item are rejected:
splitting the two leaves per parent kind into sealed arms (pre-R333; multiplies permits the R222
pivot dissolves, parent kind being a `source`-side fact), and keeping two leaves while sealing only
the locator (the leaf split carries no behavioral weight worth preserving).

## Retired vocabulary

`ChildField.PropertyField`, `ChildField.RecordField`, `propertyOrRecordBinding`,
`validatePropertyField`, `validateRecordField`.
