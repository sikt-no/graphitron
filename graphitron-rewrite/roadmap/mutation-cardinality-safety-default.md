---
id: R144
title: "Default DELETE / UPDATE inputs to unique-key cardinality safety; opt out with @multiRow"
status: Spec
bucket: architecture
priority: 6
theme: mutations-errors
depends-on: []
---

# Default DELETE / UPDATE inputs to unique-key cardinality safety; opt out with @multiRow

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
mechanism: the directive becomes `@multiRow` on the mutation field, announcing
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
  the table's primary key. `@multiRow` on the mutation field opts out of the PK
  coverage check.
- `@mutation(typeName: UPDATE)`: every input field on the `@table` input is a
  filter unless it carries `@value`, in which case it's a value to assign in
  the UPDATE statement's SET clause. WHERE-side
  columns must cover the table's primary key. `@multiRow` opts out of the PK
  coverage check.
- `@mutation(typeName: UPSERT)`: refused at classify time under the new regime.
  Existing UPSERT-using fixtures migrate or retire; R145
  (`mutation-cardinality-safety-upsert`) carries the UPSERT-specific safety
  story (ON CONFLICT requires a unique constraint by definition; the
  `@multiRow` story interacts differently because UPSERT matches at most one
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
  `INPUT_FIELD_DEFINITION` use is retired. Directive-name overlap across
  SDL sites is the established pattern (see `@field` on `FIELD_DEFINITION` vs
  `ARGUMENT_DEFINITION` vs `INPUT_FIELD_DEFINITION` vs `ENUM_VALUE` — same
  directive, site disambiguates the axis); the two `@lookupKey` uses parallel
  that shape rather than violating it.

## Design

### New directives

```
"""
Asserts that this mutation may affect more than one database row per input row.
Required on @mutation(typeName: DELETE / UPDATE) when the input's filter
columns do not cover the underlying @table's primary key. Without it, the
classifier rejects an under-keyed DELETE / UPDATE at schema-classify time so a
non-unique filter cannot silently broadcast.
"""
directive @multiRow on FIELD_DEFINITION

"""
On a @mutation(typeName: UPDATE) @table input field, marks the field as a
value column to assign rather than a key to filter by. The default for an
input field is to participate in the WHERE clause; @value opts out for
UPDATE's assignment columns. Rejected on DELETE inputs (DELETE has no
assignment) and on UPSERT inputs (UPSERT is refused under the new regime).

Names the author's intent ("this is a value to assign, not a key to filter
by"), not the emitter's SQL clause ("SET"). The verb context (UPDATE) makes
the clause vocabulary unambiguous, so the role-side name reads cleanly
without leaking the underlying statement shape into authoring vocabulary.
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

Three shapes were considered for the partition concern (UPDATE: which fields
are filters vs which are values). The Spec lands (A); (B) and (C) are recorded
here so the choice is defensible at the gate.

(A) **Two directives, as drafted.** `@multiRow` on the mutation field for the
cardinality concern; `@value` on input fields for the partition concern.
Author writes both decisions explicitly. *Chosen.* Most predictable: every
UPDATE input fixture says out loud which fields are filters and which are
values; reviewer can read partition off the SDL without consulting the
table's PK definition. Costs one directive on every UPDATE value field.

(B) **Infer the partition from PK membership.** Drop the partition directive
entirely on UPDATE; input fields whose columns are in the table's PK are
filters by default, non-PK columns are values. An override directive (a
narrower `@filter` or similar) is needed only for the `@multiRow` corner where
the author wants to filter on a non-PK column. *Rejected.* Less ceremony in
the headline case but introduces a silent dependency on the table's PK
definition: an author reading the SDL cannot tell partition without consulting
catalog metadata, and changing the PK silently changes mutation semantics for
every existing UPDATE input on that table. The override-directive corner case
is exactly the `@multiRow` setup, which is the dangerous-by-author-opt-in
shape; routing dangerous shapes through inference rather than explicit
marking concentrates surprise where it hurts most. Loses the "name the hazard"
principle the cardinality reframe is built on.

(C) **One directive with reversed polarity.** Keep `@lookupKey`, flip its
meaning so the directive marks the value field instead of the filter field.
*Rejected.* The cardinality concern (per-mutation field, hazard-naming) and
the partition concern (per-input-field, role-naming) are genuinely orthogonal
axes; collapsing them onto one directive recreates today's overloading in a
different polarity. The name `@lookupKey` carries decades of "lookup means
filter" connotation in DB literature; flipping its meaning would be a
maintenance footgun for any downstream reader unfamiliar with R144.

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
   `@multiRow`, reject with a guidance message naming the missing PK columns
   and pointing at `@multiRow` as the opt-out.
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
6. For `DmlKind.INSERT`: unchanged. No `@multiRow` semantics, no `@value`
   acceptance (INSERT inputs are not partitioned), no PK coverage check.

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
description reads: "On DELETE / UPDATE without `@multiRow`, the union of
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

`@lookupKey` co-occurs with `@mutation` in 11 files across the rewrite tree
(grep `@mutation` ∩ `@lookupKey`, excluding `roadmap/` and `docs/`):

- `graphitron-sakila-example/src/main/resources/graphql/schema.graphqls`
- `graphitron/src/main/resources/no/sikt/graphitron/rewrite/schema/directives.graphqls`
  (declaration; updated by Phase 1)
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/MutationInputResolver.java`
  (classifier; updated by Phase 1)
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java`
  (constant `DIR_LOOKUP_KEY` reference; may not need migration)
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/model/DmlKind.java`
  (Javadoc reference; update text)
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java`
  (Javadoc reference; update text)
- `graphitron/src/test/java/no/sikt/graphitron/rewrite/GraphitronSchemaBuilderTest.java`
  (every `@mutation` case using `@lookupKey` on `INPUT_FIELD_DEFINITION`)
- `graphitron/src/test/java/no/sikt/graphitron/rewrite/MutationDmlNodeIdClassificationTest.java`
- `graphitron/src/test/java/no/sikt/graphitron/rewrite/SingleRecordCarrierPipelineTest.java`
- `graphitron/src/test/java/no/sikt/graphitron/rewrite/generators/FetcherPipelineTest.java`
- `docs/code-generation-triggers.adoc` (doc reference; update prose; out of
  Phase 2's strict scope but worth carrying)

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

- Add `@multiRow` and `@value` to
  `graphitron/src/main/resources/no/sikt/graphitron/rewrite/schema/directives.graphqls`.
  Remove `INPUT_FIELD_DEFINITION` from `@lookupKey`'s location list.
- Extend `MutationInputResolver.resolveInput` per the resolver-flow section
  above: drop the two retired gates, add the per-verb dispatch, add the new
  PK-coverage check with `@multiRow` opt-out, add the UPSERT rejection arm,
  add the structural rejections for `@value`-on-DELETE / `@multiRow`-on-INSERT.
- Replace `EnumMappingResolver.buildLookupBindings`'s `DIR_LOOKUP_KEY` gate
  with the "for DELETE/UPDATE inputs, walk every admissible input field;
  exclude `@value`-marked fields on UPDATE" walk.
- Add the new `@LoadBearingClassifierCheck` producer
  (`mutation-input.where-columns-cover-pk`); wire the matching
  `@DependsOnClassifierCheck` consumer on the lookup-WHERE / row-IN emitters.
- UPSERT rejection key wired to R145's slug
  (`mutation-cardinality-safety-upsert`), already filed alongside this Spec.

Phase 2 — fixture migration.

- Sakila example schema: every `@mutation(typeName: DELETE / UPDATE)` input
  drops `@lookupKey` from filter fields; any UPDATE input that needs SET
  semantics gets `@value` added. Every `@mutation(typeName: UPSERT)` migrates
  or retires per the working-set call.
- All test fixtures using `@lookupKey` on input fields rewrite. Tests pinning
  the "no `@lookupKey` → UnclassifiedField" rejection
  (`GraphitronSchemaBuilderTest.DELETE_MUTATION_MISSING_LOOKUP_KEY` at line
  5078-5093) retype to pin the new PK-coverage-or-`@multiRow` rejection.
- `MutationDmlNodeIdClassificationTest` cases that pin `CompositeColumnField`
  rejection outside `@lookupKey` position (R130 Phase 2) retype to admission
  cases; the rejection is gone.

Phase 3 — new test coverage.

- Pipeline-tier cases on `MutationInputResolver` covering:
  - DELETE with PK-covering filter input → admit.
  - DELETE without PK-covering filter, no `@multiRow` → reject with the new
    diagnostic.
  - DELETE without PK-covering filter, with `@multiRow` → admit.
  - UPDATE with WHERE-PK-covered filter + at least one `@value` field → admit.
  - UPDATE without WHERE-PK-cover, no `@multiRow` → reject.
  - UPDATE without WHERE-PK-cover, with `@multiRow` → admit.
  - UPDATE without `@value` fields → reject ("no fields to set").
  - UPDATE with every field `@value`-marked → reject ("no filter fields").
  - DELETE with `@value` on any field → reject ("`@value` not valid on DELETE").
  - UPSERT (any shape) → reject with R145 deferral message.
  - INSERT unchanged: existing tests pass without modification.
- Execution-tier proofs in `DmlBulkMutationsExecutionTest`: at least one DELETE
  with `@multiRow` that affects multiple rows per input row, asserting the SQL
  runs and the result count exceeds input count. Mirrors the R130 end-to-end
  coverage shape.

## Tests

- **L1 (model).** No new variant; `DmlKind` stays as-is.
- **L3 (validator).** New parameterised case in
  `GraphitronSchemaBuilderTest.MutationCardinalitySafetyCase` covering the eleven
  enumerated cases above (seven DELETE / UPDATE admission + rejection rows, plus
  the four structural rejections for `@value`-on-DELETE, no-SET-on-UPDATE,
  every-field-`@value`-on-UPDATE, UPSERT).
- **L4 (pipeline).** `MutationDmlNodeIdClassificationTest` updates as
  described in Phase 2; new cases for `@multiRow` admission on
  composite-PK-decoded inputs (the R130 sakila reproducer `slettRegelverksamling`
  shape becomes the canonical admission case for the new regime, *without*
  `@lookupKey`).
- **L5 (compile spec).** `graphitron-sakila-example` schema migration; `mvn -f
  graphitron-rewrite/pom.xml install -Plocal-db` passes end-to-end after
  migration.
- **L6 (execution).** `DmlBulkMutationsExecutionTest` retains its R130 DELETE
  fixtures (renaming any that drop `@lookupKey`); add at least one `@multiRow`
  DELETE end-to-end proof asserting `|affected rows| > |input rows|`.

## Acceptance criteria

- `@multiRow` declared on `FIELD_DEFINITION`; `@value` declared on
  `INPUT_FIELD_DEFINITION`. `@lookupKey`'s declaration drops
  `INPUT_FIELD_DEFINITION` (or, fallback, the classifier rejects the use with
  the documented migration diagnostic).
- `MutationInputResolver` admits DELETE / UPDATE inputs without `@lookupKey`
  on their fields; rejects DELETE / UPDATE inputs whose filter columns don't
  cover the PK when `@multiRow` is absent.
- `MutationInputResolver` rejects every `@mutation(typeName: UPSERT)` field
  with a `Rejection.deferred` keyed to `mutation-cardinality-safety-upsert`
  (R145).
- `MutationInputResolver` rejects `@value` on DELETE / INSERT / UPSERT inputs;
  rejects `@multiRow` on INSERT.
- `EnumMappingResolver.buildLookupBindings` produces bindings for every
  admissible input field on DELETE / UPDATE inputs (modulo `@value` on UPDATE),
  regardless of `@lookupKey` presence.
- One new `@LoadBearingClassifierCheck` key
  (`mutation-input.where-columns-cover-pk`) wired into
  `LoadBearingGuaranteeAuditTest`'s pass-through.
- Every `@lookupKey` use on `INPUT_FIELD_DEFINITION` in the rewrite tree is
  removed. Every `@mutation(typeName: UPSERT)` use is removed or carved out.
- New pipeline-tier cases cover the eleven enumerated DELETE / UPDATE admission
  and rejection rows above; execution-tier coverage includes at least one
  `@multiRow` DELETE end-to-end.
- Full `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` passes on Java 25.

## Roadmap entries (siblings / dependencies / follow-ups)

- **Lifts the R130 carve-out partially.** R130 admitted `CompositeColumnField`
  in `@lookupKey` position on lookup-bearing verbs and rejected it outside
  `@lookupKey` position. R144 retires that gate entirely: the bucket question
  ("filter or value?") is now answered by `@value` presence, not by directive
  position on the filter field. R130's INSERT carve-out and its
  reference-carrier deferral (keyed to R24's
  `nodeidreferencefield-join-projection-form`) stay exactly as they are.
- **R146 (`mutation-cardinality-safety-unique-index`)**, filed alongside this
  Spec. Unique-index coverage as an alternative to PK. R144's PK-only is the
  conservative cut; R146 generalises to PK-or-unique-index, requires
  `JooqCatalog` unique-index exposure and emitter awareness of which key is
  matched.
- **R145 (`mutation-cardinality-safety-upsert`)**, filed alongside this Spec.
  Cardinality safety for UPSERT. UPSERT is refused under R144; R145 designs
  the cardinality story (ON CONFLICT requires a unique constraint by
  definition; `@multiRow` interacts differently; `@value`-partition extends
  naturally).
- **Independent from R98** (`multi-source-input-validation.md`). R98 is about
  validation surfaces (CHECK + Jakarta + directives → one rendered schema);
  R144 is about cardinality safety on the DML-WHERE side. Shared theme, no
  implementation dependency.
- **Third pass on the mutation-input classifier.** R130 widened admission
  (carrier-shape map); R131 collapsed the same-table-NodeId routing; R144
  inverts the safety polarity. The first pass that touches polarity rather
  than carrier-shape admission.
