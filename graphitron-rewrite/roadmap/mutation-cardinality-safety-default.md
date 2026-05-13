---
id: R144
title: "Default DELETE / UPDATE inputs to unique-key cardinality safety; opt out with multiRow:"
status: Ready
bucket: architecture
priority: 6
theme: mutations-errors
depends-on: []
last-updated: 2026-05-13
---

# Default DELETE / UPDATE inputs to unique-key cardinality safety; opt out with multiRow:

The `@lookupKey` directive on `@mutation` `@table` input fields is opt-in: an
author writes it to say "this field is the WHERE filter." A DELETE or UPDATE
schema that forgets the directive is rejected today by `MutationInputResolver`
with a structural error ("requires at least one `@lookupKey` field"), but the
underlying polarity is wrong: the *safe* case (input identifies a unique row,
`|output| = |input|`) is the one requiring extra ceremony, and the *unsafe* case
(input is a non-unique filter, one input row can affect many database rows) is
what the directive's absence would silently enable if any future relaxation of
that gate ever shipped. Opt-in safety is fragile; opt-in danger is robust.

The directive is also doing two unrelated jobs on the mutation side:
(a) producing the `InputColumnBindingGroup` rows that become the WHERE / ON
CONFLICT clause, and (b) partitioning UPDATE / UPSERT input fields into "filter"
vs "value" buckets. These jobs answer different questions ("is this a filter?"
and "is this many rows?") and a single directive name can't carry both meanings
cleanly. The reframe surfaced in conversation is to name the hazard, not the
mechanism: the cardinality knob becomes a new `multiRow:` argument on `@mutation`, announcing
"|output| can exceed |input|." Every input field on a DELETE / UPDATE `@table`
input is a WHERE filter by default; the validator confirms those filters cover
the table's primary key; the SET-partition concern on UPDATE is solved by a
separate, narrow `@value` directive on input fields.

The cardinality framing is the one with safety stakes. The partition concern is
structural plumbing.

## Scope

In scope:

- `@mutation(typeName: DELETE)`: every input field on the `@table` input
  participates in the WHERE clause. The set of contributed columns must cover
  the table's primary key. `multiRow: true` on the `@mutation` directive opts out of the PK
  coverage check.
- `@mutation(typeName: UPDATE)`: every input field on the `@table` input is a
  filter unless it carries `@value`, in which case it's a value to assign in
  the UPDATE statement's SET clause. WHERE-side
  columns must cover the table's primary key. `multiRow: true` opts out of the PK
  coverage check.
- `@mutation(typeName: UPSERT)`: refused at classify time under the new regime.
  Existing UPSERT-using fixtures migrate or retire; R145
  (`mutation-cardinality-safety-upsert`) carries the UPSERT-specific safety
  story (ON CONFLICT requires a unique constraint by definition; the
  `multiRow:` story interacts differently because UPSERT matches at most one
  existing row).
- `@mutation(typeName: INSERT)`: untouched. The R130 `CompositeColumnField ×
  INSERT` carve-out at `MutationInputResolver.java:312-320` is a separate
  missing-emitter argument and stays exactly as it is.

Explicitly out of scope (follow-up R-items file alongside this Spec):

- Unique-index coverage as an alternative to PK coverage. R146
  (`mutation-cardinality-safety-unique-index`) lifts the PK-only conservative
  cut.
- UPSERT under the new regime. R145 reinstates UPSERT-generation with a
  designed cardinality story.
- The Query-side use of `@lookupKey` on `ARGUMENT_DEFINITION` (the
  `LookupTableField` / `SplitLookupTableField` derived-target-table mechanism
  documented in `code-generation-triggers.adoc`). That use is structurally
  distinct from mutation-input partitioning and is untouched. The directive
  retains its `ARGUMENT_DEFINITION` location; only the
  `INPUT_FIELD_DEFINITION` use is retired. Post-Phase-2 sweep, `@lookupKey`
  is effectively single-site (only `ARGUMENT_DEFINITION` admits; the
  `INPUT_FIELD_DEFINITION` location stays declared but rejects in the
  classifier with migration prose — the same retirement idiom
  `@notGenerated` and `@multitableReference` use). The "two uses" framing
  is migration-window only.

## Design

### Directive surface

`@mutation` gains an optional `multiRow: Boolean = false` argument, and a
new `@value` directive lands on `INPUT_FIELD_DEFINITION`:

```
"""
Define a graphitron-generated mutation field.

`typeName`: the DML statement kind (INSERT / UPDATE / DELETE / UPSERT).

`multiRow`: opt-in acknowledgement that this mutation may affect more than
one database row per input row. Required on
`@mutation(typeName: DELETE / UPDATE)` when the input's filter columns do
not cover the underlying `@table`'s primary key. Without `multiRow: true`,
the classifier rejects an under-keyed DELETE / UPDATE at schema-classify
time so a non-unique filter cannot silently broadcast. Rejected on
`typeName: INSERT` (INSERT has no WHERE clause to multiply over) and on
`typeName: UPSERT` (refused under the new regime; see R145, where the
conflict-target's uniqueness is the structural enforcement and a
`multiRow:` analogue is unnecessary).

The argument lives on `@mutation` rather than as a sibling directive
because the cardinality knob is structurally coupled to the verb: there
is no SDL site where multi-row semantics make sense without a `@mutation`
directive present. A sibling directive would require an additional
"reject standalone `multiRow: true` on a non-`@mutation` field" validator
rule; the argument shape cannot be misapplied because there is nowhere
else to put it.
"""
directive @mutation(typeName: MutationType!, multiRow: Boolean = false) on FIELD_DEFINITION

"""
On a `@mutation(typeName: UPDATE)` `@table` input field, marks the field
as a value column to assign rather than a key to filter by. The default
for an input field is to participate in the WHERE clause; `@value` opts
out for UPDATE's assignment columns. Rejected on DELETE inputs (DELETE
has no assignment) and on UPSERT inputs (UPSERT is refused under the new
regime).

Names the author's intent ("this is a value to assign, not a key to
filter by"), not the emitter's SQL clause ("SET"). The verb context
(UPDATE) makes the clause vocabulary unambiguous, so the role-side name
reads cleanly without leaking the underlying statement shape into
authoring vocabulary.
"""
directive @value on INPUT_FIELD_DEFINITION
```

### Retire `@lookupKey` on `INPUT_FIELD_DEFINITION`

The directive declaration keeps the `INPUT_FIELD_DEFINITION` location; the
classifier rejects any remaining `INPUT_FIELD_DEFINITION` use with a
"`@lookupKey` on a mutation input field is no longer supported; remove it
(the field is a filter by default) or replace it with `@value` on UPDATE
value fields" diagnostic. This mirrors the project's established idiom for
retired directives: `@notGenerated` and `@multitableReference`
(`directives.graphqls:6-10`, `:142-149`) both keep their declarations and
reject in the classifier with an author-facing migration message. The
classifier-level rejection carries the migration prose that a parser-level
"unknown directive location" error cannot: parser errors are generic, and
this retirement specifically needs to tell the author what to do instead.

If the migration sweep encounters edge cases where the classifier-level
rejection is too late (e.g. a third-party schema parsing context that
short-circuits before classification), the alternative is to drop
`INPUT_FIELD_DEFINITION` from the declaration so the parser rejects at
schema-load time with a generic "unknown directive location" error. This
trades migration prose for parser-level forcing. Spec lands the
classifier-level rejection; the parser-level removal is documented here as
the more aggressive fallback if surfacing.

### Alternatives considered

Five shapes were considered across the cardinality and partition concerns.
The Spec lands (A); the rest are recorded here so the choices are
defensible at the gate.

(A) **`multiRow:` as a `@mutation` argument; `@value` as a sibling
directive on input fields.** Cardinality opt-out lives on `@mutation`;
UPDATE partition lives per-field. *Chosen.* The cardinality knob is
structurally coupled to the verb that requires it (`multiRow:` has no
SDL site of application without `@mutation`), so the argument shape is
the principles-aligned choice: no orphan-directive rejection rule, no
ambiguity about where the opt-out can appear, mirrors the existing
`@mutation(typeName: ...)` precedent. The partition concern is genuinely
per-input-field and benefits from per-field co-location, so `@value`
stays a sibling directive on `INPUT_FIELD_DEFINITION`.

(B) **Two sibling directives**: `@multiRow` on `FIELD_DEFINITION`
alongside `@value` on `INPUT_FIELD_DEFINITION`. *Rejected.* The
hazard-naming argument for a sibling `@multiRow` is real but thin: a
reviewer who would miss `multiRow: true` would also miss a `@multiRow`
line. The sibling-directive shape adds a structural cost the argument
shape doesn't pay — a validator rule rejecting standalone `@multiRow`
on a non-`@mutation` field — and gains only hypothetical composability
(a non-`@mutation` SDL site that needs the cardinality knob; no such
site exists or is planned).

(C) **Infer the partition from PK membership.** Drop the partition
directive entirely on UPDATE; input fields whose columns are in the
table's PK are filters by default, non-PK columns are values. An
override directive (a narrower `@filter` or similar) is needed only
for the `multiRow:` corner where the author wants to filter on a
non-PK column. *Rejected.* Less ceremony in the headline case but
introduces a silent dependency on the table's PK definition: an author
reading the SDL cannot tell partition without consulting catalog
metadata, and changing the PK silently changes mutation semantics for
every existing UPDATE input on that table. The override-directive
corner case is exactly the `multiRow:` setup, which is the dangerous-
by-author-opt-in shape; routing dangerous shapes through inference
rather than explicit marking concentrates surprise where it hurts most.
Loses the "name the hazard" principle the cardinality reframe is built
on.

(D) **One directive with reversed polarity.** Keep `@lookupKey`, flip
its meaning so the directive marks the value field instead of the
filter field. *Rejected.* The cardinality concern (per-mutation field,
hazard-naming) and the partition concern (per-input-field, role-naming)
are genuinely orthogonal axes; collapsing them onto one directive
recreates today's overloading in a different polarity. The name
`@lookupKey` carries decades of "lookup means filter" connotation in
DB literature; flipping its meaning would be a maintenance footgun for
any downstream reader unfamiliar with R144.

(E) **Partition on the input type rather than per-field**:
`input FilmUpdateInput @table(name: "film") @value(fields: ["title",
"rating"])` instead of `title: String @value`. *Rejected.* Input-type-
level field name lists duplicate the field declarations they reference,
need to stay in sync on every rename, and lose the per-field
co-location that makes the role obvious at the SDL author's site. The
`@field` precedent — per-site role naming — points the other way.

### Resolver flow

`MutationInputResolver.resolveInput`:

1. Drop the `kind.requiresLookupKey() && foundTia.fieldBindings().isEmpty()`
   gate at line 357. It is structurally replaced by the per-verb dispatch
   below.
2. Drop the `CompositeColumnField` shape-driven gate at lines 311-329. The
   bucket question it was solving ("is this a `@lookupKey` field or a SET
   field?") is now answered by the presence or absence of `@value`.
3. For `DmlKind.DELETE`: every `ColumnField` / `CompositeColumnField` in the
   input is a filter. Reject `@value` on any DELETE input field
   ("`@value` is not valid on `@mutation(typeName: DELETE)` inputs; DELETE
   has no assignment clause"). Compute the set of contributed columns
   (`ColumnField.column()` plus `CompositeColumnField.columns()` exploded). If
   the contributed-column set does not cover the table's primary key
   (`JooqCatalog.findPkColumns`), and the mutation field does not carry
   `multiRow: true`, reject with a guidance message naming the missing PK columns
   and pointing at `multiRow: true` as the opt-out.
4. For `DmlKind.UPDATE`: partition input fields by `@value` presence. WHERE-side
   fields run through the same PK-coverage check as DELETE. SET-side fields are
   just listed; the existing "no non-`@lookupKey` fields to set" rejection
   (line 361-362) becomes "no `@value` fields to set." An UPDATE input with
   every field `@value`-marked rejects ("no filter fields; UPDATE without a
   WHERE clause would update every row").
5. For `DmlKind.UPSERT`: reject at classify time with the message "UPSERT is
   not currently supported under the R144 cardinality-safety regime; tracked
   in `mutation-cardinality-safety-upsert` (R145)." The rejection is a
   `Rejection.deferred`
   keyed to the follow-up so `LoadBearingGuaranteeAuditTest`'s rejection-link
   audit can surface the deferral.
6. For `DmlKind.INSERT`: unchanged. No `multiRow:` semantics, no `@value`
   acceptance (INSERT inputs are not partitioned), no PK coverage check.
   Reject `multiRow: true` on the mutation field at classify time ("`multiRow: true`
   is not valid on `@mutation(typeName: INSERT)`; INSERT has no WHERE
   clause").

Two additional structural rejections fire under `multiRow:` to keep the
opt-out from authorising the genuinely-catastrophic shape:

- An empty input type on DELETE / UPDATE (zero `ColumnField` /
  `CompositeColumnField` fields, even with `multiRow: true`): reject with
  "`multiRow: true` does not authorise a `@mutation(typeName: <kind>)` with
  no filter columns at all; this would broadcast across the entire
  table. Add at least one filter field to the input."
- `@condition` on an input field is structurally a filter-narrowing
  concern (the predicate joins the WHERE alongside the column-based
  filters); `@condition`-marked fields contribute to the WHERE but do
  *not* count toward the PK-coverage check, because their predicate is
  opaque to the validator. `@condition` and `@value` on the same input
  field reject as mutually exclusive.

### `TableInputArg.of`: partition source change

The model-side carrier at `ArgumentRef.java:258` derives
`lookupKeyFields` / `setFields` from `fieldBindings` today: a field name
appearing in the binding's `MapBinding.fieldName()` (or
`DecodedRecordGroup.sourceFieldName()`) lands it in `lookupKeyFields`;
everything else lands in `setFields`. Under the polarity flip
`fieldBindings` will cover *every* admissible input field on DELETE /
UPDATE (modulo `@value` exclusion on UPDATE), so `lookupNames` would
contain every field name and `setFields` would always derive empty.
Every downstream emitter that walks `tia.setFields()`
(`TypeFetcherGenerator` at `:1734`, `:1776`, `:1810`, `:1855`, `:1880`,
`:1898`, `:2043`, `:2053`, `:2109`, `:3453`, `:3490`) would then walk an
empty set on UPDATE and emit an UPDATE with no SET clause. This is a
real correctness break, not a cosmetic one. `FieldBuilder.java:1161`
and `LookupMappingResolver.java:86` walk `tia.fieldBindings()`, not
`setFields()`, and are unaffected by the partition-source change: they
consume `fieldBindings` to know which input fields are WHERE-bound (the
former to suppress implicit `@condition` predicates on lookup-bound
fields, the latter to flatten map bindings into `LookupArg.MapInput`
slots). Under the polarity flip `fieldBindings` widens to cover every
admissible DELETE / UPDATE input field (modulo `@value` on UPDATE),
which is exactly the WHERE-bound set both sites need. No new audit-key
wiring is required at those two sites.

`TableInputArg.of` re-derives the partition from `@value` presence on
UPDATE rather than from `fieldBindings` membership. Two viable shapes:

(a) Pass `DmlKind kind` and the set of `@value`-marked field names into
`of(...)`. For UPDATE: `setFields` is exactly the `@value`-marked
fields; `lookupKeyFields` is the complement. For DELETE / INSERT:
`setFields` is empty by classifier guarantee. The factory's signature
widens by two parameters; one callsite (`MutationInputResolver` at
`:260`).

(b) Split into `forDelete(...)` / `forUpdate(...)` / `forInsert(...)`
per-verb factories. UPDATE's factory takes the `@value`-marked names
explicitly; DELETE and INSERT take none. Same caller-side semantics;
clearer call-site reads.

Shape (a) is the conservative choice (single factory, one call site to
update). Shape (b) is the principles-aligned choice (per-verb partition
source is named at the type level rather than threaded through a
parameter). Phase 1 lands (a); a follow-up structural-refactor item can
lift to (b) if the per-verb factories become a recurring pattern (the
same lift R141 noted as `dml-record-carrier-sealed-on-kind`'s
neighbour).

A second producer
`@LoadBearingClassifierCheck("mutation-input.update-set-fields-equal-value-marked")`
pairs with `@DependsOnClassifierCheck` consumers on every
`tia.setFields()` walk listed above. Guarantee: "On `DmlKind.UPDATE`,
`tia.setFields()` is exactly the set of input fields carrying `@value`
(in SDL declaration order). On `DmlKind.DELETE` / `DmlKind.INSERT`,
`tia.setFields()` is empty. Lets each SET-walking emitter trust the
partition source without checking `kind` or directive presence."
`LoadBearingGuaranteeAuditTest` enforces.

`EnumMappingResolver.buildLookupBindings`:

The walk no longer gates on `sdlField.hasAppliedDirective(DIR_LOOKUP_KEY)` (line
297). For DELETE / UPDATE inputs, every input-object field whose classifier
result is a permitted carrier shape (`ColumnField` direct or `NodeIdDecodeKeys`,
`CompositeColumnField`) produces an `InputColumnBindingGroup`, modulo the
UPDATE `@value` exclusion. List-typed fields, reference carriers, and nesting
fields continue to reject as today; the structural rejections at lines 302-303,
334-335, 346-347 keep firing on their existing conditions.

The two R130 `@LoadBearingClassifierCheck` keys
(`mutation-input.lookup-binding-honors-carrier-extraction` and
`mutation-input.lookup-binding-decoded-record-arity-matches-carrier-columns`)
stay; the consumers on lookup-WHERE / row-IN / INSERT-arm emitters are
unaffected by the polarity flip.

### Validator dispatch

`MutationInputResolver` adds a new producer
`@LoadBearingClassifierCheck("mutation-input.where-columns-cover-pk")` paired
with a `@DependsOnClassifierCheck` consumer on the lookup-WHERE emitter
(`buildLookupWhereSingleRow` / `buildBulkLookupRowIn`). The guarantee
description reads: "On DELETE / UPDATE without `multiRow: true`, the union of
contributed filter columns (`ColumnField.column()` and
`CompositeColumnField.columns()`) covers the input `@table`'s primary key. Lets
the lookup-WHERE emitter assume `WHERE` matches at most one row per input row."
`LoadBearingGuaranteeAuditTest` enforces the producer / consumer wiring.

The partition disjointness invariant (UPDATE's `@value`-marked fields do not
also contribute to the WHERE-column set) holds structurally by directive
position rather than by classifier check, so it does not warrant a
`@LoadBearingClassifierCheck` annotation. Wearing the annotation on
parser-level invariants dilutes the audit signal for genuine
classifier-enforced guarantees. The invariant is documented in a comment on
the `@value` directive declaration and at the emitter sites that read both
partitions.

## Migration

The enumeration below distinguishes *edit-site* files (Phase 1 source
edits) from *fixture-migration* files (Phase 2 SDL rewrites). Counts
are the grep co-occurrence of `@mutation` ∩ `@lookupKey`; the
narrow-scope migration set is `@lookupKey` *on `INPUT_FIELD_DEFINITION`
of a `@mutation` field's `@table` input* (a subset, separated below).
`@lookupKey` on `ARGUMENT_DEFINITION` (Query-side derived-target-table
mechanism) is in-scope-untouched and is *not* migrated.

**Phase 1 edit sites (source code, not SDL):**

- `graphitron/src/main/resources/no/sikt/graphitron/rewrite/schema/directives.graphqls`
  — directive declarations (extend `@mutation` with the optional
  `multiRow:` argument; add `@value`; `@lookupKey` declaration
  unchanged).
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/MutationInputResolver.java`
  — resolver flow (drop gates, add per-verb dispatch, add audit-key
  producers).
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/EnumMappingResolver.java`
  — `buildLookupBindings` walk (drop `DIR_LOOKUP_KEY` gate, walk every
  admissible input field on DELETE / UPDATE, exclude `@value`-marked on
  UPDATE).
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/ArgumentRef.java`
  — `TableInputArg.of` partition source (shape (a) per §Resolver flow).
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java`
  — `MutationBulkDmlRecordField` construction site annotation (Phase 1
  per §Roadmap entries → Follows R141). No migration of the
  `DIR_LOOKUP_KEY` constant reference at `:2754`: that arm reads
  `ARGUMENT_DEFINITION`-scope, which the Spec leaves untouched.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/model/DmlKind.java`
  — Javadoc reference update.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java`
  — Javadoc reference update; new `@DependsOnClassifierCheck` consumers
  on every `tia.setFields()` walk site (see §TableInputArg.of).

**Phase 2 fixture-migration set (SDL rewrites):**

Per-file counts of `@lookupKey` on `INPUT_FIELD_DEFINITION` within a
`@mutation` field's `@table` input. The enumeration below is the
pre-Phase-2 worklist; the implementer's first action in Phase 2 is to
verify these counts against a fresh grep of the rebased tree (R141 has
landed; subsequent landings may add or rename fixtures).

- `graphitron-sakila-example/src/main/resources/graphql/schema.graphqls`
  — sakila example schema. Notable: `FilmUpdateInput.filmId @lookupKey`
  (drop directive); R141-introduced `createFilmsPayload` /
  `updateFilmsPayload` and any input fields they reference.
- `graphitron/src/test/java/no/sikt/graphitron/rewrite/GraphitronSchemaBuilderTest.java`
  — classifier truth-table SDL strings. Multiple `@mutation` cases;
  individual rows enumerated in Phase 3 (admission and rejection
  retypes), each row that uses `@lookupKey` on an input-object field
  becomes a no-directive (filter-by-default) test fixture.
- `graphitron/src/test/java/no/sikt/graphitron/rewrite/MutationDmlNodeIdClassificationTest.java`
  — R130 / R131 pipeline tests; the
  `LOOKUP_KEY_ON_NODEID_INPUT_FIELD_ADMITTED{,_COMPOSITE_PK}` rows
  rebase onto the new regime.
- `graphitron/src/test/java/no/sikt/graphitron/rewrite/SingleRecordCarrierPipelineTest.java`
  — R75 pipeline tests; UPDATE fixtures using `@lookupKey` on
  `filmId`.
- `graphitron/src/test/java/no/sikt/graphitron/rewrite/generators/FetcherPipelineTest.java`
  — DML emit fixtures.
- `docs/code-generation-triggers.adoc` — doc prose reference. Out of
  Phase 2's strict scope (docs ship through the same trunk but
  carry-over is mechanical).

Files that *contain* `@lookupKey` somewhere but do not migrate (the
`@lookupKey` use is on `ARGUMENT_DEFINITION`, untouched): roughly 30
additional files in the rewrite tree, dominated by Query-side
`LookupTableField` / `SplitLookupTableField` pipeline tests
(`LookupTableFieldPipelineTest`, `SplitTableFieldPipelineTest`,
`ValuesJoinRowBuilderTest`, etc.). The migration sweep does not touch
these; the directive declaration keeps `ARGUMENT_DEFINITION`.

Each `@lookupKey` use on `INPUT_FIELD_DEFINITION` migrates to one of:

- *DELETE / UPDATE filter field*: drop the directive entirely. The field
  defaults to filter.
- *UPDATE value field*: this case doesn't exist today (today's directive
  marks the *filter*, not the value); during migration any test that
  intentionally wanted assignment behaviour needs the new `@value` directive.
  The implementer audits each migrated UPDATE input on a per-fixture basis.
- *UPSERT input field*: the surrounding mutation rejects under the new
  regime; the fixture either migrates to an UPDATE + INSERT pair or retires
  entirely.

`@mutation(typeName: UPSERT)` appears at 21 sites across 4 fixture files
(`MutationDmlNodeIdClassificationTest`, `GraphitronSchemaBuilderTest`,
`FetcherPipelineTest`, `sakila-example/schema.graphqls`). Pre-Phase-2
implementation walks the list and records the migrate-or-retire call per
site. Execution-tier UPSERT proofs in `SingleRecordCarrierDmlTest` and the
`UPSERT_MUTATION_FIELD` case in
`GraphitronSchemaBuilderTest:5095-5105` are the largest concentrations.

## Implementation phases

Phase 1 — directive declarations and classifier admission.

- Extend `@mutation` to take the new `multiRow: Boolean = false` argument
  and add the new `@value on INPUT_FIELD_DEFINITION` directive in
  `graphitron/src/main/resources/no/sikt/graphitron/rewrite/schema/directives.graphqls`.
  Leave `@lookupKey`'s `INPUT_FIELD_DEFINITION` location declared so the
  classifier can carry the retirement-prose rejection (`@notGenerated` /
  `@multitableReference` idiom).
- Extend `MutationInputResolver.resolveInput` per the resolver-flow section
  above: drop the two retired gates, add the per-verb dispatch, add the new
  PK-coverage check with the `multiRow:` opt-out, add the UPSERT rejection arm,
  add the structural rejections for `@value`-on-DELETE / `multiRow:`-on-INSERT
  / empty-input-with-`multiRow:` / `@condition`-co-occurring-with-`@value`.
- Rework `TableInputArg.of` (`ArgumentRef.java:258`) to take `DmlKind` and
  the `@value`-marked field names as additional parameters. UPDATE's
  partition reads from the names; DELETE / INSERT pass empty. Update the
  single call site in `MutationInputResolver` at `:260`.
- Replace `EnumMappingResolver.buildLookupBindings`'s `DIR_LOOKUP_KEY` gate
  with the "for DELETE/UPDATE inputs, walk every admissible input field;
  exclude `@value`-marked fields on UPDATE" walk.
- Add two new `@LoadBearingClassifierCheck` producers
  (`mutation-input.where-columns-cover-pk`,
  `mutation-input.update-set-fields-equal-value-marked`); wire the matching
  `@DependsOnClassifierCheck` consumers — the first on the lookup-WHERE /
  row-IN emitters (`buildLookupWhereSingleRow`,
  `buildBulkLookupRowIn`), the second on every `tia.setFields()` walk
  site listed in §Migration.
- Add `@DependsOnClassifierCheck("mutation-input.where-columns-cover-pk")`
  on `MutationBulkDmlRecordField`'s construction site so the
  load-bearing assumption that the bulk path always traverses
  `MutationInputResolver.resolveInput` is type-system-enforced.
- UPSERT rejection key wired to R145's slug
  (`mutation-cardinality-safety-upsert`), already filed alongside this Spec.
- Add classifier-level rejection for `@lookupKey` on `INPUT_FIELD_DEFINITION`
  with the documented migration prose. The directive declaration keeps
  the location; the classifier carries the diagnostic. Mirrors the
  `@notGenerated` / `@multitableReference` retirement idiom.

Phase 2 — fixture migration.

- Sakila example schema: every `@mutation(typeName: DELETE / UPDATE)` input
  drops `@lookupKey` from filter fields; any UPDATE input that needs SET
  semantics gets `@value` added. Every `@mutation(typeName: UPSERT)` migrates
  or retires per the working-set call.
- All test fixtures using `@lookupKey` on input fields rewrite. Tests pinning
  the "no `@lookupKey` → UnclassifiedField" rejection
  (`GraphitronSchemaBuilderTest.DELETE_MUTATION_MISSING_LOOKUP_KEY` at line
  5078-5093) retype to pin the new PK-coverage-or-`multiRow: true` rejection.
- `MutationDmlNodeIdClassificationTest` cases that pin `CompositeColumnField`
  rejection outside `@lookupKey` position (R130 Phase 2) retype to admission
  cases; the rejection is gone.

Phase 3 — new test coverage.

- Pipeline-tier cases on `MutationInputResolver` covering:
  - DELETE with PK-covering filter input → admit.
  - DELETE without PK-covering filter, no `multiRow: true` → reject with the new
    diagnostic.
  - DELETE without PK-covering filter, with `multiRow: true` → admit.
  - DELETE with `multiRow: true` and a *single non-PK* filter column → admit
    (acknowledged broadcast; this is the headline `multiRow:` use case).
  - DELETE / UPDATE on an empty `@table` input with `multiRow: true` → reject
    ("no filter columns to broadcast").
  - UPDATE with WHERE-PK-covered filter + at least one `@value` field → admit.
  - UPDATE without WHERE-PK-cover, no `multiRow: true` → reject.
  - UPDATE without WHERE-PK-cover, with `multiRow: true` → admit.
  - UPDATE without `@value` fields → reject ("no fields to set").
  - UPDATE with every field `@value`-marked → reject ("no filter fields").
  - DELETE with `@value` on any field → reject ("`@value` not valid on DELETE").
  - INSERT with `multiRow: true` on the mutation field → reject ("`multiRow: true`
    not valid on INSERT").
  - Input field with both `@condition` and `@value` → reject (mutually
    exclusive).
  - UPSERT (any shape) → reject with R145 deferral message.
  - `@lookupKey` on a mutation input field → reject with migration prose
    (the retirement diagnostic).
  - INSERT unchanged: existing INSERT tests pass without modification.
- Execution-tier proofs in `DmlBulkMutationsExecutionTest`: at least one DELETE
  with `multiRow: true` that affects multiple rows per input row, asserting the SQL
  runs and the result count exceeds input count. Mirrors the R130 end-to-end
  coverage shape.

## Tests

- **L1 (model).** No new variant; `DmlKind` stays as-is.
- **L3 (validator).** New parameterised case in
  `GraphitronSchemaBuilderTest.MutationCardinalitySafetyCase` covering the
  fifteen enumerated rows in Phase 3 (admission and rejection across DELETE
  / UPDATE / INSERT verbs; structural rejections for `@value`-on-DELETE,
  no-SET-on-UPDATE, every-field-`@value`-on-UPDATE, `multiRow:`-on-INSERT,
  empty-input-with-`multiRow:`, `@condition`-with-`@value`, UPSERT,
  `@lookupKey` on input field).
- **L4 (pipeline).** `MutationDmlNodeIdClassificationTest` updates as
  described in Phase 2; new cases for `multiRow:` admission on
  composite-PK-decoded inputs (the R130 sakila reproducer `slettRegelverksamling`
  shape becomes the canonical admission case for the new regime, *without*
  `@lookupKey`).
- **L5 (compile spec).** `graphitron-sakila-example` schema migration; `mvn -f
  graphitron-rewrite/pom.xml install -Plocal-db` passes end-to-end after
  migration.
- **L6 (execution).** `DmlBulkMutationsExecutionTest` retains its R130 DELETE
  fixtures (renaming any that drop `@lookupKey`); add at least one `multiRow: true`
  DELETE end-to-end proof asserting `|affected rows| > |input rows|`.

## Acceptance criteria

- `@mutation` directive declaration gains optional `multiRow: Boolean = false`
  argument; `@value on INPUT_FIELD_DEFINITION` directive declared.
  `@lookupKey`'s declaration unchanged; the classifier rejects any
  `INPUT_FIELD_DEFINITION` use with the documented migration diagnostic.
- `MutationInputResolver` admits DELETE / UPDATE inputs without `@lookupKey`
  on their fields; rejects DELETE / UPDATE inputs whose filter columns don't
  cover the PK when `multiRow: true` is absent.
- `MutationInputResolver` rejects every `@mutation(typeName: UPSERT)` field
  with a `Rejection.deferred` keyed to `mutation-cardinality-safety-upsert`
  (R145).
- `MutationInputResolver` rejects `@value` on DELETE / INSERT / UPSERT inputs;
  rejects `multiRow: true` on INSERT.
- `EnumMappingResolver.buildLookupBindings` produces bindings for every
  admissible input field on DELETE / UPDATE inputs (modulo `@value` on UPDATE),
  regardless of `@lookupKey` presence.
- Two new `@LoadBearingClassifierCheck` keys
  (`mutation-input.where-columns-cover-pk`,
  `mutation-input.update-set-fields-equal-value-marked`) wired into
  `LoadBearingGuaranteeAuditTest`'s pass-through with the
  `@DependsOnClassifierCheck` consumers named in Phase 1.
- `TableInputArg.of` re-derives `lookupKeyFields` / `setFields` from
  `@value` presence on UPDATE (and is empty by classifier guarantee on
  DELETE / INSERT), not from `fieldBindings` membership. Every existing
  `tia.setFields()` walk site (the eleven listed in §Migration) reads
  the new partition source unchanged at the call site.
- Every `@lookupKey` use on `INPUT_FIELD_DEFINITION` and every
  `@mutation(typeName: UPSERT)` use in the rewrite tree migrates per
  §Migration. Enforcement is by build failure: the classifier's
  retirement and UPSERT rejections fire on any remaining use; the full
  `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` is the
  verification mechanism. Phase 2 is complete when the full build
  passes with the Phase 1 source edits in place.
- New pipeline-tier cases cover the fifteen enumerated DELETE / UPDATE / INSERT admission
  and rejection rows above; execution-tier coverage includes at least one
  `multiRow: true` DELETE end-to-end against a real PostgreSQL via Testcontainers.
- Full `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` passes on Java 25.

## Roadmap entries (siblings / dependencies / follow-ups)

- **Lifts the R130 carve-out partially.** R130 admitted `CompositeColumnField`
  in `@lookupKey` position on lookup-bearing verbs and rejected it outside
  `@lookupKey` position. R144 retires that gate entirely: the bucket question
  ("filter or value?") is now answered by `@value` presence, not by directive
  position on the filter field. R130's INSERT carve-out and its
  reference-carrier deferral (keyed to R24's
  `nodeidreferencefield-join-projection-form`) stay exactly as they are.
- **Follows R141** (`bulk-input-single-carrier-list-data-field`, shipped;
  see `changelog.md`). R141 landed the bulk-input DML carrier
  (`MutationBulkDmlRecordField`) scoped to `{INSERT, UPDATE}`, with a
  compact-constructor rejection on `DmlKind.UPSERT` deferring the bulk
  UPSERT path to R145 in coordination with this Spec's upstream UPSERT
  refusal. Two implications for R144's implementation:
  - R144's `multiRow:` semantics apply to both `MutationDmlRecordField`
    (singleton) and `MutationBulkDmlRecordField` (bulk) without
    additional code. The WHERE-coverage check fires on
    `MutationInputResolver.resolveInput`, which both carriers feed
    through identically (verified: `FieldBuilder.java:2638` singleton
    dispatch and `:2705` bulk construction both run after
    `resolveInput`). R141's per-row emit loop reuses the same
    lookup-WHERE builder; the filter-column source is
    `tableInputArg.fieldBindings()` regardless of carrier shape. To
    make this load-bearing rather than prose, Phase 1 annotates
    `MutationBulkDmlRecordField`'s construction site with
    `@DependsOnClassifierCheck("mutation-input.where-columns-cover-pk")`
    alongside the lookup-WHERE emitter's consumer. Any future refactor
    that branches the bulk path around `resolveInput` (e.g. a bulk-
    specific classifier) surfaces as an orphaned consumer in
    `LoadBearingGuaranteeAuditTest`.
  - R141's compact-constructor rejection of `DmlKind.UPSERT` becomes
    *dead code* during the R144-shipped-but-R145-not-yet window:
    R144's upstream `MutationInputResolver` UPSERT rejection always
    fires first, so the bulk carrier's compact constructor never sees
    a UPSERT call. The rejection stays as a type-system backstop and
    lifts simultaneously with R144's upstream rejection when R145
    ships. Worth surfacing here so a reviewer of the bulk-carrier
    code post-R144 doesn't wonder why the rejection exists.
  - R141 added `createFilmsPayload` and `updateFilmsPayload` fixtures
    in `graphitron-sakila-example/src/main/resources/graphql/schema.graphqls`,
    and three execution tests in `DmlBulkMutationsExecutionTest`
    (`bulkInsertWithThreeRowsInNonPkOrderPreservesInputOrderInResponse`,
    `bulkInsertWithSingleRowExercisesBulkLeafPath`,
    `bulkUpdateWithThreeRowsInNonPkOrderPreservesInputOrderInResponse`).
    R141 shipped against the pre-R144 regime, so any `@lookupKey`-using
    input field on these fixtures migrates with the rest of R144's
    Phase 2 sweep. Add these fixtures to the migration enumeration.
- **R145 (`mutation-cardinality-safety-upsert`)**, Backlog. Cardinality
  safety for UPSERT. UPSERT is refused under R144; R145 designs the
  cardinality story (ON CONFLICT requires a unique constraint by
  definition; `multiRow: true` interacts differently; `@value`-partition
  extends naturally). R145's landing lifts UPSERT rejections at two
  sites in one pass: R144's upstream `MutationInputResolver`
  refusal *and* R141's compact-constructor rejection on
  `MutationBulkDmlRecordField`.
- **R146 (`mutation-cardinality-safety-unique-index`)**, Backlog. Filed
  alongside this Spec. Unique-index coverage as an alternative to PK.
  R144's PK-only is the conservative cut; R146 generalises to
  PK-or-unique-index, requires `JooqCatalog` unique-index exposure
  and emitter awareness of which key is matched.
- **Independent from R98** (`multi-source-input-validation.md`). R98 is about
  validation surfaces (CHECK + Jakarta + directives → one rendered schema);
  R144 is about cardinality safety on the DML-WHERE side. Shared theme, no
  implementation dependency.
- **Third pass on the mutation-input classifier.** R130 widened admission
  (carrier-shape map); R131 collapsed the same-table-NodeId routing; R144
  inverts the safety polarity. The first pass that touches polarity rather
  than carrier-shape admission.
