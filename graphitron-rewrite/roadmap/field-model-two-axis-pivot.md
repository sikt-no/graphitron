---
id: R164
title: "Field model orthogonal-axes pivot"
status: Backlog
bucket: structural
priority: 3
theme: structural-refactor
depends-on: [unify-record-dml-on-carrier-walk]
created: 2026-05-14
last-updated: 2026-05-14
---

# Field model orthogonal-axes pivot

The field model packs several orthogonal axes into a single permit name in each of `QueryField` (10 permits), `MutationField` (8 permits), and `ChildField` (28 permits). 46 leaves across three hierarchies; one `TypeFetcherGenerator` megaswitch with one arm per leaf. The axes are individually small (3-6 values each), but the cross product is the permit set, and adding a value on any axis multiplies the permits below it. The old DataFetcher dimension (`Root` / `Lift` / `Trivial`) was itself a conflation: `Root` and `Lift` were cardinality observations (no parent source vs. parent-source-keyed), `Trivial` was an I/O observation (no I/O vs. some I/O), and the three labels shared an axis that was really two.

The pivot makes each axis a first-class slot on `Field`:

- **Source cardinality** (`Zero` / `One` / `Many`): how many values the fetcher consumes from `env.getSource()`. `Zero` = no parent (request entry); `One` = single parent value; `Many` = a single source value that enumerates multiple rows (the bulk DML carrier's `Result<RecordN<...>>`).
- **Action** (`Trivial` / `Service` / `Query`): what the fetcher does at request time. `Trivial` reads source / args / static config without I/O; `Service` invokes a developer-supplied `@service` method (graphitron does not generate the I/O); `Query` executes a graphitron-generated jOOQ statement. `@tableMethod` is `Query`, not `Service` — the developer's method returns a typed jOOQ `Table<?>` that graphitron substitutes for the generated `Tables.*` reference, but graphitron still owns the SELECT around it.
- **Field cardinality** (`Single` / `List` / `Connection`): the field's output shape. Independent of source cardinality: `(source=One, field=List)` is `Author.books`, `(source=Zero, field=Single)` is `Query.user(id)`, `(source=Many, field=List)` is the bulk-carrier follow-up returning a list.
- **QueryBuilder** (`None` / `Projection` / `SubSelect` / `Polymorphic` / `DML` / `Opaque`): the jOOQ contribution the field makes. `None` = no SQL; `Projection` = column inlined into the parent SELECT; `SubSelect` = own SELECT (joined inline or fired as a separate batched call), carrying the SourceKey payload for WHERE / JOIN keying; `Polymorphic` = UNION ALL across tables or discriminator-column dispatch; `DML` = `INSERT` / `UPDATE` / `DELETE` / `UPSERT` with `RETURNING` (the verb / multi-row / return-shape sub-axes live here per R162); `Opaque` = `@service` owns the I/O (no graphitron-generated SQL).
- **Modifiers** (bag of optionals): `LookupMapping`, `ErrorChannel`, `SplitQuery`, etc. Genuinely orthogonal axes that don't shape the cross product.

The two cardinalities (source and field) are independent. The action sub-arm `Query.OverSource` pairs strictly with `source=Many` (one batched SELECT against the keys enumerated in the source); `Query.PerInvocation` pairs with `source=Zero` (args-keyed) or `source=One` (parent-key-keyed, DataLoader-amortized across invocations when the parent is a list). The forbidden pair is `(source=Many, action=Query.PerInvocation)`: per-element I/O at `Many` cardinality is N+1 by construction; the sub-arm split moves the constraint into the type system, with a compact-constructor invariant pinning the pairing.

`SourceKey` migrates from a fetcher-runtime concern to a payload of `QueryBuilder.SubSelect` (and any builder arm that needs keyed projection): it's about which columns to project into the SELECT's WHERE / JOIN, not about how the fetcher consumes source at runtime. The `Reader` / `Wrap` distinctions stay where they are; the `Cardinality` field on `SourceKey` retires, superseded by the field's `sourceCardinality()` slot.

Today's mixin interfaces fall out of the new axes:

- `BatchKeyField` → fields at `source=One` with a DataLoader-bearing payload (most cleanly: `action=Query.PerInvocation` or `action=Service` with a `LoaderRegistration` component on the relevant sub-arm).
- `SqlGeneratingField` → `builder() instanceof Projection | SubSelect | Polymorphic | DML`.
- `MethodBackedField` → `action() instanceof Action.Service` (equivalently `builder() instanceof Opaque`).
- `LookupField` → `modifiers().lookupMapping().isPresent()`.

## Why this supersedes R162 and R163

R162 (`mutation-result-field-sealed-on-kind`) promotes verb to permit identity in `MutationField`. The empirical motivation — `DmlKind` is double-encoded (sealed in the `DmlTableField` permits, enum-field in the `DmlRecord*` permits) — is correct, but the permit isn't `MutationField`; it's the `DML` arm of `QueryBuilder`. R162's `Result` discriminator (`Encoded{Single,List}` / `Projected{Single,List}` / `CarrierWrapper`) becomes the `returnShape` sub-component of `QueryBuilder.DML(verb, multiRow, returnShape)`. The seven-permit consolidation R162 proposes is replaced by a single `(source=Zero, action=Query, builder=DML(...))` shape with the verb axis explicit as a component.

R163 (`rename-record-to-carrier-model-wide`) renames the carrier-walk plumbing from `Record*` to `Carrier*`. The rename is correct in spirit but its scope evaporates: the carrier-walk family decomposes cleanly onto the new axes — the bulk-carrier follow-up SELECT is `(source=Many, action=Query.OverSource, builder=SubSelect)`, and the carrier's read-from-source data fields are `(source=Many, action=Trivial, builder=None)`. The surrounding plumbing types (`SingleRecordCarrierShape`, `SingleRecordCarrierResolution`, `ChildField.SingleRecord*`) get rebuilt as components of the new structure; the rename happens implicitly because the types being renamed don't survive in their current shape.

Both items can be Discarded once R164 enters Spec.

## The valid-pairs grid (draft)

Primary grid — source cardinality × action:

| | `Trivial` | `Service` | `Query` |
|---|---|---|---|
| **`Zero`** | constants, API version, time-since-startup, configuration (no existing permit; structurally allowed) | `QueryServiceTableField`, `QueryServiceRecordField`, `MutationServiceTableField`, `MutationServiceRecordField` | `QueryTableField`, `QueryLookupTableField`, `QueryInterfaceField`, `QueryUnionField`, `QueryTableInterfaceField`, `QueryNodeField`, `QueryNodesField`, `QueryTableMethodTableField`, all 8 `MutationField` DML permits |
| **`One`** | `ColumnField`, `TableField` (inline-join), `LookupTableField` (inline-join), `PropertyField`, `RecordField`, `NestingField`, `ConstructorField`, `ErrorsField`, `ColumnReferenceField`, `CompositeColumnField`, `CompositeColumnReferenceField`, `ParticipantColumnReferenceField`, `ComputedField`, `SingleRecord*` (single-carrier read-from-source arms) | `ChildField.ServiceTableField`, `ChildField.ServiceRecordField` | `SplitTableField`, `SplitLookupTableField`, `RecordTableField`, `RecordLookupTableField`, `RecordTableMethodField`, `TableMethodField`, child `InterfaceField`, child `UnionField`, child `TableInterfaceField` |
| **`Many`** | `SingleRecord*` (bulk-carrier read-from-source arms) | — (empty; if ever introduced, same batched-only constraint as `Query` — see edge cases) | `SingleRecordTableField` (bulk variant) — must be `Query.OverSource` (single batched SELECT). Per-element `Query.PerInvocation` at `Many` is forbidden: N+1. |

Secondary mapping — action pairs with QueryBuilder:

- `Trivial` → `None` (no jOOQ contribution) | `Projection` (column inlined into parent SELECT) | `SubSelect` (inline-join contribution to parent SELECT, fetcher reads materialized columns off the parent record).
- `Service` → `Opaque`.
- `Query` → `SubSelect` (own SELECT, joined or batched) | `Polymorphic` | `DML`.

The grid covers all 46 existing permits. Empty cells are structurally meaningful: `(Many, Service)` is empty today but would need the same batched-only constraint as `(Many, Query)` if a permit ever lands there; `(Zero, Trivial)` is empty in the current schema-driven set but the model leaves room for it.

## Target shape

```java
sealed interface Field permits QueryField, MutationField, ChildField {
    String name();
    SourceLocation location();
    ReturnTypeRef returnType();
    SourceCardinality sourceCardinality();   // sealed: Zero | One | Many
    Action action();                         // sealed: Trivial | Service | Query (with sub-arms)
    FieldCardinality fieldCardinality();     // sealed: Single | List | Connection
    QueryBuilder builder();                  // sealed: None | Projection(...) | SubSelect(SourceKey, ...) | Polymorphic(...) | DML(verb, multiRow, returnShape) | Opaque(MethodRef)
    Modifiers modifiers();                   // bag: Optional<LookupMapping>, Optional<ErrorChannel>, Optional<SplitQuery>, ...
}

sealed interface Action permits Action.Trivial, Action.Service, Action.Query {}

sealed interface Action.Query extends Action permits Action.Query.PerInvocation, Action.Query.OverSource {
    // PerInvocation: one SQL call per fetcher invocation. Args-keyed at source=Zero;
    //   parent-key-keyed at source=One (DataLoader amortizes cross-invocation when needed).
    // OverSource:    one batched SQL call against the keys enumerated in the source value.
    //   Pairs strictly with source=Many.
}

record SomeFieldImpl(..., SourceCardinality sc, Action a, ...) implements Field {
    public SomeFieldImpl {
        // Compact-constructor invariant pinning the (source × Query-sub-arm) pairing.
        if (sc instanceof SourceCardinality.Many && a instanceof Action.Query.PerInvocation)
            throw new IllegalArgumentException("Many cardinality with per-invocation query = N+1");
        if (!(sc instanceof SourceCardinality.Many) && a instanceof Action.Query.OverSource)
            throw new IllegalArgumentException("OverSource query requires Many cardinality");
    }
}
```

The `QueryField` / `MutationField` / `ChildField` sub-seals are preserved (they reflect authoring scope, which is a real and useful organizing concept). What collapses is the permit-per-combination inside each: `QueryInterfaceField` and `QueryUnionField` collapse to a single record carrying `builder = Polymorphic(Polymorphism.Interface(...) | Polymorphism.Union(...))`; the 28 ChildField permits collapse to roughly 6-8 records, one per (parent-context × source-cardinality × action × QueryBuilder) family.

Existing mixin interfaces retire:

- `BatchKeyField` → `sourceCardinality() instanceof One && DataLoader-bearing sub-arm of the field's action` (exact pattern resolved at Spec; carries the `LoaderRegistration` component the sub-arm holds).
- `SqlGeneratingField` → `builder() instanceof Projection | SubSelect | Polymorphic | DML`.
- `MethodBackedField` → `action() instanceof Action.Service` (equivalently `builder() instanceof Opaque`).
- `LookupField` → `modifiers().lookupMapping().isPresent()`.
- `TableTargetField` (the SubSelect-on-table sub-seal) → expressed via the `SubSelect` arm's payload.

`WithErrorChannel` and `ConditionJoinReportable` are genuinely orthogonal (error wiring, emit-time diagnostic) and stay as separate axes on `Modifiers` or sibling interfaces.

## Edge cases the Spec must resolve

- **Field cardinality × source cardinality crossings.** `(source=One, field=*)` and `(source=Zero, field=*)` are routine. `(source=Many, field=List)` covers the bulk-carrier follow-up returning a list. `(source=Many, field=Single)` is structurally suspicious — if the source enumerates many rows, the field's output is conventionally a list. Audit whether single-output-at-source=Many is ever valid, or whether the type system should forbid it via compact-constructor invariant.
- **`(Zero, Trivial)` slot.** No existing permit synthesizes a constant / API-version / time-since-startup / configuration value at the root, but the slot is structurally meaningful. Spec must decide whether to model it on day one (e.g. a `ConstantField` permit) or leave the cell empty until a user need lands.
- **`(Many, Service)` slot.** Empty today. If a permit ever lands there it carries the same batched-only constraint as `(Many, Query)`. Spec must decide whether to introduce `Action.Service.OverSource` now (preserving symmetry with `Action.Query`) or only when a permit needs it.
- **DataLoader payload placement.** Today's `BatchKeyField` carries a `LoaderRegistration`. Under the new axes, the registration belongs on the action sub-arm that needs it: `Action.Query.PerInvocation` at `source=One`, and `Action.Service` at `source=One`. Spec must decide whether the loader sits on `Query.PerInvocation` / `Service` directly (as a component) or whether each action splits into `*.Loaded` / `*.Direct` sub-arms keyed off the cardinality (e.g. `Query.PerInvocation.ArgsKeyed` for `source=Zero`, `Query.PerInvocation.ParentKeyed(LoaderRegistration)` for `source=One`).
- **`SourceKey` placement.** Migrates onto `QueryBuilder.SubSelect` (and arms that need keyed projection); the `Reader` / `Wrap` distinctions stay; `SourceKey.Cardinality` retires (Field's `sourceCardinality()` is the source of truth). Spec must confirm the move is clean — in particular, that no consumer reads `SourceKey` outside a `SubSelect`-shaped emission path.
- **`TableField` (Child) — Trivial-via-inline-join, or Query.PerInvocation?** Audit didn't resolve. If inline-join, the parent's SELECT includes the child table's columns and the fetcher is `Trivial` with `builder=SubSelect-via-inline`; if it's a separate DataLoader-batched call, it's `Query.PerInvocation` with `builder=SubSelect-via-loader`. Read `TypeFetcherGenerator:313`'s `TableField` arm and `TypeClassGenerator.collectRequiredProjectionColumns` (which lists `SplitTableField` but not `TableField`) to settle.
- **`ComputedField` (`@externalField`)** is documented as "inlined into the parent's projection at generation time" — that's `(source=One, action=Trivial, builder=Projection)` (the column is a `Field<X>` expression rather than a real column, but the projection mechanism is the same). Confirm.
- **`TableInterfaceField` (Child).** Single-table polymorphic with discriminator column. Likely `(source=One, action=Trivial, builder=Polymorphic-on-single-table)` if the discriminator lives on the parent record; `(source=One, action=Query.PerInvocation, builder=Polymorphic)` if it's a separate batched call. Settle from `TypeFetcherGenerator.buildTableInterfaceFieldFetcher`.
- **`QueryNodeField` / `QueryNodesField`.** Root polymorphic dispatch via Relay Node — `(source=Zero, action=Query, builder=Polymorphic)` with a Node-specific sub-axis on the `Polymorphic` arm. Spec needs to decide whether Node is a fourth `Polymorphism` sub-arm or a separate axis.
- **Modifiers axis composition.** `@splitQuery`, `@lookupKey`, `@reference`, `@condition`, error-channel, condition-join-reportability — which are bag-of-optionals on `Modifiers`, which are sub-axes of QueryBuilder arms (e.g. `LookupKey` as a sub-axis of `SubSelect`)?
- **Mutation `multiRow` and verb-on-DML.** R162's "verb-on-permit-identity" intuition maps to `DML(verb, multiRow, returnShape)`. Spec must enumerate which verbs admit `multiRow=true` (UPDATE, DELETE confirmed; INSERT and UPSERT excluded by construction) and where the compact-constructor invariants live.
- **Bulk-vs-single mutation input cardinality.** R162 dissolves this into `tableInputArg.list()`. With `QueryBuilder.DML` explicit, the same axis surfaces as a sub-component of the DML arm or as a separate property of the field's input shape.

## Consumer-side refactor scope

- `TypeFetcherGenerator` — the main 46-arm megaswitch reorganizes into outer-on-source-cardinality / inner-on-action / innermost-on-QueryBuilder.
- `FetcherEmitter` — currently dispatches the Trivial-like cases; restructures to dispatch on QueryBuilder within the `action=Trivial` branch.
- `FetcherRegistrationsEmitter` — currently consumes `BatchKeyField`; becomes a consumer of `source=One` fields whose action sub-arm carries a `LoaderRegistration`.
- `TypeClassGenerator.emitSelectionSwitch` and `collectRequiredProjectionColumns` — both consume `QueryBuilder.Projection` / `QueryBuilder.SubSelect` details; restructure around the QueryBuilder axis.
- `QueryConditionsGenerator` — consumes `(action=Query, builder ∈ {SubSelect, Polymorphic, DML})` (SQL-generating fields).
- `MultiTablePolymorphicEmitter` — single-axis on `QueryBuilder.Polymorphic` (multi-table arm).
- `SplitRowsMethodEmitter` — consumes `source=One` fields whose action sub-arm carries the DataLoader payload (the rows-method emitter).
- `GraphitronSchemaValidator` — currently dispatches on full permit identity for per-variant validation; restructures to per-axis validation rules.
- `MappingsConstantNameDedup.withResolvedChannel` — currently uses `WithErrorChannel` mixin; survives largely unchanged.
- The mixin interface retirements (above).
- Every test file that switches on permit type or `instanceof`s a permit — the audit didn't enumerate but `GraphitronSchemaBuilderTest` and `TypeFetcherGeneratorTest` are the heavy hitters.

## Dependencies and sequencing

- Supersedes **R162** and **R163**. Both can be Discarded once R164 enters Spec.
- **R161** (`unify-record-dml-on-carrier-walk`) is in flight and lands the carrier-walk unification. R164's `(source=Many, action=Query.OverSource)` modelling of the carrier-walk follow-up case depends on R161 having settled. Hard dependency.
- Touches every emitter, validator, and a substantial portion of the test surface. Large structural payload — likely 2-4 weeks of focused work — and should land before any further model evolution to avoid rebasing on a moving target.
- Recommended sequencing inside R164: (1) introduce the five axes (`SourceCardinality`, `Action`, `FieldCardinality`, `QueryBuilder`, `Modifiers`) as new sealed types on `Field` alongside the existing permits; (2) populate them from the existing permit data via a transitional adapter; (3) migrate consumers one at a time to switch on the new axes; (4) retire old permits and mixins; (5) delete the adapter. Each step is verifiable and reversible.
