---
id: R39
title: Validate that list fields on tables without a PK require explicit ordering
status: In Progress
bucket: validation
priority: 2
theme: model-cleanup
depends-on: []
last-updated: 2026-05-16
---

# Validate that list fields on tables without a PK require explicit ordering

## Problem

`OrderByResolver.resolveDefaultOrderSpec()` (`OrderByResolver.java:123`) falls back to
`OrderBySpec.Fixed([pk ASC])` when a list field has no `@defaultOrder` or `@orderBy` and the
table has a PK. For tables without a PK, it returns `OrderBySpec.None` instead
(`OrderByResolver.java:132`), which the generators faithfully emit as an empty `List.of()` ;
no `ORDER BY` clause. The result is a non-deterministic list every time the query runs.

The current validator does not catch this. `validateQueryTableField` only calls
`validateCardinality`. The existing "ordering required" checks are narrowly scoped:

- `validatePaginationRequiresOrdering` — only fires when pagination is also present.
- The `SplitTableField` connection check — only fires for connection-cardinality split fields.

Neither covers the plain list case on a no-PK table.

## Impact

Any list-returning `QueryTableField`, `QueryTableInterfaceField`, `TableField`,
`TableInterfaceField`, or `SplitTableField` (non-connection) on a table without a PK silently
produces non-deterministic ordering. Discovered during the interface/union Track A review when
comparing against the legacy generator, which always orders by PK.

## Fix

In `GraphitronSchemaValidator`, add a cross-cutting check alongside
`validatePaginationRequiresOrdering`: for any `SqlGeneratingField` whose wrapper is
`FieldWrapper.List` and whose `orderBy` is `OrderBySpec.None`, emit an `AUTHOR_ERROR`:

> "Field 'X.y': list fields must have a deterministic order. Add a primary key to the target
> table, or use @defaultOrder or @orderBy."

This mirrors the pagination check and catches the gap at build time rather than at runtime.

### Overlap with existing ordering checks

Three checks now cover three disjoint shapes:

- `validatePaginationRequiresOrdering` (`GraphitronSchemaValidator.java:129`) — fires when
  `pagination() != null && orderBy() instanceof OrderBySpec.None`. Catches paginated fields,
  including Connection wrappers (connections always carry pagination).
- `validateSplitTableField` connection branch (`GraphitronSchemaValidator.java:549`, body at
  line 555) — fires for `@splitQuery` connections with empty or `None` ordering. Narrower
  message ("@splitQuery connections require a non-empty ORDER BY") justifies its existence.
- *New:* list-without-ordering — fires when `wrapper instanceof FieldWrapper.List` (plain
  `[T]`, not `Connection`) and `orderBy() instanceof OrderBySpec.None`. Plain list wrappers
  do not carry `PaginationSpec`, so this is the residual gap.

Gating on `FieldWrapper.List` (rather than the broader `wrapper().isList()`, which also
returns true for `Connection`) keeps the three messages non-overlapping. `FieldWrapper`'s
sealed permits list (`Single`, `List`, `Connection` in
`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/model/FieldWrapper.java:23`)
guarantees no fourth case slips through.

## Implementation

One file: `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/GraphitronSchemaValidator.java`.

- Add a new `validateListRequiresOrdering(GraphitronField field, List<ValidationError> errors)`
  method modelled on `validatePaginationRequiresOrdering` (same file, lines 129–140). Body:

  [source,java]
  ----
  if (field instanceof SqlGeneratingField sgf
          && sgf.returnType().wrapper() instanceof FieldWrapper.List
          && sgf.orderBy() instanceof OrderBySpec.None) {
      errors.add(new ValidationError(
          field.qualifiedName(),
          Rejection.structural("Field '" + field.qualifiedName() + "': list fields must have "
              + "a deterministic order. Add a primary key to the target table, or use "
              + "@defaultOrder or @orderBy."),
          field.location()
      ));
  }
  ----
- Invoke it from `validateField` immediately after `validatePaginationRequiresOrdering` (call
  site at `GraphitronSchemaValidator.java:120`). Order in the file is "pagination check → list
  check → variant-implemented check".

No changes to `OrderByResolver`, `FieldBuilder`, or any generator. The check is a build-time
gate; resolver behaviour stays as documented in `OrderByResolver.java:132` (returns
`OrderBySpec.None` when no `@defaultOrder` and no PK), and the validator now rejects schemas
that would reach that branch on a list-returning field.

### Relationship to `@LoadBearingClassifierCheck`

The new validator method is a *hygiene-rejection* in the sense of
`rewrite-design-principles.adoc` §"Classifier guarantees shape emitter assumptions": no
emitter currently relies on it. `TypeFetcherGenerator.buildOrderByCode`
(`TypeFetcherGenerator.java:3296`), `buildConnectionOrderingBlock`
(`TypeFetcherGenerator.java:3244`), `buildBaseReturnExpr` (`TypeFetcherGenerator.java:3377`),
and `InlineTableFieldEmitter` (`InlineTableFieldEmitter.java:145`) each defensively handle the
`OrderBySpec.None` and `Fixed.columns().isEmpty()` arms (emitting an empty
`List<SortField<?>>` or skipping the `orderBy(...)` call). With the new validator in place
those branches become unreachable for list fields, but they remain reachable for `Single`
returns and `Connection`-without-pagination shapes that the validator does not gate.

Consequence: no `@LoadBearingClassifierCheck(key = "list-field.requires-deterministic-order")`
producer is declared in this item, and no emitter is tagged with `@DependsOnClassifierCheck`.
The principle explicitly permits this ("Producers without consumers are allowed: some
classifier checks reject shapes for hygiene rather than because an emitter relies on them").
A future cleanup that tightens the defensive emitter arms into load-bearing assumptions is
the natural moment to add the annotation pair; that cleanup is out of scope here.

## Tests

Add to
`graphitron-rewrite/graphitron/src/test/java/no/sikt/graphitron/rewrite/validation/`, following
the parametrised "case enum + helper" pattern in
`QueryTableFieldValidationTest.java:24-133`.

The check is cross-cutting on the `SqlGeneratingField` capability (gated only on
`FieldWrapper.List` and `OrderBySpec.None`); it does not branch on leaf identity. Per
`rewrite-design-principles.adoc` §"Pipeline tests are the primary behavioural tier", per-variant
repetition of the same predicate across all five affected leaves (`QueryTableField`,
`QueryTableInterfaceField`, `TableField`, `TableInterfaceField`, non-connection
`SplitTableField`) would be bookkeeping. One Query-rooted surface and one child-position
surface exercise both halves of the `validateField` dispatch path; that pair is sufficient.

Cases to add:

- A `Query`-rooted list field returning a no-PK table: expect the new error.
- The same field with `@defaultOrder` resolving to `OrderBySpec.Fixed`: expect no error.
- The same field on a table that has a PK (so the resolver lands on `Fixed([pk ASC])`): expect
  no error.
- A `Single`-cardinality field on a no-PK table: expect no error (covered by the non-goal).
- A `Connection`-cardinality field on a no-PK table without pagination args declared in SDL —
  not constructible; the test harness can skip. The pagination-required check already
  guards the Connection-with-pagination case, exercised by
  `QueryTableFieldValidationTest.PAGINATED_WITHOUT_ORDERING`.
- A child-position list `TableField` on a no-PK table: expect the new error. Mirrors the
  child-field side of the same gap.

The new error string deliberately differs from the pagination error so failures point the
author at the right remediation (PK vs `@orderBy`).

### Pipeline-tier coverage (in scope)

Per `rewrite-design-principles.adoc` §"Pipeline tests are the primary behavioural tier", this
item is a behaviour change (a previously-accepted schema is now rejected), not a structural
invariant. The unit cases above test the validator predicate in isolation; one pipeline-tier
case proves the rejection survives SDL → classified model → assembled `GraphitronSchema` end
to end. Add a `ValidateListRequiresOrderingPipelineTest` (or fold a case into an existing
nearby pipeline test) following the shape of e.g. `SplitTableFieldPipelineTest`:

- *Reject path:* SDL with a `Query`-rooted `[Foo]` field on a `@table`-backed no-PK type and
  no ordering directives → assert the assembled schema reports the new error.
- *Admit path (control):* the same SDL with `@defaultOrder` resolving to `OrderBySpec.Fixed`
  → assert no error.

The reject-path fixture is the minimum surface that distinguishes "the predicate fires
in unit tests" from "the predicate fires when the full builder pipeline produces the
field"; it pins both the classifier/validator wiring and the error-message contract.

## Non-goals

- Requiring ordering on single-value fields (ordering is a no-op there).
- Requiring ordering on `@service` or `@tableMethod` fields (the developer's method owns the
  result set; Graphitron doesn't generate the SQL). Service/method-backed fields do not
  implement `SqlGeneratingField`, so the cast in the new check already excludes them.
- Changing `OrderByResolver` to refuse to produce `OrderBySpec.None`. The resolver's contract
  (total projection over all classified shapes) stays intact; the validator is the right
  layer for "this shape is legal in the model but illegal as authored schema."
- Removing or merging the existing `validatePaginationRequiresOrdering`. The two checks emit
  distinct remediation text and remain useful side by side.
