---
id: R330
title: "@condition(override: true) on a @nodeId filter field passes the root table instead of the joined FK-target alias"
status: Spec
bucket: bug
priority: 3
theme: nodeid
depends-on: []
created: 2026-06-18
last-updated: 2026-06-18
---

# @condition(override: true) on a @nodeId filter field passes the root table instead of the joined FK-target alias

When a filter input field carries both `@nodeId(typeName: "X")` and `@condition(override: true)`, the generated override-condition method receives the parent's root-table local (`table`) as its implicit first (`ParamSource.Table`) argument, instead of an alias for the FK-target table `X` that the method signature expects. The custom method `iRegelverksamling(Regelverksamling rs, ...)` on a `@table(name: "soknadsmangeltype")` type is handed the `Soknadsmangeltype` root table, so generated source fails to compile (`incompatible types: Soknadsmangeltype cannot be converted to Regelverksamling`). This is a legacy (v9) -> rewrite (v10) parity gap, not a regression between two rewrite RCs: legacy Graphitron joined the FK `soknadsmangeltype -> regelverksamling` and passed the joined `regelverksamling` alias, and the rewrite's FK-target `@nodeId` reimplementation never ported that override-condition join arm (see Regression provenance below). It surfaced when the consumer migrated to v10 (`10.0.0-RC16`). Plain `@condition(override: true)` without `@nodeId` is unaffected (its first arg genuinely is the field's own table); the breakage is specific to the `@nodeId` + override combination.

## Root cause

The FK-target `@nodeId` rewrite (the `DirectFk` + `liftedSourceColumns` model, landed across R131/R189/R312) binds decoded keys directly against lifted columns on the field's *own* table with no JOIN. That is correct for the implicit nodeId predicate but never propagates the join (`joinPath` / target `TableRef`) into the `@condition` method's `ParamSource.Table` slot.

- `model/ConditionFilter.java` + `model/ParamSource.java`: `ParamSource.Table` has no notion of which table beyond "the field's target table"; the `ConditionFilter` produced by `BuildContext.buildInputFieldCondition` (`BuildContext.java:1735-1765`, via `reflectTableMethod(..., TableSlotPolicy.REQUIRED, ...)`) carries className/method/params only, no join reference.
- `BuildContext.inputFieldFromNodeIdResolved` (`BuildContext.java:2097-2110`) stitches the `DirectFk` join path and the condition onto an `InputField.ColumnReferenceField` as independent slots; the condition's `Table` slot is never reconciled with `direct.joinPath()` / target table.
- `FieldBuilder.walkInputFieldConditions` (`FieldBuilder.java:1557-1570`, `ColumnReferenceField` arm): under `enclosingOverride` the implicit predicate that *would* use `rf.liftedSourceColumns()` / join path is suppressed (guard at line 1559), so the join information is dropped for the field; only the condition (rewrapped for nested Arg params, Table slot untouched) survives.
- `QueryConditionsGenerator.buildConditionMethod` (`QueryConditionsGenerator.java:179` and `:185`): the source alias is the hard-coded literal `"table"`, passed to `ArgCallEmitter.buildCallArgs` (`ArgCallEmitter.java:66-75`) as the `ParamSource.Table` first argument for *every* condition method. The emitter never has the FK-target alias in scope.

## Regression provenance

Established by unshallowing the repo and reading the full history (the agent's clone is shallow; individual-file history collapses to the shallow boundary and looks like a squash until unshallowed):

- **The rewrite never emitted the join.** Across the entire rewrite history, `git log -S joinPath` and `git log -S FkJoin` on `QueryConditionsGenerator.java` return nothing; the condition emitter has always passed the root `"table"`. The `@nodeId` + `@condition(override)` combination on a single field was never wired up in v10. The FK-target `@nodeId` filter path was built on the no-join `liftedSourceColumns` model (R131, R189, R312); R131's own entry records fixing a separate "zero-rows regression in the `opptak-subgraph` `regelverksamlingId` schema", the same consumer field, confirming this path was brought to parity piecemeal and the override arm was a remaining gap.
- **Legacy v9 did emit the join + alias.** Legacy `ReferenceFieldTest.java:82` pins the legacy output `.district(_a_customer_district, _a_customer_district_district_address) // Note, condition overrides as it uses "on"`: an override condition on a reference field receiving the joined target alias. Legacy also has a dedicated `SQLJoinOnCondition` construct and passed two aliases (source + joined target), mapping to the rewrite's unused `ParamSource.SourceTable` + `ParamSource.Table`. The reported consumer methods take only the single target arg.

## Decision: correlated EXISTS (B)

The SQL shape was the one open decision. **Decided: B, a correlated `EXISTS` inside the condition method**, over A (restore the legacy top-level join + alias literally). The model lift, validator-mirror rejection, and test plan are identical either way; only the emitted SQL and its blast radius differ.

The condition method stays a self-contained `(Table, env) -> Condition`: it builds its own correlated `X` alias and returns `DSL.exists(DSL.selectOne().from(rs).where(parent.fkChild = rs.pk AND <X>Service.<method>(rs, args...)))`. Why B wins on principle:

- **Layering / helper-locality.** `QueryConditionsGenerator`'s own javadoc commits the emitted helpers to the pure `(Table, env) -> Condition` shim shape with the fetcher owning FROM/JOIN. B keeps that contract (its correlated alias is method-local, like an inline subquery's). A breaks it, spreading one join across the fetcher and conditions layers and widening every affected `<field>Condition` signature with a positional FK-target alias parameter.
- **Behaviour, not SQL byte-shape, is preserved.** Pipeline + execution tiers check result semantics; code-string body assertions are banned; legacy SQL divergence is an accepted, execution-pinned precedent (`argument-resolution.adoc`). The JOIN -> EXISTS shape change is sanctioned because the result set is preserved (the FK is to-one).
- **Cardinality invariant.** A correlated `EXISTS` is the shape the `JoinStep` cardinality invariant (`JoinStep.java:39-65`) blesses; A's top-level inner join sits nearer the silent row-elimination footgun it exists to prevent.
- **Blast radius.** B touches only the override arm; it never perturbs the fragile `override:false` implicit-predicate path (decoded keys bound against `liftedSourceColumns` on the parent's own table, no join).

The cost accepted: the `EXISTS` shell is genuinely new emission (no precedent in the generator; the correlation predicate inside it reuses `JoinPathEmitter.emitCorrelationWhere`), and the SQL diverges from legacy (`EXISTS` vs top-level `JOIN`). The developer method contract is unchanged: it still receives an `X` table instance and returns a `Condition`, oblivious to whether that condition lands in a top-level WHERE (legacy) or an `EXISTS` subquery (here); existing consumer methods compile and run unmodified.

A is recorded as the rejected alternative under "Alternative considered (A)" below, for the reviewer's benefit and in case a future consumer is found to depend on the join being visible in the outer query.

## Approach: correlated EXISTS with a model-lifted Table-slot identity

The bug is a model gap that surfaces as an emitter literal. `ParamSource.Table` documents itself as "the field's target table", but for an FK-target `@nodeId` + override field the developer method's first parameter is the FK-*target* table `X`, not the field's own root table. The model holds two tables (root and `X`) and the slot cannot tell them apart, so `QueryConditionsGenerator` emits the literal `"table"` for every condition method. This is the typed adapter/composer asymmetry the principles warn about: the `QueryConditions.<field>Condition` adapter hands the `<X>Conditions`-style composer a value of the wrong static type, and here it fails at *consumer compile* rather than runtime.

The fix lifts the Table-slot identity into the model (see Model lift) so the emitter switches on a pre-resolved value rather than the literal `"table"`, then emits the FK-target arm as a correlated `EXISTS` (per the decision above).

## Design

### Model lift (the resolution, not its inputs)

Today `BuildContext.inputFieldFromNodeIdResolved` (`BuildContext.java:2097-2110`) stitches `direct.joinPath()` / `liftedSourceColumns` and the `ArgConditionRef` onto a `ColumnReferenceField` as independent slots, and `FieldBuilder.walkInputFieldConditions` (`FieldBuilder.java:1557-1570`) drops the join under `enclosingOverride`, leaving a bare `ConditionFilter` whose `Table` slot the emitter fills with `"table"`. The fix lifts the Table-slot identity so the emitter switches on a pre-resolved value instead of a literal:

- When an FK-target `@nodeId` field carries `@condition(override: true)`, the `ConditionFilter` the emitter consumes must carry the FK correlation: the target `TableRef` (`X`), the `joinPath` (`FkJoin` hops), the parent-side FK-child columns (`liftedSourceColumns`), and `X`'s `keyColumns`. Leading representation: a narrow sealed split so the FK-target case is a distinct `WhereFilter` shape rather than an `Optional` bolted onto the common `ConditionFilter` (narrow component types over broad records; the emitter and validator both fork on the distinction, so it belongs in the model). The lighter `Optional<FkTargetCorrelation>` on `ConditionFilter` is the fallback if the sealed split proves disruptive to the other `ConditionFilter` consumers; final shape settled in In Progress.
- The plain-override (non-nodeId) and root-table cases keep the existing root-table arm and emit `table` byte-identically.

### Emitter (`QueryConditionsGenerator`)

`buildConditionMethod` switches on the lifted value per filter:

- **Root-table arm** (today's behaviour): emit `<X>Conditions.<method>(table, args...)` via `ArgCallEmitter.buildCallArgs(..., "table", ...)`, unchanged.
- **FK-target arm**: declare a correlated alias for `X` (`Tables.X.as("<field>_<n>")`), and return `DSL.exists(DSL.selectOne().from(rs).where(<correlation>.and(<X>Service.<method>(rs, args...))))`, where `<correlation>` is `parent.fkChild = rs.pk` over the hops. Reuse `JoinPathEmitter.emitCorrelationWhere` / `generateAliases` for the correlation predicate and aliasing (the same helpers `InlineColumnReferenceFieldEmitter` already drives for the non-override reference case); the developer method call still flows through `buildCallArgs`, now with the correlated alias as `srcAlias` instead of `"table"`. The `EXISTS` shell is the one genuinely new emission (no existing precedent), confined to this single arm.

### Alternative considered (A): top-level join + threaded alias (rejected)

Recorded for the reviewer; not the chosen path. A restores legacy SQL literally: `TypeFetcherGenerator.buildQueryTableFetcher` declares the aliased target table (`Tables.X.as(...)`), emits the FK join over `joinPath` (reusing the same `JoinPathEmitter` helpers and the `SelectJoinStep step = …; step = step.join(...).on(...)` reassignment idiom the interface fetchers already use), and threads the joined terminal alias into the `<field>Condition` call as an added positional parameter (one per FK-target+override filter). The model lift and validator mirror would be identical to B. Rejected because it spreads one join decision across the fetcher and conditions layers and introduces a cross-layer contract (fetcher and conditions class agreeing on the per-field alias-parameter list) that widens every affected condition method's signature; revisit only if a consumer is found to depend on the join being visible in the outer query.

### Validator mirror

Per validator-mirrors-classifier, every classifier decision implying an emitter branch must fail at validate time when the branch is unimplemented. The defensive `IllegalStateException` throws in the column-reference emitter are the wrong tier for a schema-author error. Add `GraphitronSchemaValidator` rejections for:

- **Composite-key FK target + override** (`CompositeColumnReferenceField` is a deferred stub today): reject with a message pointing at the field and naming the deferral, rather than reaching the emitter. The composite correlation (`row(parentFkCols).eq(row(rs.keyCols))`) is the natural eventual home but belongs to R24, not this fix.
- Any FK-target override whose `joinPath` / `liftedSourceColumns` did not resolve.

## Slices

1. **Model lift + validator rejection.** Carry the FK correlation onto the override `ConditionFilter` (sealed split or `Optional` per above); add the validate-time rejections (composite-key + override, unresolved join). Pipeline tests pin the classification and the rejections. No emitter change yet, so the FK-target arm still mis-emits, guarded by a pending-acceptance test marked accordingly or landed with slice 2.
2. **Emitter FK-target arm.** Implement the `EXISTS` emission switching on the lifted value; root-table arm stays byte-identical. The acceptance test (below) flips green.
3. **Execution coverage + docs.** Sakila execution cases (single-hop, multi-hop, list `[ID!]`, nullable FK) proving result-set equivalence; document the FK-target override behaviour on the `@nodeId` / `@condition` surface in the user docs.

## Edge cases

| Case | Handling |
|---|---|
| Multi-hop `joinPath` (>1 `FkJoin`) | Walk the full path inside the `EXISTS` `from`/`where`; reuse `JoinPathEmitter`. Confined to one method body (no cross-layer terminal-alias threading). |
| Composite key (`CompositeColumnReferenceField`) | **Rejected at validate time** in this fix (deferred stub); the `RowN` correlation is R24. |
| Nullable FK | `EXISTS` is false when no `X` row matches, correct filter semantics; structurally safe under the cardinality invariant. |
| List-valued `[ID!]` nodeId filter | Decode list, predicate is `rs.pk IN (decoded keys)` inside the `EXISTS`, ANDed with the developer call; matches the existing `RowIn` body shape. Execution-covered. |
| `override: false` / no condition | **Unchanged**: implicit predicate binds decoded keys against `liftedSourceColumns`, no join. The fix never touches this path. |
| Plain `@condition(override: true)` without `@nodeId` | **Unchanged**: root-table arm, emits `table`. |

## Test plan

- **Acceptance (pipeline, falsifiable).** A single filter field carrying both `@nodeId(typeName: X)` and `@condition(override: true)`; assert the model carries the FK-target correlation and the generated condition routes the developer call against the FK-target alias (a correlated `X` subquery), not the root `table`. The pre-fix code passes `table`, so this test fails against the regression, which is the point.
- **Rejection (pipeline).** Composite-key FK target + override rejects at validate time with a clear deferral message; unresolved join rejects.
- **Execution (sakila, the behaviour backstop).** Result-set equivalence for single-hop, a multi-hop FK chain, a list `[ID!]` filter, and a nullable FK, each asserting the returned rows, not SQL text. A negative control on a plain override (no `@nodeId`) confirming the root-table arm is unchanged.
- **No code-string assertions on generated method bodies** at any tier; pipeline assertions are on the classified model and structural `TypeSpec` shape per the test strategy.

The existing `NodeIdReferenceFilterPipelineTest` puts `@nodeId` and `@condition` on *separate* fields and only asserts the decode helper is lifted + generation does not throw; no current test exercises both directives on one field, nor asserts the alias bound to an override method's first argument, which is why the gap went uncaught. The acceptance test above closes it.

## Reported instances

Two real consumer instances (both `incompatible types` compile failures at Graphitron 10.0.0-RC16): `SoknadsmangeltypeFilterInput.regelverksamlingId` -> `iRegelverksamling(Regelverksamling, ...)`, and `EndringsloggV2FilterInput.brukerId` -> `harBruker(Bruker, ...)`.
