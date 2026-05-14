---
id: R164
title: "Field model two-axis pivot: DataFetcher x QueryBuilder"
status: Backlog
bucket: structural
priority: 3
theme: structural-refactor
depends-on: [unify-record-dml-on-carrier-walk]
created: 2026-05-14
last-updated: 2026-05-14
---

# Field model two-axis pivot: DataFetcher x QueryBuilder

The field model expresses two orthogonal axes through a single hierarchy, producing combinatorial pressure that grows by powers as the model evolves. **DataFetcher** is the runtime axis ("what does the field's `DataFetcher` do at request time"): `Root` executes SQL at the request entry, `Lift` runs SQL keyed on parent source (typically DataLoader-batched; the carrier-walk follow-up case skips the loader because the parent value, an upstream DML carrier, already enumerates the full key set for the fetch), `Trivial` reads synchronously off `env.getSource()`. **QueryBuilder** is the codegen axis ("what jOOQ query part does the field contribute"): `Projection` (column or expression inlined into the parent SELECT), `SubSelect` (own SELECT subquery, joined or DataLoader-batched), `Polymorphic` (UNION ALL across multiple tables, or discriminator-column dispatch on a single table), `DML` (INSERT / UPDATE / DELETE / UPSERT statement with `RETURNING`), `Opaque` (developer's `@service` / `@tableMethod` owns the query), `None` (pure runtime read, no jOOQ contribution). The two axes are genuinely orthogonal: `ColumnField` is `(Trivial, Projection)`, `TableField` is `(Trivial, SubSelect-via-join)`, `SplitTableField` is `(Lift, SubSelect-via-DataLoader)` — same QueryBuilder, different DataFetcher; same DataFetcher, different QueryBuilder. Today the cross product is expressed as a single permit name in each of `QueryField` (10 permits), `MutationField` (8 permits), and `ChildField` (28 permits). The 46 total arms in `TypeFetcherGenerator`'s main dispatch switch are the empirical fingerprint of the conflation: a two-level decision (DataFetcher × QueryBuilder, with parent-context and modifiers below) flattened into a single name. This item promotes both axes to first-class typed components on the `Field` interface, collapses the mixin interfaces that have been quietly carrying the axis decomposition all along (`BatchKeyField` = Lift on DataFetcher; `SqlGeneratingField` = non-None / non-Opaque on QueryBuilder; `MethodBackedField` = Opaque on QueryBuilder; `LookupField` = a QueryBuilder modifier), and reorganizes the megaswitch into a 3-level dispatch tree where each level has 3-6 arms and every arm decides one axis.

## Why this supersedes R162 and R163

R162 (`mutation-result-field-sealed-on-kind`) promotes verb to permit identity in `MutationField`. The empirical motivation — `DmlKind` is double-encoded (sealed in the `DmlTableField` permits, enum-field in the `DmlRecord*` permits) — is correct, but the permit isn't `MutationField`; it's the `DML` arm of `QueryBuilder`. R162's `Result` discriminator (`Encoded{Single,List}` / `Projected{Single,List}` / `CarrierWrapper`) becomes the `returnShape` sub-component of `QueryBuilder.DML(verb, multiRow, returnShape)`. The seven-permit consolidation R162 proposes is replaced by a single `(Root, DML(...))` shape with the verb axis explicit as a component.

R163 (`rename-record-to-carrier-model-wide`) renames the carrier-walk plumbing from `Record*` to `Carrier*`. The rename is correct in spirit but its scope evaporates: the carrier-walk family collapses into the `Lift` arm of DataFetcher (with no loader registration for the single-carrier follow-up case), and the surrounding plumbing types (`SingleRecordCarrierShape`, `SingleRecordCarrierResolution`, `ChildField.SingleRecord*`) get rebuilt as components of the new structure. The rename happens implicitly because the types being renamed don't survive in their current shape.

Both items can be Discarded once R164 enters Spec.

## The valid-pairs grid (draft)

| | `Projection` | `SubSelect` | `Polymorphic` | `DML` | `Opaque` | `None` |
|---|---|---|---|---|---|---|
| **Root** | — | `QueryTableField`, `QueryLookupTableField` | `QueryInterfaceField`, `QueryUnionField`, `QueryTableInterfaceField`, `QueryNode*` | All 8 `MutationField` permits | `QueryServiceTableField`, `QueryServiceRecordField`, `MutationService*`, `QueryTableMethodTableField` | — |
| **Lift** | — | `SplitTableField`, `SplitLookupTableField`, `RecordTableField`, `RecordLookupTableField`, `RecordTableMethodField`, `SingleRecordTableField` (no-loader follow-up) | `InterfaceField`, `UnionField`, `TableInterfaceField` (child) | — | `ServiceTableField`, `ServiceRecordField` (child) | — |
| **Trivial** | `ColumnField`, `CompositeColumnField`, `ColumnReferenceField`, `ParticipantColumnReferenceField`, `CompositeColumnReferenceField`, `ComputedField` | `TableField`, `LookupTableField` (inline-join) | — | — | — | `PropertyField`, `RecordField`, `NestingField`, `ConstructorField`, `ErrorsField`, `SingleRecord*` (read-from-carrier arms) |

The grid covers all 46 existing permits across the three hierarchies. Empty cells are structurally impossible (`Trivial DML` cannot exist; you can't read-from-source a mutation). The grid's sparsity is itself the validation: the two axes plus orthogonal modifiers exhaust the field model.

## Target shape

```java
sealed interface Field permits QueryField, MutationField, ChildField {
    String name();
    SourceLocation location();
    ReturnTypeRef returnType();
    DataFetcher fetcher();        // sealed: Root | Lift(SourceKey, Optional<LoaderRegistration>) | Trivial
    QueryBuilder builder();       // sealed: None | Projection(...) | SubSelect(...) | Polymorphic(...) | DML(verb, multiRow, returnShape) | Opaque(MethodRef)
    Modifiers modifiers();        // bag: Optional<LookupMapping>, Optional<ErrorChannel>, Optional<SplitQuery>, ...
}
```

The `QueryField` / `MutationField` / `ChildField` sub-seals are preserved (they reflect authoring scope, which is a real and useful organizing concept). What collapses is the permit-per-combination inside each: e.g. `QueryInterfaceField` and `QueryUnionField` collapse to a single record carrying `builder = Polymorphic(Polymorphism.Interface(...) | Polymorphism.Union(...))`. The 28 ChildField permits collapse to roughly 6-8 records, one per `(parent-context × DataFetcher × QueryBuilder)` family.

Existing mixin interfaces retire:

- `BatchKeyField` → `fetcher() instanceof DataFetcher.Lift l && l.loaderRegistration().isPresent()` (assuming the `Optional<LoaderRegistration>` resolution of the edge case below; if the sub-arm split lands instead, it's `instanceof DataFetcher.Lift.Batched`)
- `SqlGeneratingField` → `builder() instanceof SubSelect | Polymorphic | DML`
- `MethodBackedField` → `builder() instanceof Opaque(var method)`
- `LookupField` → `modifiers().lookupMapping().isPresent()`
- `TableTargetField` (the SubSelect-on-table sub-seal) → expressed via the `SubSelect` arm's payload

`WithErrorChannel` and `ConditionJoinReportable` are genuinely orthogonal (error wiring, emit-time diagnostic) and stay as separate axes on `Modifiers` or sibling interfaces.

## Edge cases the Spec must resolve

- **`Lift` arm's `LoaderRegistration` slot — `Optional`, or sub-arm split?** Present for the DataLoader-backed fields (`SplitTableField`, `RecordTableField`, etc.), absent for the carrier-walk follow-up (`SingleRecordTableField`), where the parent value already enumerates the full key set so no batching is needed. Spec must decide whether the slot is `Optional<LoaderRegistration>` on a single `Lift` arm, or whether the no-loader case is a sub-arm of `Lift` (e.g. `Lift.Batched(SourceKey, LoaderRegistration)` / `Lift.Inline(SourceKey)`). The trade-off mirrors the standard "optional-component vs sub-seal" call: optional collapses to one record but lets emitters miss the empty case; sub-seal pushes the discrimination into the type system at the cost of an extra arm.
- **`TableField` (Child) — Trivial-via-inline-join or Lift?** Audit didn't resolve. If inline-join, the parent's SELECT includes the child table's columns and the fetcher is Trivial; if DataLoader, it's Lift. Read `TypeFetcherGenerator:313`'s `TableField` arm and `TypeClassGenerator.collectRequiredProjectionColumns` (which lists `SplitTableField` but not `TableField`) to settle.
- **`ComputedField` (`@externalField`)** is documented as "inlined into the parent's projection at generation time" — that's `(Trivial, Projection)` (the column is a `Field<X>` expression rather than a real column, but the projection mechanism is the same). Confirm.
- **`TableInterfaceField` Lift-vs-Root status on child.** Single-table polymorphic with discriminator column; might be `(Trivial, Polymorphic-on-single-table)` like a fancy `TableField`, or `(Lift, Polymorphic)` if it uses DataLoader.
- **`QueryNodeField` / `QueryNodesField`** are root polymorphic dispatch via Relay Node. They're `(Root, Polymorphic)` but with a Node-specific sub-axis on the Polymorphic arm; spec needs to decide whether Node is a fourth `Polymorphism` sub-arm or a separate axis.
- **Modifiers axis composition.** `@splitQuery`, `@lookupKey`, `@reference`, `@condition`, error-channel, condition-join-reportability — which are bag-of-optionals on `Modifiers`, which are sub-axes of QueryBuilder arms (e.g. `LookupKey` is a sub-axis of `SubSelect`)?
- **Mutation `multiRow` and verb-on-DML.** R162's "verb-on-permit-identity" intuition maps to `DML(verb, multiRow, returnShape)`. Spec must enumerate which verbs admit `multiRow=true` (UPDATE, DELETE confirmed; INSERT and UPSERT excluded by construction) and where the compact-constructor invariants live.
- **Bulk-vs-single input cardinality.** R162 dissolves this into `tableInputArg.list()`. With QueryBuilder.DML explicit, the same axis surfaces as a sub-component of the DML arm or as a separate property of the field's input shape.

## Consumer-side refactor scope

- `TypeFetcherGenerator` — the main 46-arm megaswitch reorganizes into outer-on-DataFetcher / inner-on-QueryBuilder / innermost-on-specifics.
- `FetcherEmitter` — currently the Trivial-bucket dispatcher; restructures to dispatch on QueryBuilder within the Trivial DataFetcher branch.
- `FetcherRegistrationsEmitter` — currently consumes `BatchKeyField`; becomes a Lift-arm consumer of DataFetcher.
- `TypeClassGenerator.emitSelectionSwitch` and `collectRequiredProjectionColumns` — both consume QueryBuilder.Projection / QueryBuilder.SubSelect details; restructure around the new axis.
- `QueryConditionsGenerator` — single-axis on QueryBuilder (SQL-generating root fields only).
- `MultiTablePolymorphicEmitter` — single-axis on QueryBuilder.Polymorphic (multi-table arm).
- `SplitRowsMethodEmitter` — single-axis on DataFetcher.Lift (DataLoader rows-method emitter).
- `GraphitronSchemaValidator` — currently dispatches on full permit identity for per-variant validation; restructures to per-axis validation rules.
- `MappingsConstantNameDedup.withResolvedChannel` — currently uses `WithErrorChannel` mixin; survives largely unchanged.
- The four-plus mixin interface retirements (above).
- Every test file that switches on permit type or `instanceof`s a permit — the audit didn't enumerate but `GraphitronSchemaBuilderTest` and `TypeFetcherGeneratorTest` are the heavy hitters.

## Dependencies and sequencing

- Supersedes **R162** and **R163**. Both can be Discarded once R164 enters Spec.
- **R161** (`unify-record-dml-on-carrier-walk`) is in flight and lands the carrier-walk unification. R164's Lift-arm modelling of the carrier-walk follow-up case depends on R161 having settled. Hard dependency.
- Touches every emitter, validator, and a substantial portion of the test surface. This is a large structural payload — likely 2-4 weeks of focused work — and should land before any further model evolution to avoid rebasing on a moving target.
- Recommended sequencing inside R164: (1) introduce `DataFetcher` and `QueryBuilder` as new sealed types on `Field` alongside existing permits; (2) populate them from the existing permit data via a transitional adapter; (3) migrate consumers one at a time to switch on the new axes; (4) retire old permits and mixins; (5) delete the adapter. Each step is verifiable and reversible.
