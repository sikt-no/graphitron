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

## What is

The field model is a sealed hierarchy with 46 permits: `QueryField` (10), `MutationField` (8), `ChildField` (28). `TypeFetcherGenerator` dispatches on permit identity with one arm per leaf. Several consumer-side generators do the same, and a constellation of mixin interfaces (`BatchKeyField`, `SqlGeneratingField`, `MethodBackedField`, `LookupField`, `TableTargetField`) overlays cross-cutting traits.

Each permit name packs several independent decisions into one identifier:

- "where does the source come from": root call vs. parent-keyed vs. enumerating multiple rows
- "what does the fetcher do at request time": no I/O, `@service` invocation, generated jOOQ
- "what shape does the field return": single, list, connection
- "what jOOQ contribution does the field make": none, inlined column, own SELECT, UNION ALL, DML
- "is there a LookupMapping, error channel, splitQuery, ...": genuinely orthogonal modifiers

A permit like `RecordLookupTableField` collapses four of these decisions onto one name; `QueryServiceRecordField` collapses three. The cross product *is* the permit set, and adding a value on any axis multiplies the permits below it.

## What's to be

Five orthogonal slots on `Field`:

| Slot | Values | Meaning |
|---|---|---|
| `sourceCardinality()` | `Zero` / `One` / `Many` | How many values the fetcher consumes from `env.getSource()`. `Zero` = no parent (request entry); `One` = single parent value; `Many` = one source value enumerating multiple rows (the bulk-DML carrier's `Result<RecordN<...>>`). |
| `action()` | `Trivial` / `Service` / `Query` | What the fetcher does at request time. `Trivial` reads source / args / static config with no I/O; `Service` invokes a developer-supplied `@service` method; `Query` executes a graphitron-generated jOOQ statement. `@tableMethod` is `Query`: the developer's method returns a typed `Table<?>` that graphitron substitutes for the generated `Tables.*` reference, but graphitron owns the SELECT around it. |
| `fieldCardinality()` | `Single` / `List` / `Connection` | The field's output shape. Independent of source cardinality: `(source=One, field=List)` is `Author.books`; `(source=Zero, field=Single)` is `Query.user(id)`; `(source=Many, field=List)` is the bulk-carrier follow-up. |
| `builder()` | `None` / `Projection` / `SubSelect` / `Polymorphic` / `DML` / `Opaque` | The jOOQ contribution. `Projection` = column inlined into the parent SELECT; `SubSelect` = own SELECT (joined inline or fired as a separate batched call), carrying the `SourceKey` payload for WHERE/JOIN keying; `Polymorphic` = UNION ALL across tables or discriminator-column dispatch; `DML` = `INSERT`/`UPDATE`/`DELETE`/`UPSERT` with `RETURNING` (verb / multi-row / return-shape live here as components); `Opaque` = `@service` owns the I/O. |
| `modifiers()` | bag-of-optionals | `LookupMapping`, `ErrorChannel`, `SplitQuery`, ... Genuinely orthogonal axes that don't shape the cross product. |

```java
sealed interface Field permits QueryField, MutationField, ChildField {
    String name();
    SourceLocation location();
    ReturnTypeRef returnType();
    SourceCardinality sourceCardinality();   // sealed: Zero | One | Many
    Action action();                          // sealed (see below)
    FieldCardinality fieldCardinality();      // sealed: Single | List | Connection
    QueryBuilder builder();                   // sealed (see below)
    Modifiers modifiers();
}

sealed interface Action permits Action.Trivial, Action.Service, Action.Query {}

sealed interface Action.Query extends Action
        permits Action.Query.PerInvocation, Action.Query.OverSource {
    // PerInvocation: one SQL call per fetcher invocation.
    //   Args-keyed at source=Zero; parent-key-keyed at source=One
    //   (DataLoader amortizes across invocations when the parent is a list).
    // OverSource: one batched SQL call against the keys enumerated in the source value.
    //   Pairs strictly with source=Many.
}

sealed interface QueryBuilder permits
        QueryBuilder.None,
        QueryBuilder.Projection,
        QueryBuilder.SubSelect,        // payload: SourceKey + join/where shape
        QueryBuilder.Polymorphic,
        QueryBuilder.DML,              // payload: verb + multiRow + returnShape
        QueryBuilder.Opaque {}         // payload: MethodRef

record SomeFieldImpl(...) implements Field {
    public SomeFieldImpl {
        // The (Many, Query.PerInvocation) pair is per-element I/O at Many cardinality,
        // i.e. N+1 by construction. Forbidden at the type level via the sub-arm split
        // plus this compact-constructor invariant.
        if (sourceCardinality instanceof SourceCardinality.Many
                && action instanceof Action.Query.PerInvocation)
            throw new IllegalArgumentException("Many cardinality with per-invocation query = N+1");
        if (!(sourceCardinality instanceof SourceCardinality.Many)
                && action instanceof Action.Query.OverSource)
            throw new IllegalArgumentException("OverSource query requires Many cardinality");
    }
}
```

The `QueryField` / `MutationField` / `ChildField` sub-seals stay: they reflect authoring scope, a real and useful organizing concept. What collapses is the permit-per-combination inside each. `QueryInterfaceField` and `QueryUnionField` fold into one record carrying `builder = Polymorphic(Polymorphism.Interface(...) | Polymorphism.Union(...))`; the 28 `ChildField` permits fold to roughly 6-8 records, one per (parent-context, source-cardinality, action, builder) family.

`SourceKey` migrates from a fetcher-runtime concern to a payload of `QueryBuilder.SubSelect` and any builder arm that needs keyed projection: it's about which columns to project into the SELECT's WHERE / JOIN, not about how the fetcher consumes source at runtime. The `Reader` / `Wrap` distinctions stay; `SourceKey.Cardinality` retires, superseded by the field's `sourceCardinality()` slot.

The existing mixin interfaces fall out of the new axes and retire:

- `BatchKeyField` → `sourceCardinality() instanceof One` with a `LoaderRegistration` component on the action's sub-arm.
- `SqlGeneratingField` → `builder() instanceof Projection | SubSelect | Polymorphic | DML`.
- `MethodBackedField` → `action() instanceof Action.Service` (equivalently `builder() instanceof Opaque`).
- `LookupField` → `modifiers().lookupMapping().isPresent()`.
- `TableTargetField` → expressed via the `SubSelect` arm's payload.

`WithErrorChannel` and `ConditionJoinReportable` are genuinely orthogonal and survive on `Modifiers` or as sibling interfaces.

## The valid-pairs grid (draft)

Source cardinality × action:

| | `Trivial` | `Service` | `Query` |
|---|---|---|---|
| **`Zero`** | constants, API version, configuration (no existing permit; structurally allowed) | the four `Service*Field` permits | `QueryTableField`, `QueryLookupTableField`, `QueryInterfaceField`, `QueryUnionField`, `QueryTableInterfaceField`, `QueryNodeField`, `QueryNodesField`, `QueryTableMethodTableField`, all 8 `MutationField` DML permits |
| **`One`** | `ColumnField`, `TableField` (inline-join), `LookupTableField` (inline-join), `PropertyField`, `RecordField`, `NestingField`, `ConstructorField`, `ErrorsField`, `ColumnReferenceField`, `CompositeColumnField`, `CompositeColumnReferenceField`, `ParticipantColumnReferenceField`, `ComputedField`, `SingleRecord*` (single-carrier read-from-source arms) | `ChildField.ServiceTableField`, `ChildField.ServiceRecordField` | `SplitTableField`, `SplitLookupTableField`, `RecordTableField`, `RecordLookupTableField`, `RecordTableMethodField`, `TableMethodField`, child `InterfaceField`, child `UnionField`, child `TableInterfaceField` |
| **`Many`** | `SingleRecord*` (bulk-carrier read-from-source arms) | empty | `SingleRecordTableField` (bulk variant) — must be `Query.OverSource` |

Action × QueryBuilder pairing:

- `Trivial` → `None` | `Projection` | `SubSelect` (inline-join contribution; fetcher reads materialized columns off the parent record).
- `Service` → `Opaque`.
- `Query` → `SubSelect` | `Polymorphic` | `DML`.

The grid covers all 46 existing permits. Empty cells are structurally meaningful (see Edge cases below).

## Edge cases the Spec must resolve

- **`(source=Many, field=Single)`.** If the source enumerates many rows, the field's output is conventionally a list. Audit whether single-output-at-`source=Many` is ever valid, or whether the type system should forbid it via compact-constructor invariant.
- **`(Zero, Trivial)` slot.** Empty in today's schema-driven permit set. Decide whether to model up-front (e.g. a `ConstantField` permit for API version / configuration values) or leave the cell empty until a user need lands.
- **`(Many, Service)` slot.** Empty today. If a permit ever lands there it carries the same batched-only constraint as `(Many, Query)`. Decide whether to introduce `Action.Service.OverSource` now for symmetry, or only when a permit needs it.
- **DataLoader payload placement.** Today's `BatchKeyField` carries a `LoaderRegistration`. The registration belongs on the action sub-arm that needs it. Decide whether the loader sits on `Action.Query.PerInvocation` / `Action.Service` directly as a component, or whether each splits into sub-arms keyed off the cardinality (e.g. `Query.PerInvocation.ArgsKeyed` for `source=Zero`, `Query.PerInvocation.ParentKeyed(LoaderRegistration)` for `source=One`).
- **`TableField` (Child) classification.** `(source=One, action=Trivial, builder=SubSelect)` via inline-join, or `(source=One, action=Query.PerInvocation, builder=SubSelect)` via DataLoader-batched call? Read `TypeFetcherGenerator:313`'s `TableField` arm and `TypeClassGenerator.collectRequiredProjectionColumns` (which lists `SplitTableField` but not `TableField`) to settle.
- **`ComputedField` (`@externalField`).** Documented as "inlined into the parent's projection at generation time", which reads as `(source=One, action=Trivial, builder=Projection)` (the column is a `Field<X>` expression rather than a real column, but the projection mechanism is the same). Confirm from emitter.
- **`TableInterfaceField` (Child).** Single-table polymorphic with discriminator column. Likely `(One, Trivial, Polymorphic)` if the discriminator lives on the parent record; `(One, Query.PerInvocation, Polymorphic)` if it's a separate batched call. Settle from `TypeFetcherGenerator.buildTableInterfaceFieldFetcher`.
- **`QueryNodeField` / `QueryNodesField`.** Root polymorphic dispatch via Relay Node. `(Zero, Query, Polymorphic)` with a Node-specific sub-arm on `Polymorphic`, or a separate axis?
- **Modifiers composition.** `@splitQuery`, `@lookupKey`, `@reference`, `@condition`, error-channel, condition-join-reportability — which are bag-of-optionals on `Modifiers`, which are sub-axes of `QueryBuilder` arms (e.g. `LookupKey` as a sub-axis of `SubSelect`)?
- **`QueryBuilder.DML` payload.** Enumerate which verbs admit `multiRow=true` (`UPDATE`, `DELETE` confirmed; `INSERT` and `UPSERT` excluded by construction) and where the compact-constructor invariants live.
- **Bulk-vs-single mutation input cardinality.** Already dissolves into `tableInputArg.list()`. Decide whether this surfaces as a sub-component of `QueryBuilder.DML` or as a separate property of the field's input shape.

## Consumer-side refactor scope

- `TypeFetcherGenerator` — the 46-arm megaswitch reorganizes into outer-on-source-cardinality / inner-on-action / innermost-on-builder.
- `FetcherEmitter` — currently dispatches the Trivial-like cases; restructures to dispatch on `QueryBuilder` within the `action=Trivial` branch.
- `FetcherRegistrationsEmitter` — currently consumes `BatchKeyField`; becomes a consumer of `source=One` fields whose action sub-arm carries a `LoaderRegistration`.
- `TypeClassGenerator.emitSelectionSwitch` and `collectRequiredProjectionColumns` — both consume `QueryBuilder.Projection` / `QueryBuilder.SubSelect` details; restructure around the builder axis.
- `QueryConditionsGenerator` — consumes `action=Query` fields whose builder is `SubSelect | Polymorphic | DML`.
- `MultiTablePolymorphicEmitter` — single-axis on `QueryBuilder.Polymorphic` (multi-table arm).
- `SplitRowsMethodEmitter` — consumes `source=One` fields whose action sub-arm carries the DataLoader payload.
- `GraphitronSchemaValidator` — currently dispatches on full permit identity for per-variant validation; restructures to per-axis validation rules.
- `MappingsConstantNameDedup.withResolvedChannel` — uses `WithErrorChannel` mixin; survives largely unchanged.
- The mixin interface retirements (above).
- Every test file that switches on permit type. `GraphitronSchemaBuilderTest` and `TypeFetcherGeneratorTest` are the heavy hitters; full enumeration is a Spec task.

## Dependencies and sequencing

- **Supersedes R162 and R163.** R162's verb-on-permit-identity becomes the `(verb, multiRow, returnShape)` payload of `QueryBuilder.DML`. R163's `Record → Carrier` rename evaporates because the carrier-walk plumbing types decompose onto the new axes (the bulk-carrier follow-up SELECT is `(source=Many, action=Query.OverSource, builder=SubSelect)`; the carrier's read-from-source data fields are `(source=Many, action=Trivial, builder=None)`). Both Discardable once R164 enters Spec.
- **Depends on R161** (`unify-record-dml-on-carrier-walk`). R164's `(source=Many, action=Query.OverSource)` modelling of the carrier-walk follow-up case depends on R161 having settled. Hard dependency.
- Touches every emitter, validator, and a substantial portion of the test surface. Large structural payload, likely 2-4 weeks of focused work, and should land before any further model evolution to avoid rebasing on a moving target.
- Recommended sequencing inside R164: (1) introduce the five axes (`SourceCardinality`, `Action`, `FieldCardinality`, `QueryBuilder`, `Modifiers`) as new sealed types on `Field` alongside the existing permits; (2) populate from the existing permit data via a transitional adapter; (3) migrate consumers one at a time to switch on the new axes; (4) retire old permits and mixins; (5) delete the adapter. Each step verifiable and reversible.
