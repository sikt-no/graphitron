---
id: R162
title: "Consolidate MutationField permits under verb-on-permit-identity"
status: Backlog
bucket: structural
priority: 4
theme: mutations-errors
depends-on: [unify-record-dml-on-carrier-walk]
created: 2026-05-14
last-updated: 2026-05-14
---

# Consolidate MutationField permits under verb-on-permit-identity

> **Superseded by R164** (`field-model-two-axis-pivot`). The verb-on-permit-identity intuition is correct, but the empirical evidence (audit at 2026-05-14) shows the right cut is two orthogonal axes (DataFetcher × QueryBuilder), not a single re-seal of `MutationField`. R162's `Result` discriminator becomes the `returnShape` sub-component of `QueryBuilder.DML(verb, multiRow, returnShape)` under R164; the verb axis lives in the `DML` arm uniformly. Discard once R164 enters Spec.

The `MutationField` sealed hierarchy has grown three permit families with overlapping axes. Today: the `DmlTableField` family (`MutationInsertTableField`, `MutationUpdateTableField`, `MutationDeleteTableField`, `MutationUpsertTableField`) seals on DML verb and carries the return-shape dispatch in a `DmlReturnExpression` sealed field; the carrier-walk family (`MutationDmlRecordField`, `MutationBulkDmlRecordField`) carries `DmlKind` as a field and seals on cardinality (single vs. bulk); the service-backed family (`MutationServiceTableField`, `MutationServiceRecordField`) seals on return shape and has no verb axis. The three families pick different axes for permit identity, so the same conceptual question ("what verb runs?", "what's the return shape?", "how many rows?") gets answered through different mechanisms depending on which family the permit lives in. The `MutationField` switch in `TypeFetcherGenerator` carries the cost: six-plus arms today, with the verb dispatch happening at the arm level for `DmlTableField` but at the body level for carrier-walk permits.

The verb-on-permit-identity pattern is the principled target. "The field type tells us what we'll be creating" — and the `DmlTableField` family already implements this rule. This item lands the consolidation: every `MutationField` permit is verb-named, the return-shape distinction lives in a sealed `Result` discriminator on each permit, and the cardinality (bulk-or-not) axis becomes a property of `tableInputArg.list()` rather than permit identity. The multi-row axis (one input affecting many DB rows via `multiRow: true`) gets its own permit names because the structural shape genuinely differs — no PK pinning, broadcast WHERE clause, `mutation-input.where-columns-cover-pk` waived.

## Target permit landscape

Seven permits replace today's eight:

- `MutationInsertResultField` — single and bulk INSERT.
- `MutationUpdateResultField` — single-row UPDATE (covers single-input and bulk single-row).
- `MutationMultiRowUpdateResultField` — broadcast UPDATE.
- `MutationDeleteResultField` — single-row DELETE.
- `MutationMultiRowDeleteResultField` — broadcast DELETE.
- `MutationUpsertResultField` — single and bulk UPSERT (bulk lifted by R145).
- `MutationServiceResultField` — service-backed; one permit unifies current `MutationServiceTableField` + `MutationServiceRecordField`.

Each permit carries a sealed `Result` discriminator covering `Encoded{Single,List}` / `Projected{Single,List}` / `CarrierWrapper` (DML permits) or `Table` / `Record` (service permit). The four `DmlTableField` permits fold in via the `Encoded` and `Projected` arms; the post-R161 carrier-walk permits fold in via `CarrierWrapper`. `DmlKind` is gone from every permit, encoded in permit identity. `tableInputArg.list()` carries the bulk axis where it applies. The `MutationField` switch collapses from six-plus arms to seven kind-named arms, each dispatching internally on `Result`.

## Why these specific axes

- **Verb on permit identity.** Emit shapes per kind differ significantly (different SQL primitives — `.insertInto`, `.update`, `.deleteFrom`, `.upsert`); sealing on kind earns its keep because the switch is the main dispatch surface.
- **Cardinality as a field, not permit identity.** `MutationDmlRecordField` and `MutationBulkDmlRecordField` have identical component lists today; the seal is redundant with `tableInputArg.list()`. Conditional compact-constructor invariants enforce the bulk-only invariants (cross-cardinality classifier check `mutation-dml-record-field.data-table-equals-input-table`, order preservation runtime claim, kind restriction). The seal carries no structural information beyond what a boolean field captures.
- **MultiRow on permit identity.** A single input producing many DB changes carries invariants the single-row case doesn't share — WHERE clause doesn't pin a PK, affected-row count may exceed one, `mutation-input.where-columns-cover-pk` flips from required to waived, emit shape lacks PK-keyed response projection. Unlike bulk, this distinction isn't redundant with any component field; the discriminator carries structural information beyond what `tableInputArg.list()` captures. INSERT and UPSERT don't have multi-row variants (you insert/upsert what you give).
- **Service as a single permit.** Graphitron doesn't see verbs for `@service`-backed mutations (the user's method decides what runs). One service permit; return shape lives in the `Result` discriminator.

## Spec must address

- **`DmlReturnExpression` collapse.** With consolidation, does `DmlReturnExpression` still exist as a sealed type, or does it fold entirely into `Result`? The current four-arm shape (post-R161) overlaps cleanly with `Result`'s `Encoded{Single,List}` / `Projected{Single,List}` arms; if `Result` is the right home, `DmlReturnExpression` retires too.
- **Bulk Upsert deferral.** R145 lifts the bulk Upsert restriction. Until then, `MutationUpsertResultField`'s compact constructor rejects `tableInputArg.list() == true`. Spec needs to enumerate the compact-constructor invariant set per permit, including the deferral.
- **`Result.CarrierWrapper` vs. `Result.Projected{Single,List}`.** Confirm whether `CarrierWrapper` (the post-R161 carrier-walk case) is genuinely distinct from `Projected{Single,List}` at the emit level, or whether the two collapse into one arm. The two produce different graphql-java source values today (PK record vs. projected record), so they're likely distinct, but the spec audit should verify the emit shapes are different enough to warrant separate arms.
- **MultiRow admission scope.** The `multiRow: true` directive lives on `@mutation` today; spec needs to enumerate which kinds admit it (UPDATE, DELETE confirmed; INSERT and UPSERT excluded by construction). The classifier branch that routes to `MutationMultiRowUpdateResultField` vs. `MutationUpdateResultField` needs an explicit rule.
- **Test migration scope.** Classification fixtures across `GraphitronSchemaBuilderTest` reference current permit types by name across many test cases. Spec should enumerate the rename scope and identify whether any test names themselves need updating to reflect the verb-on-identity pattern.
- **The `MutationField` interface's `kind()` method.** `DmlTableField` exposes `kind()` today; carrier-walk permits expose a `kind` field. Once kind is on permit identity, the method becomes redundant for `instanceof MutationInsertResultField`-style dispatch but may still be useful for unified switches that want to extract the verb. Spec should decide whether to keep it on a common supertype or delete it entirely.

## Dependencies

- **R161** (`unify-record-dml-on-carrier-walk`) must land first. The carrier-walk pair can't be cleanly renamed and folded while Path 2 is still alive.
- **R145** (`mutation-cardinality-safety-upsert`) is not a hard dependency, but the spec encodes the bulk-Upsert deferral as a compact-constructor invariant that R145 lifts.
- **R12** (`error-handling-parity`) is inherited transitively via R161.
