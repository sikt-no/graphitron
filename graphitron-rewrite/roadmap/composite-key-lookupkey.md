---
id: R5
title: "Composite-key `@lookupKey` on list-of-input-object arguments"
status: Spec
bucket: architecture
priority: 4
theme: model-cleanup
depends-on: []
---

# Composite-key `@lookupKey` on list-of-input-object arguments

The Backlog one-liner ("add `ArgumentRef.CompositeLookupArg` carrying `(input-field-name, target-column)` pairs") is stale. The composite-key path on list-of-input-object lookup arguments is in fact already running end-to-end:

- `LookupMapping.ColumnMapping.LookupArg.MapInput` (`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/model/LookupMapping.java:111-121`) carries `List<InputColumnBinding.MapBinding>` with the `(fieldName, targetColumn)` pairs the one-liner asked for; the comment on line 111 already marks it as "R5's `@lookupKey` on input-object fields".
- `LookupMappingResolver.resolve` (`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/LookupMappingResolver.java:77-82`) projects `ArgumentRef.InputTypeArg.TableInputArg` to `MapInput` whenever `tia.fieldBindings()` is non-empty.
- `LookupValuesJoinEmitter.flattenSlots` / `buildInputRowsMethod` (`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/LookupValuesJoinEmitter.java:127-130`, `:183-207`) emit a typed `Row<N+1>[]` with one cell per binding and a USING/JOIN over the slot columns; `ValuesJoinRowBuilder.MAX_ARITY = 22` caps composite arity at 21.
- `EnumMappingResolver.buildLookupBindings` (`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/EnumMappingResolver.java:242`) walks input fields, resolves each `@field(name:)` to a `ColumnRef`, and returns the `MapBinding` list the resolver consumes.
- `@lookupKey` on a *list-typed* input field (an inner-list, e.g. `filmIds: [Int!] @lookupKey`) is already rejected at `EnumMappingResolver.java:259-264` with the "move list cardinality to the outer argument" diagnostic, covered by `GraphitronSchemaBuilderTest.DML_LIST_LOOKUP_KEY_FIELD_REJECTED` (`:5239`).
- `FieldBuilder.classifyTableFieldComponents` (`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java:984-991`) already rejects "lookup field has no `@lookupKey`-resolving argument" at classifier time with `Rejection.structural("@lookupKey is declared but no argument resolved to a lookup column")`, so a `LookupTableField` (or `QueryLookupTableField`) never reaches the validator with `lookupMapping.args()` empty.

Pipeline-tier coverage exists at `LookupTableFieldPipelineTest.compositeKeyInputType_producesSwitchArmAndInputRowsHelper` (`graphitron-rewrite/graphitron/src/test/java/no/sikt/graphitron/rewrite/LookupTableFieldPipelineTest.java:90`); schema-classifier coverage at `GraphitronSchemaBuilderTest.LOOKUP_FIELD_COMPOSITE_KEY_INPUT_TYPE_ARG` (`:4440-4465`). Both pin the two-binding case (`FilmActorKey { filmId @field(name: "film_id") @lookupKey, actorId @field(name: "actor_id") @lookupKey }`) on `[FilmActorKey!]!`.

Treat R5 as a cleanup-and-hardening pass over the already-shipped shape rather than a fresh feature build. The work in this spec is to (1) lock in the type-level invariants that the resolver and emitter already silently rely on, (2) annotate the classifier→emitter contract with `@LoadBearingClassifierCheck` / `@DependsOnClassifierCheck` pairs so the `LoadBearingGuaranteeAuditTest` mirror enforces it, (3) add the missing execution-tier test, and (4) update the in-code R5 comment and changelog entry to reflect the actual delivered shape.

## Decisions

- The shipped `TableInputArg` + `MapInput` shape stays. Do not reintroduce a dedicated `ArgumentRef.CompositeLookupArg` permit. Folding lookup and mutation input under a single `TableInputArg` is the unified design (one input-type → one ArgumentRef permit, partitioned by field-level `@lookupKey`); a parallel `CompositeLookupArg` permit would re-fork that path and double the directive-resolution surface.
- Single-binding `MapInput` is not normalised down to `ScalarLookupArg`. Both shapes share a slot pipeline through `flattenSlots`; rewriting one to the other would only churn the resolver and break the "input-shape determines arm" symmetry the sealed switch is built on.
- Hardening is structural (canonical-constructor `IllegalArgumentException`), not validator-asserted. The validator continues to handle schema-author-facing rejections (cardinality mismatch, lookup-fields-must-not-return-connection); structural impossibilities (empty `MapInput.bindings`) are type-level invariants because the classifier and resolver are the only producers and a violation indicates a classifier bug, not an authoring bug.
- No new validator arm is needed. The "lookup field has no `@lookupKey` arg" case is already a classifier rejection at `FieldBuilder.java:984` (the field never becomes a `LookupTableField`); the validator-mirrors-classifier principle does not apply because the validator only sees classified output, and the rejection happens upstream of validation. The validator's existing checks (cardinality match at `GraphitronSchemaValidator.java:373-389`, connection-return rejection at `:363-368`, `@orderBy` rejection at `:391-397`) are correct and unchanged.

## Type-level invariants

Add canonical-constructor non-empty checks to the two `LookupArg` permits whose bindings the emitter loops over:

- `LookupMapping.ColumnMapping.LookupArg.MapInput` (`LookupMapping.java:118`): `if (bindings.isEmpty()) throw new IllegalArgumentException("MapInput '" + argName + "' must carry at least one binding");`. Today the resolver guards this with `if (!tia.fieldBindings().isEmpty())` at the call site (`LookupMappingResolver.java:78`); the canonical constructor moves the invariant onto the type so the emitter and downstream consumers can rely on `bindings.get(0)` without re-checking.
- `LookupMapping.ColumnMapping.LookupArg.DecodedRecord` (`LookupMapping.java:145`): same check on `bindings`. The `CompositeColumnArg` classifier path always synthesises ≥ 1 binding (PK columns), so this is asserting an existing invariant rather than tightening behaviour.
- `LookupMapping.ColumnMapping.args` (`LookupMapping.java:44`): no canonical-constructor non-empty check. An empty `args` is a legitimate intermediate state (the resolver returns `ColumnMapping(emptyList(), table)` when no `@lookupKey`-bearing arg was seen, and `FieldBuilder.classifyTableFieldComponents` at `:984` rejects the field before it becomes a `LookupTableField`). Document this on the javadoc so the divergence from `MapInput.bindings` is intentional, not an oversight.

`ArgumentRef.InputTypeArg.TableInputArg.fieldBindings` (`ArgumentRef.java:236`) keeps its current "may be empty for non-lookup mutation inputs" contract; the boundary where non-empty starts to matter is the `MapInput` projection, and that is where the invariant lives.

## Load-bearing classifier checks

Add `@LoadBearingClassifierCheck` / `@DependsOnClassifierCheck` pairs so `LoadBearingGuaranteeAuditTest` mirrors the classifier→emitter contract for the lookup pipeline (currently zero coverage):

- Producer at `EnumMappingResolver.buildLookupBindings` (`EnumMappingResolver.java:242`): `@LoadBearingClassifierCheck` declaring that every returned `MapBinding.targetColumn()` resolves against the `TableInputArg.inputTable` (all bindings target columns of one table, the lookup target). Key: `lookup-mapping-bindings-table-coherent`.
- Producer at the inner-list rejection (`EnumMappingResolver.java:259-264`): `@LoadBearingClassifierCheck` declaring the no-inner-list invariant. Key: `lookup-key-input-field-non-list`.
- Producer at `FieldBuilder.classifyTableFieldComponents` (`FieldBuilder.java:984`): `@LoadBearingClassifierCheck` declaring that any field that *does* become a `LookupTableField` / `QueryLookupTableField` has `lookupMapping.args()` non-empty (the rejection at this site catches the empty-args case before the field is constructed). Key: `lookup-field-non-empty-args`.
- Consumer at `LookupValuesJoinEmitter.flattenSlots` (`LookupValuesJoinEmitter.java:127`, the `MapInput` arm): `@DependsOnClassifierCheck` referencing `lookup-mapping-bindings-table-coherent` and `lookup-key-input-field-non-list`. The slot construction reads `b.targetColumn()` for every binding without re-checking table coherence and assumes the `MapInput.bindings` list is internally homogeneous.
- Consumer at `LookupValuesJoinEmitter.buildInputRowsMethod` (`:183`): `@DependsOnClassifierCheck` referencing `lookup-field-non-empty-args`. `requireSlots(field)` flattens to a non-empty `Slot` list which the typed `Row<N+1>[]` arity computation depends on; an empty slot list would emit `Row<1>` with only the index cell, structurally invalid jOOQ.

The audit-test pair (`LoadBearingGuaranteeAuditTest`, `graphitron-rewrite/graphitron/src/test/java/no/sikt/graphitron/rewrite/model/LoadBearingGuaranteeAuditTest.java`) auto-pairs producer/consumer by key; no new test code beyond the annotations is needed for the orphan/blank-description checks to engage.

The `MapInput.bindings` non-empty invariant added in §Type-level invariants is *not* annotated as a `@LoadBearingClassifierCheck`. Type-level invariants enforced at construction belong on the canonical constructor; the audit pair exists for the classifier→emitter contract handoff where the producer and consumer are different methods, not for type-level guarantees.

## Tests

Pipeline tier (`graphitron-rewrite/graphitron/src/test/java/no/sikt/graphitron/rewrite/`):

- Extend `LookupTableFieldPipelineTest.compositeKeyInputType_producesSwitchArmAndInputRowsHelper` (`LookupTableFieldPipelineTest.java:90`) with assertions on the projected `LookupMapping.ColumnMapping`: `args.size() == 1`; the single arg is a `MapInput` with `bindings.size() == 2`, `list == true`, and binding target columns `(film_id, actor_id)` in declaration order; `slotColumns().size() == 2`. The current test asserts the emitted `filmActorsInputRows` method exists; this pins the model shape behind the same fixture.
- New `LookupMappingTest` (`graphitron-rewrite/graphitron/src/test/java/no/sikt/graphitron/rewrite/model/LookupMappingTest.java`) covering the canonical-constructor invariants directly: `MapInput` with empty bindings throws `IllegalArgumentException`; `DecodedRecord` with empty bindings throws; `MapInput` with one binding succeeds (single-binding case is legal, see Decisions). One test per case; ~20 lines total.

Audit tier:

- `LoadBearingGuaranteeAuditTest` runs unmodified; the three new `@LoadBearingClassifierCheck` keys and the two `@DependsOnClassifierCheck` consumer references land naturally, and the orphan/blank-description checks fail the build if a key drifts.

Execution tier (`graphitron-rewrite/graphitron-sakila-example/`):

- New end-to-end test, `CompositeKeyLookupQueryTest`, modelled on `FederationEntitiesDispatchTest` (`graphitron-rewrite/graphitron-sakila-example/src/test/java/no/sikt/graphitron/rewrite/test/querydb/FederationEntitiesDispatchTest.java:36-52`) which already wires a jOOQ `ExecuteListener` populating a `SQL_LOG: List<String>` against a `PostgreSQLContainer`. Schema slice: `input FilmActorKey @table(name: "film_actor") { filmId: Int @field(name: "film_id") @lookupKey, actorId: Int @field(name: "actor_id") @lookupKey }` plus a root query field `filmActorsByKey(key: [FilmActorKey!]!): [FilmActor!]!`. Two cases:
  - Golden path: query with two distinct `(film_id, actor_id)` keys returns exactly two rows in input order; `SQL_LOG` contains a two-column USING/JOIN over both `film_id` and `actor_id` (assert on the captured SQL string, not on generator output).
  - Subset path: query with a key list mixing one valid and one non-existent `(film_id, actor_id)` pair returns one row (the valid one) in input order; the missing slot is null/absent per the lookup contract.

The execution-tier sibling is the test that doesn't exist today and is the load-bearing addition; the pipeline and audit work is invariant-locking and audit-coverage scaffolding.

## Documentation cleanup

- `LookupMapping.java:111`: rewrite the comment from "R5's `@lookupKey` on input-object fields" to a stable description, e.g. "Composite-key Map-shaped input: `@lookupKey` on input-object fields, projecting one binding per input field to its target column". The workflow rule "Documentation names only live tests/code" disallows naming a roadmap item from production code; once R5 ships and its file is deleted, the reference would dangle.
- Append a one-line entry to `graphitron-rewrite/roadmap/changelog.md` capturing the actual delivered shape (`TableInputArg` + `MapInput`, not `CompositeLookupArg`) and the landing commit SHAs, so the archaeological record reflects what shipped rather than the stale Backlog one-liner.
- `composite-key-lookupkey.md` itself is deleted on `In Review → Done` per the workflow rule; this file does not need an in-place edit beyond the spec body.

## Acceptance criteria

- Canonical constructors on `MapInput` and `DecodedRecord` reject empty bindings; new `LookupMappingTest` pins the throw and the single-binding-succeeds case.
- Three `@LoadBearingClassifierCheck` keys on the lookup classifier path (`lookup-mapping-bindings-table-coherent`, `lookup-key-input-field-non-list`, `lookup-field-non-empty-args`); matching `@DependsOnClassifierCheck` annotations on the two `LookupValuesJoinEmitter` consumer sites; `LoadBearingGuaranteeAuditTest` green.
- Pipeline-tier `LookupTableFieldPipelineTest.compositeKeyInputType_producesSwitchArmAndInputRowsHelper` extension asserts `MapInput.bindings` shape (size, list cardinality, target columns).
- Execution-tier `CompositeKeyLookupQueryTest` runs the composite-key list-of-input-object lookup against PostgreSQL: two-key golden path returns two rows in input order with a two-column USING/JOIN in the captured SQL; mixed-key subset path returns the matching row only.
- `LookupMapping.java:111` no longer names "R5"; `roadmap/changelog.md` gains a closing entry naming the actual delivered shape and landing SHAs.
- `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` green.

## Out of scope

- Replacing `TableInputArg` with a dedicated `CompositeLookupArg` permit (would re-fork the lookup/mutation input paths; current unification is preferable, see Decisions).
- Single-binding `MapInput` → `ScalarLookupArg` normalisation (no behavioural difference; see Decisions).
- Cardinality-mismatch validator rework (`hasListArg() != returnIsList`, `:383`); the existing diagnostic is correct and out-of-scope here.
- Inner-list `@lookupKey` rejection at `EnumMappingResolver.java:259-264` already exists with a green test; only the `@LoadBearingClassifierCheck` annotation is in scope, the rejection logic itself is unchanged.
- Arity > 22 handling on composite `MapInput`: deferred until a real schema hits the cap; `ValuesJoinRowBuilder.MAX_ARITY` already throws an `IllegalStateException` with a clear message at construction.
