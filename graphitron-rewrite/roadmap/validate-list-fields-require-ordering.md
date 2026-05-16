---
id: R39
title: Validate that list fields on tables without a PK require explicit ordering
status: In Review
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

Three files.

1. *New marker:*
`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/model/OrderingOwnedByProducer.java`.

- Sealed marker capability `OrderingOwnedByProducer` permitting
  `ChildField.SingleRecordTableField` and `ChildField.ServiceTableField`. The contract: "the
  visible result ordering of this field is owned by an upstream producer, not by the field's
  own `orderBy()` component." For `SingleRecordTableField` the producer is the
  `FetcherEmitter` PK-keyed-map walk that re-orders the response SELECT to the upstream
  source list (`FetcherEmitter.java:512-557`); for `ServiceTableField` the producer is the
  developer's `@service` method, whose return is forwarded verbatim with no follow-up SELECT
  (`TypeFetcherGenerator.buildServiceRowsMethod`, line 4238). Sealed so a future permit with
  the same semantics must opt in deliberately — find-usages from either permit lands on the
  validator's exclusion below.

2. *Validator:*
`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/GraphitronSchemaValidator.java`.

- Add a new `validateListRequiresOrdering(GraphitronField field, List<ValidationError> errors)`
  method modelled on `validatePaginationRequiresOrdering` (same file, lines 129–140). Body:

  [source,java]
  ----
  if (field instanceof SqlGeneratingField sgf
          && !(field instanceof OrderingOwnedByProducer)
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

3. *Marker implementations:*
`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/model/ChildField.java`.

- `SingleRecordTableField` adds `implements ... OrderingOwnedByProducer`. Its existing
  hard-coded `orderBy() = OrderBySpec.None` override stays — the marker, not a synthetic
  PK-derived value, is what tells the validator the permit owns its own ordering. Doc-comment
  refresh: pair the "structurally empty" framing with a pointer to the marker so a reader
  arriving from either direction sees the coupling.
- `ServiceTableField` adds `implements ... OrderingOwnedByProducer` to its existing capabilities
  list. The record itself is unchanged structurally — the `orderBy` component still exists and
  still carries whatever the `FieldBuilder` construction sites pass it (today: always
  `OrderBySpec.None`, because `OrderByResolver` is not invoked at those sites). The marker
  re-classifies the type-level "this permit is exempt from the list-ordering check"; the
  remediation message stays accurate for everywhere else.

No changes to `OrderByResolver`, `FieldBuilder`, the carrier emitter, or any other generator.
The validator is a build-time gate; resolver behaviour stays as documented in
`OrderByResolver.java:132` (returns `OrderBySpec.None` when no `@defaultOrder` and no PK), and
the validator now rejects schemas that would reach that branch on a list-returning field that
does not bear the `OrderingOwnedByProducer` marker.

### Why a marker, not a derived `orderBy()` value

An earlier draft of this item had `SingleRecordTableField.orderBy()` compute a PK-fixed
default from `sourceKey.columns()` so the validator's "list + `None`" predicate didn't trip
on the carrier. That approach has two problems flagged by the principles-architect self-check:

- The PK-default projection duplicates `OrderByResolver.resolveDefaultOrderSpec`'s no-directive
  branch (same shape, different inputs, written by two different bodies). Two consumers
  evaluating the same predicate is the principle's "the branch belongs in the model"
  invitation; if either drifts, the validator's correctness drifts with it.
- The validator's correctness depended on the model derivation's invariant
  ({code}`sourceKey.columns()` is non-empty for any constructible `SingleRecordTableField`),
  which is itself a classifier guarantee from {code}`FieldBuilder.java:3175`. Three files
  coupled by an invariant that none of them name. A future producer of the permit with an
  empty source-key — perfectly legal at the type level — would surface as a misleading
  "add a primary key" rejection in the validator.

The marker interface moves the carrier-walk exemption into the type system, where
{code}`ServiceTableField`'s same-semantics exemption lives naturally beside it, and the
validator predicate becomes self-contained ("list-shaped and unorderable AND no producer owns
ordering"). One axis, one site, sealed so additions are deliberate.

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

The `OrderingOwnedByProducer` marker is sealed and the validator's exclusion is a direct
{code}`instanceof` against it: the validator's correctness does not depend on a classifier
guarantee about any permit's internal shape (e.g. whether `SingleRecordTableField`'s
`sourceKey.columns()` is non-empty). Find-usages from either permit lands directly on the
validator site, and adding a new permit to the marker's permits list is the explicit
deliberation point.

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

The `OrderingOwnedByProducer` marker exemption (carrier-walk / `@service` patterns) is
exercised end-to-end by the existing R141 / R158 sakila-example fixtures (`FilmsPayload`,
`FilmsServicePayload`, `FilmActorsServicePayload`): each declares a list-shaped data field
with no `@defaultOrder`, the validator must admit them, and their execution-tier tests in
`graphitron-sakila-example` would fail downstream if the marker were removed or the validator
predicate broadened. A dedicated unit case is not added: marker membership is sealed and the
{code}`instanceof OrderingOwnedByProducer` exclusion is a one-line predicate that does not
benefit from leaf-specific unit cases.

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
- Requiring ordering on fields where the visible result order is owned by an upstream
  producer (the carrier-walk and `@service`-method patterns). Permits opt into the
  `OrderingOwnedByProducer` sealed marker; the validator excludes them by type. Permits that
  decline `SqlGeneratingField` entirely (`ServiceRecordField`, `TableMethodField`, the
  carrier-as-returning siblings) are excluded by the cast and need no marker.
- Changing `OrderByResolver` to refuse to produce `OrderBySpec.None`. The resolver's contract
  (total projection over all classified shapes) stays intact; the validator is the right
  layer for "this shape is legal in the model but illegal as authored schema."
- Removing or merging the existing `validatePaginationRequiresOrdering`. The two checks emit
  distinct remediation text and remain useful side by side.
